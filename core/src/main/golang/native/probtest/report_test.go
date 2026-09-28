package probtest

import (
	"encoding/json"
	"strings"
	"testing"
)

// Verifies the crash reported from the device: a node that never recorded a
// delay used to serialise as "delays":null, which kotlinx.serialization rejects
// for a non-null List<Int>, failing the whole envelope.
func TestNormalizeNeverEmitsNullDelays(t *testing.T) {
	r := Report{
		Rounds: 3,
		Total:  3,
		Nodes: []Node{
			{Name: "survivor", Passes: 3, Delays: []int{120, 130, 140}},
			{Name: "failed-first-round", Passes: 0, Error: "timeout"},
			{Name: "rejected", Rejected: true, Error: "bad flow"},
		},
	}
	r.normalize()

	buf, err := json.Marshal(r)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	if strings.Contains(string(buf), `"delays":null`) {
		t.Fatalf("still emits a null delays array: %s", buf)
	}
	if !strings.Contains(string(buf), `"delays":[]`) {
		t.Fatalf("expected an empty array for the failed node: %s", buf)
	}
}
