package probtest

import (
	"bytes"
	"context"
	"encoding/binary"
	"io"
	"net"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/metacubex/mihomo/component/geodata"
	"github.com/metacubex/mihomo/tunnel"
	"gopkg.in/yaml.v3"
)

// ---- helpers ----

// startSocks5 serves a minimal SOCKS5 CONNECT endpoint so a node in the test
// fixture is genuinely reachable.
func startSocks5(t *testing.T) string {
	t.Helper()

	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	t.Cleanup(func() { l.Close() })

	go func() {
		for {
			c, err := l.Accept()
			if err != nil {
				return
			}
			go serveSocks(c)
		}
	}()

	return l.Addr().String()
}

func serveSocks(c net.Conn) {
	defer c.Close()

	hdr := make([]byte, 262)
	if _, err := io.ReadFull(c, hdr[:2]); err != nil {
		return
	}
	if _, err := io.ReadFull(c, hdr[:int(hdr[1])]); err != nil {
		return
	}
	if _, err := c.Write([]byte{0x05, 0x00}); err != nil {
		return
	}

	if _, err := io.ReadFull(c, hdr[:4]); err != nil {
		return
	}

	var host string
	switch hdr[3] {
	case 0x01:
		if _, err := io.ReadFull(c, hdr[:4]); err != nil {
			return
		}
		host = net.IP(hdr[:4]).String()
	case 0x03:
		if _, err := io.ReadFull(c, hdr[:1]); err != nil {
			return
		}
		n := int(hdr[0])
		if _, err := io.ReadFull(c, hdr[:n]); err != nil {
			return
		}
		host = string(hdr[:n])
	case 0x04:
		if _, err := io.ReadFull(c, hdr[:16]); err != nil {
			return
		}
		host = net.IP(hdr[:16]).String()
	default:
		return
	}

	if _, err := io.ReadFull(c, hdr[:2]); err != nil {
		return
	}
	port := binary.BigEndian.Uint16(hdr[:2])

	up, err := net.DialTimeout("tcp", net.JoinHostPort(host, strconv.Itoa(int(port))), 5*time.Second)
	if err != nil {
		c.Write([]byte{0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}
	defer up.Close()

	if _, err := c.Write([]byte{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0}); err != nil {
		return
	}
	go io.Copy(up, c)
	io.Copy(c, up)
}

// startOrigin answers 204 the way generate_204 does.
func startOrigin(t *testing.T) string {
	t.Helper()

	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}

	srv := &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	})}
	go srv.Serve(l)
	t.Cleanup(func() { srv.Close() })

	return "http://" + l.Addr().String() + "/generate_204"
}

func fastOptions(testURL string) Options {
	return Options{
		TestURL:      testURL,
		Rounds:       3,
		RoundGap:     120 * time.Millisecond,
		RoundTimeout: 5 * time.Second,
		Concurrency:  8,
	}
}

func fixture(backend, deadPort string) string {
	return `
mode: rule
mixed-port: 7899
external-controller: 127.0.0.1:9090
secret: hunter2
interface-name: en0
log-level: warning
proxies:
  - name: good-node
    type: socks5
    server: ` + hostOf(backend) + `
    port: ` + portOf(backend) + `
    udp: false
  - name: dead-node
    type: socks5
    server: 127.0.0.1
    port: ` + deadPort + `
    udp: false
  - name: bad-flow
    type: vless
    server: 127.0.0.1
    port: 443
    uuid: 00000000-0000-0000-0000-000000000000
    network: tcp
    flow: xtls-rprx-direct
proxy-groups:
  - name: PROXY
    type: select
    proxies: [good-node, dead-node, bad-flow, DIRECT]
  - name: ORPHAN
    type: select
    proxies: [dead-node]
  - name: NESTED
    type: select
    proxies: [PROXY, DIRECT]
rules:
  - DOMAIN-SUFFIX,google.com,ORPHAN
  - DOMAIN-SUFFIX,example.com,PROXY
  - GEOIP,US,ORPHAN
  - MATCH,PROXY
`
}

func hostOf(addr string) string {
	host, _, err := net.SplitHostPort(addr)
	if err != nil {
		return addr
	}
	return host
}

