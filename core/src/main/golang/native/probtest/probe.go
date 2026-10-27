package probtest

import (
	"context"
	"fmt"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/metacubex/mihomo/adapter"
	"github.com/metacubex/mihomo/common/utils"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/log"
)

type node struct {
	name    string
	typ     string
	proxy   C.Proxy
	passes  int
	delays  []int
	failed  bool
	lastErr string
	// note carries something worth saying about a node that was kept anyway:
	// today, that the reachability probe could not open the site from its exit.
	// It travels into the report so a published node is never quietly
	// indistinguishable from one that answered everything asked of it.
	note string
	// speedMbps and speedTier are set by the speed gate for nodes that
	// cleared it.
	speedMbps float64
	speedTier string
}

// buildNode pre-validates a raw proxy entry and parses it. It returns a
// non-empty reason when the entry must not be parsed at all.
//
// Pre-validation is what keeps a hostile subscription from taking the process
// down: adapter.ParseProxy reaches constructors that call log.Fatalln (and
// log.Fatalln is os.Exit, which cannot be recovered from), so those inputs
// have to be filtered before any constructor runs.
func buildNode(idx int, raw any) (*node, string) {
	entry, ok := raw.(map[string]any)
	if !ok {
		return nil, fmt.Sprintf("entry is %T, expected a mapping", raw)
	}

	name, _ := entry["name"].(string)
	if name == "" {
		return nil, "missing name"
	}

	typ, ok := entry["type"].(string)
	if !ok || typ == "" {
		return nil, "missing type"
	}

	if reason := unsafeEntry(entry, typ); reason != "" {
		return nil, reason
	}

	proxy, err := parseSafe(entry)
	if err != nil {
		// The type goes in because the count does not explain itself: a round
		// reported 586 nodes lost to "parse" out of 986, and nothing in that
		// says whether the core cannot read one protocol, whether those entries
		// were malformed, or whether the number is one source's worth of
		// something unsupported. Naming the type puts it in the log's own
		// grouping, so it turns up with the rest of the unnamed failures.
		return nil, fmt.Sprintf("parse %s: %s", typ, err.Error())
	}

	return &node{name: name, typ: typ, proxy: proxy}, ""
}

func unsafeEntry(entry map[string]any, typ string) string {
	switch typ {
	case "vless":
		// outbound.NewVless calls log.Fatalln (=> os.Exit(1)) for the
		// deprecated XTLS flows, so reject anything we do not positively
		// know is safe instead of gambling on a panic recover.
		flow, _ := entry["flow"].(string)
		if flow != "" && flow != "xtls-rprx-vision" {
			return fmt.Sprintf("unsafe vless flow %q (unsupported or fatal)", flow)
		}
	case "ssh":
		// outbound.NewSSH reads option.PrivateKey from disk at parse time;
		// a subscription must not be able to make us open arbitrary paths.
		if k := entry["private-key"]; k != nil {
			if s, _ := k.(string); s != "" {
				return "ssh private-key path refused"
			}
		}
	}
	return ""
}

func parseSafe(entry map[string]any) (proxy C.Proxy, err error) {
	defer func() {
		if r := recover(); r != nil {
			proxy = nil
			err = fmt.Errorf("%v", r)
		}
	}()

	proxy, err = adapter.ParseProxy(entry)

	return
}

