package probtest

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/url"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/metacubex/http"
	"github.com/metacubex/mihomo/component/ca"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/log"
)

// SpeedProbe describes one reachability probe in the gate. Every field is
// tunable because exits answer differently: what one region's nodes see as a
// block page another's serve as consent, and a hard-coded judgment that is
// wrong for an exit kills every node behind it without appeal.
type SpeedProbe struct {
	Name string `json:"name"`
	URL  string `json:"url"`
	// Enabled defaults to true; an explicit false skips the probe.
	Enabled *bool `json:"enabled,omitempty"`
	// ExpectStatus lists the accepted HTTP statuses. Empty accepts only 200.
	ExpectStatus []int `json:"expectStatus,omitempty"`
	// ReadLimit caps how much of the body is judged. A block page carries
	// its evidence early; reading more only burns data.
	ReadLimit int64 `json:"readLimit,omitempty"`
	// RequireMarker fails the probe unless the body contains it.
	RequireMarker string `json:"requireMarker,omitempty"`
	// ForbidMarker fails the probe when the body contains it.
	ForbidMarker string `json:"forbidMarker,omitempty"`
	// RequirePrefix fails the probe unless the trimmed body starts with it.
	RequirePrefix string `json:"requirePrefix,omitempty"`
	// AcceptConsent treats a consent landing as reachable. YouTube serves
	// some exits a consent interstitial before any page; the infrastructure
	// answered, so for reachability that counts as open.
	AcceptConsent bool `json:"acceptConsent,omitempty"`
}

// SpeedOptions bounds the real-transfer gate a node must clear after the
// latency rounds, before it may be published.
//
// The latency rounds only prove that a node can reach one small endpoint
// quickly. They say nothing about whether YouTube is reachable from the exit,
// or whether there is enough bandwidth behind it to carry video, and a node
// that fails either is exactly the kind of node a reader cannot use. This gate
// measures both, by pushing real traffic through the node.
//
// Every tunable arrives as JSON (see SpeedConfig) so a round can be retuned
// without rebuilding: missing fields fall back to the defaults below.
type SpeedOptions struct {
	// Enabled turns the gate on. A run with it off publishes whatever the
	// latency rounds left standing, as before.
	Enabled bool `json:"enabled"`
	// FloorMbps is the steady-state throughput a node must sustain. The
	// default of 0.5 is what YouTube's 240p needs; a node that cannot even
	// carry that has no business in a published configuration.
	FloorMbps float64 `json:"floorMbps,omitempty"`
	// MaxBytes caps how much one throughput measurement may download. The
	// measurement stops early once the floor is comfortably cleared, so this
	// bounds only the slow tail. Default 1 MiB.
	MaxBytes int64 `json:"maxBytes,omitempty"`
	// MaxTimeMs caps one throughput measurement in wall clock. Default 8000.
	MaxTimeMs int64 `json:"maxTimeMs,omitempty"`
	// Concurrency caps parallel measurements. Deliberately far below the
	// latency rounds' cap: parallel downloads compete for the device's own
	// uplink and every measurement comes out lower than the node deserves.
	Concurrency int `json:"concurrency,omitempty"`
	// ThroughputURL is the large file the gate measures against. It must
	// accept Range requests.
	ThroughputURL string `json:"throughputUrl,omitempty"`
	// Probes lists the reachability probes in order. Empty means the built
	// in YouTube probes.
	Probes []SpeedProbe `json:"probes,omitempty"`

	MaxTime time.Duration `json:"-"`
}

func (o SpeedOptions) withDefaults() SpeedOptions {
	if o.FloorMbps <= 0 {
		o.FloorMbps = defaultSpeedFloorMbps
	}
	if o.MaxBytes <= 0 {
		o.MaxBytes = defaultSpeedMaxBytes
	}
	if o.MaxTimeMs > 0 {
		o.MaxTime = time.Duration(o.MaxTimeMs) * time.Millisecond
	}
	if o.MaxTime == 0 {
		o.MaxTime = defaultSpeedMaxTime
	}
	if o.Concurrency <= 0 {
		o.Concurrency = defaultSpeedConcurrency
	}
	if o.ThroughputURL == "" {
		o.ThroughputURL = defaultThroughputURL
	}
	if len(o.Probes) == 0 {
		o.Probes = defaultProbes()
	} else {
		for i := range o.Probes {
			if o.Probes[i].ReadLimit <= 0 {
				o.Probes[i].ReadLimit = defaultProbeReadLimit
			}
		}
	}
	return o
}