func portOf(addr string) string {
	_, port, err := net.SplitHostPort(addr)
	if err != nil {
		return "0"
	}
	return port
}

// ---- tests ----

func TestRunFiltersAndExportsCompleteConfig(t *testing.T) {
	backend := startSocks5(t)
	testURL := startOrigin(t)

	opt := fastOptions(testURL)

	res, err := Run(context.Background(), []byte(fixture(backend, deadPortFor(backend))), opt, nil)
	if err != nil {
		t.Fatalf("Run: %v", err)
	}

	if res.Report.Survivors != 1 {
		t.Errorf("survivors = %d, want 1 (%+v)", res.Report.Survivors, res.Report.Nodes)
	}
	if res.Report.Rejected != 1 {
		t.Errorf("rejected = %d, want 1 (bad-flow)", res.Report.Rejected)
	}
	if res.Report.Total != 3 {
		t.Errorf("total = %d, want 3", res.Report.Total)
	}
	if len(res.YAML) == 0 {
		t.Fatalf("no yaml produced")
	}

	for _, want := range []string{"good-node"} {
		if !contains([]byte(res.YAML), want) {
			t.Errorf("yaml missing %q:\n%s", want, res.YAML)
		}
	}
	for _, unwanted := range []string{"dead-node", "bad-flow", "hunter2", "en0", "127.0.0.1:9090"} {
		if contains([]byte(res.YAML), unwanted) {
			t.Errorf("yaml must not contain %q:\n%s", unwanted, res.YAML)
		}
	}

	// complete config: groups narrowed, dangling rule targets dropped, MATCH kept
	root, err := decodeRoot(res.YAML)
	if err != nil {
		t.Fatalf("re-decode: %v", err)
	}

	groups := mustGroups(t, root)
	names := map[string]bool{}
	for _, g := range groups {
		names[g["name"].(string)] = true
	}
	if names["ORPHAN"] {
		t.Errorf("ORPHAN group should have been dropped: %v", groups)
	}
	if !names["PROXY"] || !names["NESTED"] {
		t.Errorf("expected PROXY and NESTED to survive: %v", names)
	}

	rules := mustRules(t, root)
	for _, r := range rules {
		if _, _, ok := ruleTarget(r); ok {
			if target, _, _ := ruleTarget(r); target == "ORPHAN" {
				t.Errorf("rule %q references dropped group", r)
			}
		}
	}
	if !containsRule(rules, "MATCH,PROXY") {
		t.Errorf("missing MATCH rule: %v", rules)
	}

	// the published document must itself parse
	if err := verify(res.YAML, keepSet("good-node")); err != nil {
		t.Errorf("verify: %v", err)
	}
}

// The subscription service this feeds refuses anything whose top level is not
// exactly {proxies: [...]}, so the narrow document has to be that and nothing
// else, while still carrying the surviving nodes verbatim.
func TestProxiesDocumentHoldsOnlySurvivingProxies(t *testing.T) {
	backend := startSocks5(t)

	res, err := Run(
		context.Background(),
		[]byte(fixture(backend, deadPortFor(backend))),
		fastOptions(startOrigin(t)),
		nil,
	)
	if err != nil {
		t.Fatalf("Run: %v", err)
	}

	if len(res.ProxiesYAML) == 0 {
		t.Fatalf("no proxies document produced")
	}

	var document map[string]any
	if err := yaml.Unmarshal(res.ProxiesYAML, &document); err != nil {
		t.Fatalf("decode: %v", err)
	}

	if len(document) != 1 || document["proxies"] == nil {
		t.Fatalf("document must hold only proxies, got keys %v", keysOf(document))
	}

	entries, ok := document["proxies"].([]any)
	if !ok {
		t.Fatalf("proxies is %T, want a list", document["proxies"])
	}
	if len(entries) != 1 {
		t.Fatalf("proxies = %d entries, want 1", len(entries))
	}

	entry, ok := entries[0].(map[string]any)
	if !ok {
		t.Fatalf("entry is %T, want a mapping", entries[0])
	}
	for _, field := range []string{"name", "type", "server"} {
		if _, ok := entry[field].(string); !ok {
			t.Errorf("entry is missing a string %q: %v", field, entry)
		}
	}
	if entry["name"] != "good-node" {
		t.Errorf("name = %v, want good-node", entry["name"])
	}
	if entry["port"] == nil {
		t.Errorf("entry is missing port: %v", entry)
	}

	// Nothing that identifies the machine may travel with the nodes.
	for _, unwanted := range []string{"dead-node", "bad-flow", "hunter2", "127.0.0.1:9090", "proxy-groups", "rules"} {
		if contains(res.ProxiesYAML, unwanted) {
			t.Errorf("document must not contain %q:\n%s", unwanted, res.ProxiesYAML)
		}
	}
}