// probeRounds runs spaced rounds over every node that is still in the
// running, eliminating a node as soon as it fails a single round.
func probeRounds(
	ctx context.Context,
	nodes []*node,
	opt Options,
	expected utils.IntRanges[uint16],
	onProgress func(Progress),
) ([]*node, error) {
	started := time.Now()

	emit := func(round int) {
		if onProgress == nil {
			return
		}
		running, failed := 0, 0
		for _, n := range nodes {
			if n.failed {
				failed++
			} else {
				running++
			}
		}
		onProgress(Progress{
			Round:     round,
			Rounds:    opt.Rounds,
			ElapsedMs: time.Since(started).Milliseconds(),
			Passed:    running,
			Failed:    failed,
		})
	}

	for round := 1; round <= opt.Rounds; round++ {
		if err := ctx.Err(); err != nil {
			return nil, err
		}

		eligible := make([]*node, 0, len(nodes))
		for _, n := range nodes {
			if !n.failed {
				eligible = append(eligible, n)
			}
		}

		if len(eligible) == 0 {
			break
		}

		emit(round)

		// Live ticks while the round grinds. The boundary emits only fire at
		// round edges, so a round that spends twenty minutes timing its slow
		// probes out shows the same numbers the whole way through and reads
		// as a hang. The ticker in probeOnce reports every few seconds
		// instead; passed and failed keep the boundary semantics - everything
		// not yet failed is still running - and done counts the verdicts this
		// round has handed down.
		before := len(nodes) - len(eligible)
		tick := func(done, failedNow int) {
			if onProgress == nil {
				return
			}
			onProgress(Progress{
				Round:     round,
				Rounds:    opt.Rounds,
				ElapsedMs: time.Since(started).Milliseconds(),
				Passed:    len(eligible) - failedNow,
				Failed:    before + failedNow,
				Done:      done,
			})
		}

		if err := probeOnce(ctx, eligible, opt, expected, tick); err != nil {
			return nil, err
		}

		if round < opt.Rounds && opt.RoundGap > 0 {
			timer := time.NewTimer(opt.RoundGap)
			select {
			case <-ctx.Done():
				timer.Stop()
				return nil, ctx.Err()
			case <-timer.C:
			}
		}
	}

	emit(opt.Rounds)

	survivors := make([]*node, 0, len(nodes))
	for _, n := range nodes {
		if !n.failed && n.passes == opt.Rounds {
			survivors = append(survivors, n)
		}
	}

	return survivors, nil
}

// probeRetryBackoff is the pause before the single retry of a probe that never
// produced an answer.
const probeRetryBackoff = 500 * time.Millisecond

// probeTickInterval paces the live progress of a round: fast enough that a
// twenty-minute round visibly moves, slow enough that each tick is worth a
// notification rewrite for a reader who is only asking whether it is alive.
const probeTickInterval = 5 * time.Second

// probeRetrySlack is the margin added to the delay limit when deciding how
// long that retry may wait.
const probeRetrySlack = 500 * time.Millisecond

// probeRetryDeadline is how long the retry of a silent probe may wait, and it
// is deliberately far shorter than a full probe. URLTest's delay is the time
// to the answer, so a retry that answers after the delay limit is dropped by
// the same arithmetic that dropped the first attempt: every node that could
// have survived a full-length retry survives this one as well, and the ones
// that could not are merely waited for more slowly. The round's verdict is
// unchanged; only its cost is.
func probeRetryDeadline(opt Options, maxDelayMs int64) time.Duration {
	d := time.Duration(maxDelayMs)*time.Millisecond + probeRetrySlack
	if d > opt.RoundTimeout {
		return opt.RoundTimeout
	}
	return d
}

// retryableProbeFailure decides whether a failed probe is worth asking once
// more.
//
// A round that loses most of its candidates to a network that drops
// connections cannot tell a dead node from a dropped one, and on a filtered
// link - where a burst of parallel dials is exactly what provokes the drop -
// the second ask is what separates the two. It stays a single ask, and the
// second sample is judged by the same delay limit as the first.
//
// Definitive answers are not retried. The server refused the account, the
// target answered 4xx/5xx, the protocol rejected the handshake - those are the
// exit's own verdict, and asking again spends a probe slot to hear the same
// thing.
func retryableProbeFailure(err error) bool {
	if err == nil {
		return false
	}
	s := err.Error()
	for _, definitive := range []string{
		"need auth",
		"unexpected status",
		"Forbidden",
		"Bad Request",
		"Bad Gateway",
	} {
		if strings.Contains(s, definitive) {
			return false
		}
	}
	return true
}

