//go:build live

package probtest

import (
	"context"
	"os"
	"testing"
)

// TestLiveSpeedGate runs the gate against real nodes over the network so the
// exact failure of a probe can be read from t.Log instead of guessed from a
// rotated logcat line.
//
//	LIVE_YAML=/path/to/nodes.yaml LIVE_SPEED_JSON='{"enabled":true,...}' \
//	  go test -tags live ./native/probtest/ -run TestLiveSpeedGate -v
func TestLiveSpeedGate(t *testing.T) {
	yamlPath := os.Getenv("LIVE_YAML")
	if yamlPath == "" {
		t.Skip("LIVE_YAML not set")
	}

	data, err := os.ReadFile(yamlPath)
	if err != nil {
		t.Fatalf("read yaml: %v", err)
	}

	root, err := decodeRoot(data)
	if err != nil {
		t.Fatalf("decode: %v", err)
	}

	entries, err := collectEntries(root)
	if err != nil {
		t.Fatalf("entries: %v", err)
	}

	survivors := make([]*node, 0, len(entries))
	for i, entry := range entries {
		n, reason := buildNode(i, entry)
		if reason != "" {
			t.Logf("rejected %s: %s", nodeName(entry, i), reason)
			continue
		}
		survivors = append(survivors, n)
	}
	if len(survivors) == 0 {
		t.Fatal("no testable node")
	}

	opt := Options{}.withDefaults()
	opt.SpeedTest.Enabled = true
	if raw := os.Getenv("LIVE_SPEED_JSON"); raw != "" {
		sp, err := ParseSpeedOptions(raw)
		if err != nil {
			t.Fatalf("speed options: %v", err)
		}
		opt.SpeedTest = sp
	}

	kept := speedGate(context.Background(), survivors, opt, nil)

	keptNames := map[string]bool{}
	for _, n := range kept {
		keptNames[n.name] = true
	}
	for _, n := range survivors {
		t.Logf("gate kept=%v node=%q tier=%q mbps=%v err=%q",
			keptNames[n.name], n.name, n.speedTier, n.speedMbps, n.lastErr)
	}
}