func TestProxiesDocumentRejectsConfigWithoutProxies(t *testing.T) {
	for name, document := range map[string]string{
		"empty":      "",
		"no proxies": "rules:\n  - MATCH,DIRECT\n",
		"not a list": "proxies:\n  name: a\n",
		"empty list": "proxies: []\n",
	} {
		if _, err := ProxiesDocument([]byte(document)); err == nil {
			t.Errorf("%s: expected an error", name)
		}
	}
}

func keysOf(m map[string]any) []string {
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	return keys
}

// mihomo moved most of the state that ParseRawConfig used to overwrite behind
// getters, so this only tracks what is still globally observable. The point is
// the same: a screening round must not disturb the running core.
func TestRunDoesNotMutateLiveGlobalState(t *testing.T) {
	backend := startSocks5(t)
	testURL := startOrigin(t)

	// Everything ParseRawConfig used to overwrite and that is still readable
	// back is watched here: a screening round must leave the running core
	// exactly as it found it. The rest (UA, keepalive interval, TLS
	// fingerprint, fake IP range, proxy/group lists) no longer exist as
	// package level state, so there is nothing left to compare.
	type snapshot struct {
		geoSiteUrl  string
		mmdbUrl     string
		asnUrl      string
		geodataMode bool
		asnEnable   bool
		loaderName  string
		siteMatcher string
		tunnelNodes int
	}

	capture := func() snapshot {
		return snapshot{
			geoSiteUrl:  geodata.GeoSiteUrl(),
			mmdbUrl:     geodata.MmdbUrl(),
			asnUrl:      geodata.ASNUrl(),
			geodataMode: geodata.GeodataMode(),
			asnEnable:   geodata.ASNEnable(),
			loaderName:  geodata.LoaderName(),
			siteMatcher: geodata.SiteMatcherName(),
			tunnelNodes: len(tunnel.Proxies()),
		}
	}
	before := capture()

	if _, err := Run(context.Background(), []byte(fixture(backend, deadPortFor(backend))), fastOptions(testURL), nil); err != nil {
		t.Fatalf("Run: %v", err)
	}

	after := capture()
	if before != after {
		t.Errorf("live state changed:\nbefore=%+v\nafter =%+v", before, after)
	}
}
func TestUnsafeVlessFlowIsRejectedNotFatal(t *testing.T) {
	backend := startSocks5(t)
	testURL := startOrigin(t)

	// A legacy XTLS flow makes outbound.NewVless call log.Fatalln, which is
	// os.Exit(1). If this test dies, pre-validation regressed.
	res, err := Run(context.Background(), []byte(`
proxies:
  - name: fatal
    type: vless
    server: 127.0.0.1
    port: 443
    uuid: 00000000-0000-0000-0000-000000000000
    network: tcp
    flow: xtls-rprx-direct
  - name: ok
    type: socks5
    server: `+hostOf(backend)+`
    port: `+portOf(backend)+`
`), fastOptions(testURL), nil)

	if err != nil {
		t.Fatalf("Run: %v", err)
	}
	if res.Report.Rejected != 1 {
		t.Fatalf("rejected = %d, want 1", res.Report.Rejected)
	}
	if res.Report.Survivors != 1 {
		t.Fatalf("survivors = %d, want 1", res.Report.Survivors)
	}
}

func TestRoundGapIsHonoured(t *testing.T) {
	backend := startSocks5(t)
	testURL := startOrigin(t)

	opt := fastOptions(testURL)
	opt.RoundGap = 300 * time.Millisecond

	start := time.Now()
	if _, err := Run(context.Background(), []byte(`
proxies:
  - name: ok
    type: socks5
    server: `+hostOf(backend)+`
    port: `+portOf(backend)+`
`), opt, nil); err != nil {
		t.Fatalf("Run: %v", err)
	}

	// three rounds with two gaps between them
	if elapsed := time.Since(start); elapsed < 600*time.Millisecond {
		t.Errorf("elapsed %v, want >= 600ms (two %v gaps)", elapsed, opt.RoundGap)
	}
}