// ParseSpeedOptions decodes the gate's tunables. Empty means the built in
// defaults; a file on the device overrides any field it names, so tuning a
// round is editing JSON rather than rebuilding.
func ParseSpeedOptions(raw string) (SpeedOptions, error) {
	opt := SpeedOptions{}
	if strings.TrimSpace(raw) == "" {
		return opt.withDefaults(), nil
	}
	if err := json.Unmarshal([]byte(raw), &opt); err != nil {
		return SpeedOptions{}, fmt.Errorf("bad speed config: %w", err)
	}
	return opt.withDefaults(), nil
}

// defaultProbes is the built in gate: the site, the video CDN front door,
// then a real video page for the bot check. Order matters; the first failure
// stops the rest.
func defaultProbes() []SpeedProbe {
	return []SpeedProbe{
		{
			Name: "site", URL: defaultSiteURL, Enabled: boolPtr(true),
			ExpectStatus: []int{200}, ReadLimit: homeReadLimit,
			RequireMarker: "youtube", AcceptConsent: true,
		},
		{
			Name: "edge", URL: defaultEdgeURL, Enabled: boolPtr(true),
			ExpectStatus: []int{200}, ReadLimit: edgeReadLimit,
			// The edge answers differently per exit: some return a JSON
			// mapping, others a plain DNS redirect line naming the nearest
			// video edge. Both mean the video CDN front door is reachable,
			// so only the status is judged, never the body shape.
		},
		{
			Name: "watch", URL: defaultWatchURL, Enabled: boolPtr(true),
			ExpectStatus: []int{200}, ReadLimit: watchReadLimit,
			ForbidMarker: youtubeBotMarker, AcceptConsent: true,
		},
	}
}

func boolPtr(b bool) *bool { return &b }

// probeEnabled reports whether a probe runs. A probe left out of nothing is
// on; only an explicit false skips it.
func (p SpeedProbe) probeEnabled() bool {
	return p.Enabled == nil || *p.Enabled
}

const (
	defaultSpeedFloorMbps   = 0.5
	defaultSpeedMaxBytes    = 1 << 20 // 1 MiB
	defaultSpeedMaxTime     = 8 * time.Second
	defaultSpeedConcurrency = 6

	// The stage progress reports during the speed gate, so a caller can tell
	// it apart from the latency rounds without guessing from the counts.
	StageSpeedTest = "speedtest"

	// youtubeHome proves the site itself resolves and serves through the exit.
	// A hijacked or reset path fails here.
	defaultSiteURL = "https://www.youtube.com/"
	// youtubeEdge is the front door of the video CDN. It is blocked alongside
	// the video hosts and often independently of the site above, which is why
	// the site check alone cannot stand in for it. It answers a tiny JSON.
	defaultEdgeURL = "https://redirector.googlevideo.com/report_mapping?di=no"
	// youtubeWatch is a real video page. Shared exits are frequently blocked
	// by YouTube's bot check, which intercepts exactly these pages while the
	// site and the CDN front door both answer happily, so this is the only
	// probe of the three that catches the most common real-world failure.
	defaultWatchURL = "https://www.youtube.com/watch?v=jNQXAC9IVRw"
	// youtubeBotMarker is the text of that interstitial. Detection only needs
	// the body prefix: the interstitial is a small page that carries it early.
	youtubeBotMarker = "confirm you're not a bot"

	// defaultThroughputURL is a large public video on Google's own storage.
	// It accepts Range requests, so a measurement downloads exactly the
	// window it needs rather than a whole movie.
	// The old gtv-videos-bucket URL answers 403 to anonymous range requests
	// since Google locked it down, which zeros every node that reaches this
	// stage. Cloudflare's speed endpoint honours the 1 MiB transfer window.
	defaultThroughputURL = "https://speed.cloudflare.com/__down?bytes=1048576"
	defaultProbeReadLimit = 64 << 10

	// Speed gate budgets. The reachability probes read only a prefix of each
	// response: a hijacked page carries its evidence early, and the point is
	// to bound the traffic a full round of probing costs.
	homeReadLimit  = 64 << 10
	edgeReadLimit  = 16 << 10
	watchReadLimit = 128 << 10
	// A measurement needs a minimum sample before its average means anything.
	speedMinBytes = 64 << 10
	// Early exit: once this many bytes have arrived averaging comfortably
	// above the floor, the node has proven itself and reading more would only
	// burn the reader's data. A node below the floor never exits early and
	// burns its full time cap instead, which is the trade.
	speedEarlyExitBytes = 128 << 10
	speedEarlyExitRatio = 1.5

	probeTimeoutPerNode = 60 * time.Second
)