// needsResample decides whether a round has to ask a node again, and the rule
// it encodes is that a round keeps the fastest sample it obtained.
//
// A probe that never answered is worth one more ask, and so is an answer that
// missed the delay limit: the limit sits on the noise floor, and the round that
// produced this code watched a node measured at 375ms and 374ms lose because
// its third sample came back at 1498ms. Re-asking once lets the round report
// the sample it got rather than the one it happened to get last, and it does
// not relax the limit - a node whose second sample also misses is dropped
// exactly as the first one was.
func needsResample(err error, delay uint16, maxDelayMs int64) bool {
	if err != nil {
		return retryableProbeFailure(err)
	}
	return maxDelayMs > 0 && int64(delay) > maxDelayMs
}

// probeOnce probes every given node concurrently within the concurrency cap.
// notify, when set, is called from a single pacing goroutine with the count of
// verdicts handed down so far and the count of those verdicts that eliminated
// their node.
func probeOnce(ctx context.Context, eligible []*node, opt Options, expected utils.IntRanges[uint16], notify func(done, failed int)) error {
	var wg sync.WaitGroup
	sem := make(chan struct{}, opt.Concurrency)

	maxDelay := effectiveMaxDelayMs(opt)

	// A sample that is unusable, by going silent or by answering over the delay
	// limit, is asked once more, and these count how often that mattered. See
	// retryableProbeFailure.
	var resampled, saved int64

	// Live progress counters. Both are atomics because the ticker goroutine
	// reads them while the probe goroutines write.
	var probed, roundFailed int64

	if notify != nil {
		stop := make(chan struct{})
		stopped := make(chan struct{})
		defer func() {
			close(stop)
			<-stopped
		}()
		go func() {
			defer close(stopped)
			ticker := time.NewTicker(probeTickInterval)
			defer ticker.Stop()
			last := -1
			for {
				select {
				case <-stop:
					return
				case <-ctx.Done():
					return
				case <-ticker.C:
					if done := int(atomic.LoadInt64(&probed)); done != last {
						last = done
						notify(done, int(atomic.LoadInt64(&roundFailed)))
					}
				}
			}
		}()
	}

	for i, n := range eligible {
		if err := ctx.Err(); err != nil {
			// A node that was never handed to a probe has neither passed nor
			// failed, and the tally counts everything not failed as still
			// running. So a cancelled round reports the nodes it never looked
			// at as though they had survived it. Record the cancellation on
			// them: the round still errors out, but the count it printed while
			// it died no longer claims survivors it did not test.
			for _, skipped := range eligible[i:] {
				markFailed(skipped, err.Error())
			}
			break
		}

		wg.Add(1)
		sem <- struct{}{}

		go func(n *node) {
			defer wg.Done()
			defer func() { <-sem }()

			// Every eligible node is probed on its own, including several
			// accounts listed against one server:port. A shared endpoint is
			// tempting to probe once - the round that produced this code saw
			// ninety-one names resolve to thirty-five machines - but "the
			// first account on this endpoint did not answer" is not "the
			// endpoint is dead". The failure that matters here is frequently
			// the account's own: a wrong UUID, a retired password, a protocol
			// the server stopped serving. Those leave the endpoint perfectly
			// alive for the accounts beside it, so short-circuiting the rest
			// of the endpoint on one node's verdict silently converts one bad
			// account into a whole machine's worth of dropped nodes.
			//
			// A dial that never answers must not hold a semaphore slot
			// forever: sixteen of them is all it takes to deadlock the whole
			// round, because no other node can start. URLTest is therefore
			// run in its own goroutine behind a hard deadline of our own,
			// which no transport can opt out of. The leaked dial behind a
			// timed-out probe belongs to the node, not the round, so the
			// node is failed and the round moves on.
			res := make(chan probeResult, 1)
			go func() {
				delay, err := probeOne(ctx, n, opt, expected)
				if needsResample(err, delay, maxDelay) && ctx.Err() == nil {
					atomic.AddInt64(&resampled, 1)
					// A short pause before asking again: two dials in the
					// same instant meet the same state.
					select {
					case <-time.After(probeRetryBackoff):
					case <-ctx.Done():
					}
					if ctx.Err() == nil {
						// The retry waits only as long as a delay can still
						// be a pass, so a hopeless retry costs a fraction of
						// a probe slot instead of a whole probe.
						retryCtx, cancelRetry := context.WithTimeout(ctx, probeRetryDeadline(opt, maxDelay))
						retryDelay, retryErr := probeOne(retryCtx, n, opt, expected)
						// The deadline is released here rather than left to
						// the goroutine's exit: the slot it holds is the
						// round's scarcest resource.
						cancelRetry()
						// The first error is kept if the retry fails too:
						// the report already says why the node lost.
						if retryErr == nil && (err != nil || retryDelay < delay) {
							// A retry that answers inside the short deadline
							// still has to clear the delay limit, which
							// applyProbe does below.
							if maxDelay <= 0 || int64(retryDelay) <= maxDelay {
								atomic.AddInt64(&saved, 1)
							}
							delay, err = retryDelay, nil
						}
					}
				}
				res <- probeResult{delay: delay, err: err}
			}()

			timer := time.NewTimer(hardProbeCap(opt))
			defer timer.Stop()

			select {
			case r := <-res:
				applyProbe(n, r, maxDelay)
			case <-ctx.Done():
				markFailed(n, ctx.Err().Error())
			case <-timer.C:
				markFailed(n, "probe did not answer in time")
			}

			// The verdict above was folded into the node by this goroutine, so
			// reading the flag back races with nothing.
			if n.failed {
				atomic.AddInt64(&roundFailed, 1)
			}
			atomic.AddInt64(&probed, 1)
		}(n)
	}

	wg.Wait()

	if notify != nil {
		// The boundary emit for the next round only repeats this tally at the
		// start of that round, so close the round out with its own last word:
		// without it the final stragglers under the ticker interval are
		// counted nowhere until the next round opens.
		notify(int(atomic.LoadInt64(&probed)), int(atomic.LoadInt64(&roundFailed)))
	}

	if n := atomic.LoadInt64(&resampled); n > 0 {
		log.Infoln("probe round: %d node(s) re-sampled after an unusable first answer, %d cleared the limit", n, atomic.LoadInt64(&saved))
	}

	return ctx.Err()
}

