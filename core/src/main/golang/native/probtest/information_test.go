package probtest

import (
	"context"
	"strings"
	"testing"
	"time"
)

func TestInformationNodeNames(t *testing.T) {
	// Taken from a real subscription, where these are appended as ordinary
	// proxies that answer like ordinary proxies.
	information := []string{
		"剩余流量：712.92 GB",
		"套餐到期：2026-11-24",
		"距离下次重置剩余：14 天",
		"到期时间：2026-11-24",
		"过期时间：2026-11-24 23:59",
		"总流量：1.5 TB",
		"已用流量：300GB",
		"有效期至 2027-01-01",
		"https://example.com/panel",
		"www.example.com",
		"2026-11-24",
		"2026/11/24",
		// Decoration in front of the word that identifies the entry.
		"🇭🇰 剩余流量：12 GB",
		"★ 套餐到期：2026-11-24",
	}

	for _, name := range information {
		if reason := InformationNode(name); reason == "" {
			t.Errorf("InformationNode(%q) = %q, want a reason", name, reason)
		}
	}

	// The other half of the rule: a real node is not lost because it happens
	// to mention traffic, a deadline or a date.
	nodes := []string{
		"香港 01",
		"🇺🇸US_407|897KB/s|R002-260618 01",
		"日本|cdn1|电信高速|2倍率",
		"2x专线-新加坡-1",
		"美国洛杉矶-1|联通优化",
		"香港 剩余流量优化",
		"TRAFFIC-PEAK-01",
		"到期后自动切换",
		"套餐升级",
		"NL_239|4.0MB/s|R002-260618 01",
	}

	for _, name := range nodes {
		if reason := InformationNode(name); reason != "" {
			t.Errorf("InformationNode(%q) = %q, want no reason", name, reason)
		}
	}

	// A name that is only decoration leaves nothing to match, and must not be
	// mistaken for an information entry.
	if reason := InformationNode("  ★  "); reason != "" {
		t.Errorf("InformationNode(decoration only) = %q, want no reason", reason)
	}
}

func TestInformationReasonNeedsAName(t *testing.T) {
	if reason := informationReason("not a mapping"); reason != "" {
		t.Errorf("informationReason(string) = %q, want no reason", reason)
	}
	if reason := informationReason(map[string]any{"type": "socks5"}); reason != "" {
		t.Errorf("informationReason(entry without a name) = %q, want no reason", reason)
	}
}

// An information node points at a server that answers, so nothing but this
// check keeps one out of the published configuration. The fixture gives them
// the same reachable backend as the node that must survive, which is exactly
// what an airport does.
func TestRunDropsInformationNodes(t *testing.T) {
	backend := startSocks5(t)
	testURL := startOrigin(t)

	doc := `
proxies:
  - name: good-node
    type: socks5
    server: ` + hostOf(backend) + `
    port: ` + portOf(backend) + `
  - name: "剩余流量：712.92 GB"
    type: socks5
    server: ` + hostOf(backend) + `
    port: ` + portOf(backend) + `
  - name: "套餐到期：2026-11-24"
    type: socks5
    server: ` + hostOf(backend) + `
    port: ` + portOf(backend) + `
  - name: "距离下次重置剩余：14 天"
    type: socks5
    server: ` + hostOf(backend) + `
    port: ` + portOf(backend) + `
`

	opt := fastOptions(testURL)

	res, err := Run(context.Background(), []byte(doc), opt, nil)
	if err != nil {
		t.Fatalf("Run: %v", err)
	}

	if res.Report.Survivors != 1 {
		t.Errorf("survivors = %d, want 1", res.Report.Survivors)
	}
	if res.Report.Rejected != 3 {
		t.Errorf("rejected = %d, want 3", res.Report.Rejected)
	}

	for _, unwanted := range []string{"剩余流量", "套餐到期", "重置剩余"} {
		if contains(res.YAML, unwanted) {
			t.Errorf("published configuration contains %q:\n%s", unwanted, res.YAML)
		}
		if contains(res.ProxiesYAML, unwanted) {
			t.Errorf("published proxies contain %q:\n%s", unwanted, res.ProxiesYAML)
		}
	}

	// Rejected, not silently dropped: the report is the only place a reader
	// learns that a subscription carried three of them.
	reported := 0
	for _, n := range res.Report.Nodes {
		if strings.Contains(n.Error, "subscription information") {
			reported++
			if !n.Rejected {
				t.Errorf("%q reported without being marked rejected", n.Name)
			}
		}
	}
	if reported != 3 {
		t.Errorf("reported %d information nodes, want 3", reported)
	}

	// The check must cost nothing in time: these never reach a probe, so the
	// round is the length of the surviving node's rather than four times it.
	if elapsed := res.Report.ElapsedMs; elapsed > int64(20*time.Second/time.Millisecond) {
		t.Errorf("round took %dms, information nodes appear to have been probed", elapsed)
	}
}