// SpeedTier names the video quality a measured throughput can carry.
func SpeedTier(mbps float64) string {
	switch {
	case mbps >= 8:
		return "1080p"
	case mbps >= 2.5:
		return "720p"
	case mbps >= 1:
		return "360p"
	default:
		return "240p"
	}
}

// speedGate drops every survivor that cannot actually serve YouTube and
// returns those that remain, marking the rest with why they went.
//
// It runs after the latency rounds and before the rewrite, so a node the gate
// drops never reaches a published group and the configuration a reader
// downloads only ever names nodes proven capable of the traffic they will
// carry.
func speedGate(ctx context.Context, survivors []*node, opt Options, onProgress func(Progress)) []*node {
	sp := opt.SpeedTest.withDefaults()

	started := time.Now()
	var passed, failed int64
	emit := func(done, total int) {
		if onProgress == nil {
			return
		}
		onProgress(Progress{
			Stage:     StageSpeedTest,
			Round:     opt.Rounds,
			Rounds:    opt.Rounds,
			ElapsedMs: time.Since(started).Milliseconds(),
			Passed:    int(atomic.LoadInt64(&passed)),
			Failed:    int(atomic.LoadInt64(&failed)),
			Total:     total,
			Done:      done,
		})
	}

	total := len(survivors)
	emit(0, total)

	var mu sync.Mutex
	kept := make([]*node, 0, len(survivors))
	var wg sync.WaitGroup
	sem := make(chan struct{}, sp.Concurrency)
	dropped := 0

	// Failure classes, so one summary line at the end says which probe is
	// doing the killing instead of leaving the next all-fail round a mystery.
	var dialFail int64
	failCount := map[string]*int64{}
	failFirst := map[string]string{}

	type speedResult struct {
		mbps  float64
		stage string
		err   error
	}

	for _, n := range survivors {
		if ctx.Err() != nil {
			break
		}

		wg.Add(1)
		sem <- struct{}{}

		go func(n *node) {
			defer wg.Done()
			defer func() { <-sem }()

			// Same reasoning as the latency rounds: a dial that never
			// answers must not hold a slot forever. Throughput transfers
			// self-bound by their own caps, so the watchdog only covers
			// probes that hang before any byte can be judged.
			res := make(chan speedResult, 1)
			go func() {
				mbps, stage, err := speedCheck(ctx, n.proxy, sp)
				res <- speedResult{mbps: mbps, stage: stage, err: err}
			}()

			timer := time.NewTimer(probeTimeoutPerNode + 30*time.Second)
			defer timer.Stop()

			drop := func(mbps float64, stage string, err error) {
				atomic.AddInt64(&failed, 1)
				mu.Lock()
				dropped++
				n.failed = true
				n.lastErr = fmt.Sprintf("speed gate: %s", err.Error())
				key := stage
				if key == "" {
					key = "other"
				}
				p := failCount[key]
				if p == nil {
					var counter int64
					p = &counter
					failCount[key] = p
					failFirst[key] = err.Error()
				}
				*p++
				if isDialError(err) {
					dialFail++
				}
				mu.Unlock()
			}

			select {
			case r := <-res:
				if r.err != nil {
					drop(r.mbps, r.stage, r.err)
					return
				}
				atomic.AddInt64(&passed, 1)
				n.speedMbps = r.mbps
				n.speedTier = SpeedTier(r.mbps)
				mu.Lock()
				kept = append(kept, n)
				mu.Unlock()
			case <-ctx.Done():
				drop(0, "", ctx.Err())
			case <-timer.C:
				drop(0, "", fmt.Errorf("probe did not answer in time"))
			}
		}(n)

		emit(dropped+len(kept), total)
	}

	wg.Wait()
	emit(len(kept)+dropped, total)

	// One line per gate, not per node: when a round dies with nothing left
	// standing, this is what says whether the site, the video CDN, the bot
	// check or the throughput did the killing, with the first error of each
	// class for the exact symptom.
	mu.Lock()
	failSummary := map[string]int64{}
	for name, counter := range failCount {
		failSummary[name] = *counter
	}
	log.Infoln(
		"speed gate finished: tested=%d kept=%d dropped=%d dial=%d failures=%v first=%v",
		total, len(kept), dropped, atomic.LoadInt64(&dialFail), failSummary, failFirst,
	)
	mu.Unlock()

	return kept
}