func TestCancellation(t *testing.T) {
	backend := startSocks5(t)
	testURL := startOrigin(t)

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Millisecond)
	defer cancel()

	_, err := Run(ctx, []byte(`
proxies:
  - name: ok
    type: socks5
    server: `+hostOf(backend)+`
    port: `+portOf(backend)+`
`), fastOptions(testURL), nil)
	if err == nil {
		t.Fatal("expected cancellation error")
	}
}

func TestRejectsProviderOnlySubscription(t *testing.T) {
	if _, err := Run(context.Background(), []byte(`
proxy-providers:
  sub:
    type: http
    url: https://example.com
    path: ./p.yaml
`), fastOptions("http://127.0.0.1:1"), nil); err == nil {
		t.Fatal("expected error for provider-only document")
	}
}

// ---- helpers ----

// deadPortFor returns a port nothing can be listening on. Port 1 is reserved
// and only ever refuses connections, which is what we want: the probe fails
// fast instead of hanging for RoundTimeout.
func deadPortFor(_ string) string {
	return "1"
}

func contains(h []byte, n string) bool {
	return bytes.Contains(h, []byte(n))
}

func keepSet(names ...string) map[string]struct{} {
	out := make(map[string]struct{}, len(names))
	for _, n := range names {
		out[n] = struct{}{}
	}
	return out
}

func mustGroups(t *testing.T, root map[string]any) []map[string]any {
	t.Helper()
	list, ok := root["proxy-groups"].([]any)
	if !ok {
		t.Fatalf("proxy-groups missing or not a list: %T", root["proxy-groups"])
	}
	out := make([]map[string]any, 0, len(list))
	for _, e := range list {
		m, ok := e.(map[string]any)
		if !ok {
			continue
		}
		out = append(out, m)
	}
	return out
}

func mustRules(t *testing.T, root map[string]any) []string {
	t.Helper()
	list, ok := root["rules"].([]any)
	if !ok {
		t.Fatalf("rules missing or not a list: %T", root["rules"])
	}
	out := make([]string, 0, len(list))
	for _, e := range list {
		if s, ok := e.(string); ok {
			out = append(out, s)
		}
	}
	return out
}

func containsRule(rules []string, want string) bool {
	for _, r := range rules {
		if r == want {
			return true
		}
	}
	return false
}

// A node whose protocol this core does not implement must not abort the whole
// screening round: it is reported as rejected and the remaining nodes are still
// tested. AnyTLS used to be such a case before the mihomo submodule was
// updated, which is why this path is guarded by a test.
func TestUnknownProxyTypeIsRejectedNotFatal(t *testing.T) {
	backend := startSocks5(t)
	testURL := startOrigin(t)

	res, err := Run(context.Background(), []byte(`
proxies:
  - name: bogus-node
    type: notarealprotocol
    server: 127.0.0.1
    port: 8443
  - name: ok
    type: socks5
    server: `+hostOf(backend)+`
    port: `+portOf(backend)+`
`), fastOptions(testURL), nil)
	if err != nil {
		t.Fatalf("Run: %v", err)
	}

	// Only the genuinely unknown type is rejected.
	if res.Report.Rejected != 1 {
		t.Errorf("rejected = %d, want 1 (%+v)", res.Report.Rejected, res.Report.Nodes)
	}
	if res.Report.Survivors != 1 {
		t.Errorf("survivors = %d, want 1", res.Report.Survivors)
	}

	var reason string
	for _, n := range res.Report.Nodes {
		if n.Name == "bogus-node" {
			reason = n.Error
		}
	}
	if !strings.Contains(reason, "notarealprotocol") {
		t.Errorf("reason = %q, want the mihomo parse error", reason)
	}
	if strings.Contains(string(res.YAML), "bogus-node") {
		t.Errorf("published config must not contain the rejected node:\n%s", res.YAML)
	}
}