type probeResult struct {
	delay uint16
	err   error
}

// hardProbeCap bounds one node's turn no matter how deep the dial hangs.
// RoundTimeout ends the probe cooperatively; the cap ends it regardless,
// with enough slack for a legitimate probe that is winding down to finish.
func hardProbeCap(opt Options) time.Duration {
	return opt.RoundTimeout + 30*time.Second
}

// applyProbe folds one probe outcome into the node. A node that answered at
// all but slower than the delay limit is failed the same way a dead exit is:
// reachable-but-slow is not worth connecting through, and the next rounds
// must not carry it into the published groups.
func applyProbe(n *node, r probeResult, maxDelayMs int64) {
	if r.err != nil {
		markFailed(n, r.err.Error())
		return
	}
	if maxDelayMs > 0 && int64(r.delay) > maxDelayMs {
		markFailed(n, fmt.Sprintf("delay %dms above %dms limit", r.delay, maxDelayMs))
		return
	}
	n.passes++
	n.delays = append(n.delays, int(r.delay))
}

// effectiveMaxDelayMs guards against a caller that skipped withDefaults: a
// zero must not turn into "every node fails", it means the default limit.
func effectiveMaxDelayMs(opt Options) int64 {
	if opt.MaxDelayMs <= 0 {
		return defaultMaxDelayMs
	}
	return opt.MaxDelayMs
}

// markFailed records one failure of a node and takes it out of the running.
func markFailed(n *node, reason string) {
	n.passes = 0
	n.failed = true
	n.lastErr = reason
}

func probeOne(ctx context.Context, n *node, opt Options, expected utils.IntRanges[uint16]) (uint16, error) {
	ctx, cancel := context.WithTimeout(ctx, opt.RoundTimeout)
	defer cancel()

	return n.proxy.URLTest(ctx, opt.TestURL, expected)
}