// Gate failure classes, naming the probe a node died at.
const (
	gateStageSite  = "site"
	gateStageEdge  = "edge"
	gateStageWatch = "watch"
	gateStageSpeed = "throughput"
)

// isDialError reports whether the failure happened before any HTTP could be
// judged: the tunnel itself never came up.
func isDialError(err error) bool {
	if err == nil {
		return false
	}
	s := err.Error()
	return strings.Contains(s, "dial") || strings.Contains(s, "connection") ||
		strings.Contains(s, "i/o timeout") || strings.Contains(s, "no such host")
}

// speedCheck runs the full gate against one node: three reachability probes
// into YouTube, then one throughput measurement. The first failure stops the
// rest, so a node that cannot reach the site at all never downloads video.
//
// The returned stage names which probe decided, for the gate's summary.
func speedCheck(ctx context.Context, proxy C.Proxy, sp SpeedOptions) (float64, string, error) {
	ctx, cancel := context.WithTimeout(ctx, probeTimeoutPerNode)
	defer cancel()

	if stage, err := youtubeReachable(ctx, proxy, sp); err != nil {
		return 0, stage, err
	}
	return youtubeThroughput(ctx, proxy, sp)
}

// youtubeReachable walks the configured reachability probes in order. The
// first failure stops the rest, so a node that cannot reach the site at all
// never downloads video.
func youtubeReachable(ctx context.Context, proxy C.Proxy, sp SpeedOptions) (string, error) {
	for _, p := range sp.Probes {
		if !p.probeEnabled() {
			continue
		}
		if err := runProbe(ctx, proxy, p); err != nil {
			name := p.Name
			if name == "" {
				name = p.URL
			}
			return name, fmt.Errorf("%s: %w", name, err)
		}
	}
	return "", nil
}

// isConsentLanding reports a consent page rather than a block. YouTube serves
// some exits a consent interstitial before any page: the infrastructure
// answered, and playback behind it does not go through that page.
func isConsentLanding(host, body string) bool {
	if strings.HasSuffix(strings.ToLower(host), "consent.youtube.com") {
		return true
	}
	return strings.Contains(body, "consent.youtube.com")
}

// redirectWithinGoogle keeps follows inside Google's own hosts. A hijacked
// exit answers with a redirect to its own portal; following that would turn
// the hijack into a passing probe, so only Google hosts may be followed.
func redirectWithinGoogle(host string) bool {
	h := strings.ToLower(strings.TrimSuffix(host, "."))
	for _, suf := range []string{"youtube.com", "googlevideo.com", "google.com", "ggpht.com", "ytimg.com"} {
		if h == suf || strings.HasSuffix(h, "."+suf) {
			return true
		}
	}
	return false
}

