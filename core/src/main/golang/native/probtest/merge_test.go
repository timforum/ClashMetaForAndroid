package probtest

import (
	"context"
	"strings"
	"testing"
)

func nodeYaml(name, server string) string {
	return "  - name: " + name + "\n    type: socks5\n    server: " + server + "\n    port: 1080\n"
}

func TestMergeConcatenatesAndDedupes(t *testing.T) {
	a := "proxies:\n" + nodeYaml("HK-01", "1.1.1.1") + nodeYaml("US-01", "2.2.2.2")
	b := "proxies:\n" + nodeYaml("HK-01", "3.3.3.3") + nodeYaml("JP-01", "4.4.4.4")
	c := "proxies:\n" + nodeYaml("HK-01", "5.5.5.5")

	merged, skipped, err := Merge([][]byte{[]byte(a), []byte(b), []byte(c)})
	if err != nil {
		t.Fatalf("Merge: %v", err)
	}
	if len(skipped) != 0 {
		t.Errorf("skipped = %v, want none", skipped)
	}

	root, err := decodeRoot(merged)
	if err != nil {
		t.Fatalf("decode merged: %v", err)
	}

	entries, err := collectEntries(root)
	if err != nil {
		t.Fatalf("collectEntries: %v", err)
	}
	if len(entries) != 5 {
		t.Errorf("got %d nodes, want 5 (no node may be dropped)", len(entries))
	}

	names := map[string]string{}
	for _, e := range entries {
		m, ok := e.(map[string]any)
		if !ok {
			t.Fatalf("entry is %T", e)
		}
		name, _ := m["name"].(string)
		if prev, dup := names[name]; dup {
			t.Errorf("duplicate name %q (servers %s and %v)", name, prev, m["server"])
		}
		names[name] = m["server"].(string)
	}

	// The first candidate keeps the plain name; later ones get a stable suffix.
	if names["HK-01"] != "1.1.1.1" {
		t.Errorf("first candidate should keep HK-01, got %q", names["HK-01"])
	}
	if names["HK-01 (1)"] != "3.3.3.3" {
		t.Errorf("second candidate HK-01 = %q, want %q", names["HK-01 (1)"], "3.3.3.3")
	}
	if names["HK-01 (2)"] != "5.5.5.5" {
		t.Errorf("third candidate HK-01 = %q, want %q", names["HK-01 (2)"], "5.5.5.5")
	}
}

func TestMergeKeepsGroupsAndRulesFromTemplate(t *testing.T) {
	plain := "proxies:\n" + nodeYaml("A", "1.1.1.1")
	rich := "proxies:\n" + nodeYaml("B", "2.2.2.2") +
		"proxy-groups:\n  - name: PROXY\n    type: select\n    proxies: [A, B]\n" +
		"rules:\n  - MATCH,PROXY\n"

	merged, _, err := Merge([][]byte{[]byte(plain), []byte(rich)})
	if err != nil {
		t.Fatalf("Merge: %v", err)
	}

	root, err := decodeRoot(merged)
	if err != nil {
		t.Fatalf("decode merged: %v", err)
	}

	if groups, ok := root["proxy-groups"].([]any); !ok || len(groups) != 1 {
		t.Fatalf("template groups lost: %#v", root["proxy-groups"])
	}
	if rules, ok := root["rules"].([]any); !ok || len(rules) != 1 {
		t.Fatalf("template rules lost: %#v", root["rules"])
	}
}

func TestMergeReportsUnusableCandidatesWithoutFailing(t *testing.T) {
	good := "proxies:\n" + nodeYaml("A", "1.1.1.1")
	bad := "proxies: [ unclosed"
	providerOnly := "proxy-providers:\n  s:\n    type: http\n    url: https://x\n"

	merged, skipped, err := Merge([][]byte{[]byte(bad), []byte(good), []byte(providerOnly)})
	if err != nil {
		t.Fatalf("Merge must survive a broken candidate: %v", err)
	}
	if len(skipped) != 2 {
		t.Fatalf("skipped = %+v, want 2", skipped)
	}
	if skipped[0].Index != 0 || skipped[1].Index != 2 {
		t.Errorf("skip indexes = %+v, want 0 and 2", skipped)
	}
	if !strings.Contains(skipped[0].Reason, "") || skipped[0].Reason == "" {
		t.Errorf("skip 0 has no reason")
	}

	root, err := decodeRoot(merged)
	if err != nil {
		t.Fatalf("decode merged: %v", err)
	}
	entries, _ := collectEntries(root)
	if len(entries) != 1 {
		t.Errorf("got %d nodes, want 1", len(entries))
	}
}

func TestMergeFailsWhenNothingUsable(t *testing.T) {
	if _, _, err := Merge([][]byte{[]byte("proxies: [ unclosed")}); err == nil {
		t.Fatal("expected an error when every candidate is unusable")
	}
	if _, _, err := Merge(nil); err == nil {
		t.Fatal("expected an error for an empty candidate list")
	}
}

func TestMergedDocumentSurvivesRun(t *testing.T) {
	// The merged document must remain a valid input for Run, otherwise the
	// merge would only be exercised in isolation.
	a := "proxies:\n" + nodeYaml("HK-01", "127.0.0.1:1") // dead on purpose
	b := "proxies:\n" + nodeYaml("HK-01", "127.0.0.1:1") +
		"proxy-groups:\n  - name: PROXY\n    type: select\n    proxies: [HK-01]\n" +
		"rules:\n  - MATCH,PROXY\n"

	merged, _, err := Merge([][]byte{[]byte(a), []byte(b)})
	if err != nil {
		t.Fatalf("Merge: %v", err)
	}

	// Every node is unreachable, so Run must fail cleanly with a report rather
	// than crash or emit an unusable configuration.
	res, err := Run(context.Background(), merged, fastOptions("http://127.0.0.1:1"), nil)
	if err == nil {
		t.Fatalf("expected failure, got %+v", res)
	}
	if res != nil && res.Report.Total != 2 {
		t.Errorf("report total = %d, want 2", res.Report.Total)
	}
}