// runProbe fetches one probe URL through the proxy and judges the landing.
func runProbe(ctx context.Context, proxy C.Proxy, p SpeedProbe) error {
	conn, err := dialThrough(ctx, proxy, p.URL)
	if err != nil {
		return err
	}
	defer conn.Close()

	client := proxyClient(conn, proxy, probeTimeoutPerNode)
	defer client.CloseIdleConnections()

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, p.URL, nil)
	if err != nil {
		return err
	}
	req.Header.Set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36")

	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	if len(p.ExpectStatus) > 0 {
		ok := false
		for _, want := range p.ExpectStatus {
			if resp.StatusCode == want {
				ok = true
				break
			}
		}
		if !ok {
			return fmt.Errorf("HTTP %d", resp.StatusCode)
		}
	} else if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("HTTP %d", resp.StatusCode)
	}

	landing := ""
	if resp.Request != nil && resp.Request.URL != nil {
		landing = resp.Request.URL.Hostname()
	}

	body, err := io.ReadAll(io.LimitReader(resp.Body, p.ReadLimit))
	if err != nil {
		return err
	}
	text := string(body)

	if p.AcceptConsent && isConsentLanding(landing, text) {
		// A consent page is still YouTube's own infrastructure answering:
		// reachability proven, nothing blocked.
		return nil
	}
	if p.RequireMarker != "" && !strings.Contains(strings.ToLower(text), strings.ToLower(p.RequireMarker)) {
		return fmt.Errorf("body lacks %q (%d bytes)", p.RequireMarker, len(text))
	}
	if p.RequirePrefix != "" && !strings.HasPrefix(strings.TrimSpace(text), p.RequirePrefix) {
		return fmt.Errorf("body does not start with %q (%q)", p.RequirePrefix, truncate(text, 40))
	}
	if p.ForbidMarker != "" && strings.Contains(strings.ToLower(text), strings.ToLower(p.ForbidMarker)) {
		return fmt.Errorf("body hit %q", p.ForbidMarker)
	}
	return nil
}

// youtubeThroughput measures the steady-state rate at which the node carries
// video data, returning it in megabits per second.
//
// The clock starts at the first payload byte, so connection setup and the
// server's think time do not dilute the rate. The download stops at the byte
// cap, the time cap, or as soon as the average clears the floor by a wide
// margin, whichever comes first.
func youtubeThroughput(ctx context.Context, proxy C.Proxy, sp SpeedOptions) (float64, string, error) {
	conn, err := dialThrough(ctx, proxy, sp.ThroughputURL)
	if err != nil {
		return 0, gateStageSpeed, fmt.Errorf("throughput dial: %w", err)
	}
	defer conn.Close()

	client := proxyClient(conn, proxy, sp.MaxTime+20*time.Second)
	defer client.CloseIdleConnections()

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, sp.ThroughputURL, nil)
	if err != nil {
		return 0, gateStageSpeed, err
	}
	// A range request keeps the transfer to the measurement window. Identity
	// encoding keeps the byte count honest: a compressed stream would measure
	// the codec, not the pipe.
	req.Header.Set("Range", fmt.Sprintf("bytes=0-%d", sp.MaxBytes-1))
	req.Header.Set("Accept-Encoding", "identity")

	resp, err := client.Do(req)
	if err != nil {
		return 0, gateStageSpeed, fmt.Errorf("throughput request: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusPartialContent && resp.StatusCode != http.StatusOK {
		return 0, gateStageSpeed, fmt.Errorf("throughput request got HTTP %d", resp.StatusCode)
	}

	got, elapsed, err := drainMeasured(resp.Body, sp)
	if err != nil {
		return 0, gateStageSpeed, fmt.Errorf("throughput transfer: %w", err)
	}
	if got < speedMinBytes {
		return 0, gateStageSpeed, fmt.Errorf("only %d bytes arrived before the transfer ended", got)
	}

	mbps := float64(got) * 8 / elapsed.Seconds() / 1e6
	if mbps < sp.FloorMbps {
		return mbps, gateStageSpeed, fmt.Errorf("%.2f Mbps below the %.2f Mbps floor", mbps, sp.FloorMbps)
	}
	return mbps, "", nil
}

// drainMeasured reads the body while timing it, stopping at the caps or at
// early exit. It returns the bytes read and the time they took.
func drainMeasured(body io.Reader, sp SpeedOptions) (int64, time.Duration, error) {
	buf := make([]byte, 32<<10)
	var got int64
	var start time.Time

	for {
		n, err := body.Read(buf)
		if n > 0 {
			if start.IsZero() {
				start = time.Now()
			}
			got += int64(n)
			elapsed := time.Since(start)

			if got >= sp.MaxBytes || elapsed >= sp.MaxTime {
				return got, elapsed, nil
			}
			if got >= speedEarlyExitBytes {
				if mbps := float64(got) * 8 / elapsed.Seconds() / 1e6; mbps >= sp.FloorMbps*speedEarlyExitRatio {
					return got, elapsed, nil
				}
			}
		}
		if err != nil {
			if err == io.EOF && !start.IsZero() {
				// The server ended the range early. The sample is still a
				// measurement, provided enough of it arrived to mean anything.
				return got, time.Since(start), nil
			}
			if start.IsZero() {
				return 0, 0, fmt.Errorf("no data arrived: %w", err)
			}
			return got, time.Since(start), nil
		}
	}
}

// dialThrough opens one connection to the host of rawURL through the proxy,
// the same way URLTest does, leaving DNS resolution to the exit.
func dialThrough(ctx context.Context, proxy C.Proxy, rawURL string) (net.Conn, error) {
	addr, err := remoteMetadata(rawURL)
	if err != nil {
		return nil, err
	}
	return proxy.DialContext(ctx, &addr)
}

// remoteMetadata mirrors adapter's urlToMetadata, which is private there.
func remoteMetadata(rawURL string) (addr C.Metadata, err error) {
	u, err := url.Parse(rawURL)
	if err != nil {
		return
	}

	port := u.Port()
	if port == "" {
		switch u.Scheme {
		case "https":
			port = "443"
		case "http":
			port = "80"
		default:
			err = fmt.Errorf("%s scheme not supported", rawURL)
			return
		}
	}

	err = addr.SetRemoteAddress(net.JoinHostPort(u.Hostname(), port))
	return
}

// proxyClient wraps a connection already established through a proxy into an
// HTTP client, so requests over it ride the tunnel. The first request reuses
// the handed-in connection; every later one (a redirect follow-up, a second
// Do on the same client) must be a fresh tunnel instead of the now-closed
// first connection — without that, a consent redirect or the throughput
// request inherits a dead wire and the node fails with a misleading EOF.
func proxyClient(conn net.Conn, proxy C.Proxy, timeout time.Duration) *http.Client {
	tlsConfig, err := ca.GetTLSConfig(ca.Option{})
	if err != nil {
		// Without TLS config there is no HTTPS; surface it by refusing rather
		// than silently downgrading the probe.
		return &http.Client{Transport: failingTransport{err}}
	}

	handed := false

	return &http.Client{
		Timeout: timeout,
		Transport: &http.Transport{
			DialContext: func(ctx context.Context, network, address string) (net.Conn, error) {
				if !handed {
					handed = true
					return conn, nil
				}
				var addr C.Metadata
				if err := addr.SetRemoteAddress(address); err != nil {
					return nil, err
				}
				return proxy.DialContext(ctx, &addr)
			},
			TLSClientConfig:     tlsConfig,
			TLSHandshakeTimeout: 10 * time.Second,
			DisableKeepAlives:   true,
		},
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			// Datacenter exits are routinely answered with a consent
			// interstitial before any page. That is still Google answering,
			// so it may be followed, but only within Google's own hosts: a
			// hijacked exit answers with a redirect to its own portal, and
			// following that would turn a hijack into a passing probe.
			if len(via) >= 4 {
				return fmt.Errorf("too many redirects")
			}
			if !redirectWithinGoogle(req.URL.Host) {
				return fmt.Errorf("redirect leaves Google (%s)", req.URL.Host)
			}
			return nil
		},
	}
}

type failingTransport struct{ err error }

func (f failingTransport) RoundTrip(*http.Request) (*http.Response, error) {
	return nil, f.err
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n] + "..."
}
