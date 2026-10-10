package main

//#include "bridge.h"
import "C"

import (
	"context"
	"encoding/json"
	"fmt"
	"unsafe"

	"cfa/native/probtest"
	"cfa/native/tunnel"
)

// speedTestEnvelope is the single document speedTestGroup hands to the JVM,
// so the Kotlin side deserializes one shape whether the run produced a
// ranking or a reason why it could not.
type speedTestEnvelope struct {
	OK      bool                    `json:"ok"`
	Error   string                  `json:"error,omitempty"`
	Group   string                  `json:"group,omitempty"`
	Results []probtest.GroupOutcome `json:"results,omitempty"`
	// Best is the fastest node that cleared the gate, Backup the next one:
	// the pair a caller persists as the default connection node and the
	// standby that takes over when the default stops answering.
	Best   string `json:"best,omitempty"`
	Backup string `json:"backup,omitempty"`
}

// groupSpeedRequest is the wire form of a live group run. Zero values fall
// back to the gate's own defaults, so an empty object is a valid request.
type groupSpeedRequest struct {
	// Rounds repeats the measurement per node; the worst round is the
	// score. One round is what an interactive toggle uses to answer now,
	// the supervisor runs more to rank on something less lucky.
	Rounds        int     `json:"rounds,omitempty"`
	FloorMbps     float64 `json:"floorMbps,omitempty"`
	MaxBytes      int64   `json:"maxBytes,omitempty"`
	MaxTimeMs     int64   `json:"maxTimeMs,omitempty"`
	Concurrency   int     `json:"concurrency,omitempty"`
	ThroughputURL string  `json:"throughputUrl,omitempty"`
	// MaxDelayMs eliminates a node whose latency probe answered slower than
	// that even though the exit itself worked. Zero defers to the gate's
	// default, the same limit the candidate rounds use.
	MaxDelayMs int64 `json:"maxDelayMs,omitempty"`
	// TestURL overrides the endpoint the latency probe measures against.
	// Empty keeps each node's own, the URL its displayed delay was
	// measured against, so a fresh measurement refreshes that number.
	TestURL string `json:"testUrl,omitempty"`
	// Only narrows the run to one named node, which is what the periodic
	// watch of the node the connection is on asks: whether that one node
	// still answers and still carries, at the cost of measuring one node
	// instead of the whole group.
	Only string `json:"only,omitempty"`
	// SkipReachability drops the YouTube probes from the measurement,
	// leaving latency plus throughput.
	SkipReachability bool `json:"skipReachability,omitempty"`
}

func (r groupSpeedRequest) options() probtest.SpeedOptions {
	return probtest.SpeedOptions{
		Enabled:          true,
		FloorMbps:        r.FloorMbps,
		MaxBytes:         r.MaxBytes,
		MaxTimeMs:        r.MaxTimeMs,
		Concurrency:      r.Concurrency,
		ThroughputURL:    r.ThroughputURL,
		SkipReachability: r.SkipReachability,
	}
}

// speedTestGroup measures every leaf node of a live group through the real
// transfer gate and returns the ranking, reusing the core that is already
// running: the same proxies the connection uses are the ones measured, so a
// node that passes here is a node the reader just watched work.
//
// Progress arrives on the callback as one GroupProgress per node finished;
// the return value carries the whole result. The callback is a JNI global ref
// owned by this call and is released no matter how the function leaves.
//
//export speedTestGroup
func speedTestGroup(callback unsafe.Pointer, group, options C.c_string) (out *C.char) {
	defer func() {
		if r := recover(); r != nil {
			out = marshalJson(speedTestEnvelope{
				OK:    false,
				Error: fmt.Sprintf("speed test panic: %v", r),
			})
		}

		// jni_new_string() calls strlen() on the result, so it must never be
		// nil even if the recover path above itself failed.
		if out == nil {
			out = marshalJson(speedTestEnvelope{
				OK:    false,
				Error: "speed test produced no result",
			})
		}

		C.release_object(callback)
	}()

	name := C.GoString(group)
	if name == "" {
		return marshalJson(speedTestEnvelope{
			OK:    false,
			Error: "no group given",
		})
	}

	var req groupSpeedRequest
	if raw := C.GoString(options); raw != "" {
		if err := json.Unmarshal([]byte(raw), &req); err != nil {
			return marshalJson(speedTestEnvelope{
				OK:    false,
				Group: name,
				Error: fmt.Sprintf("invalid options: %v", err),
			})
		}
	}

	targets, err := tunnel.GroupSpeedTargets(name)
	if err != nil {
		return marshalJson(speedTestEnvelope{
			OK:    false,
			Group: name,
			Error: err.Error(),
		})
	}

	// A subscription's information entries are real proxies and would measure
	// like one, and this run does not merely report - it picks the node the
	// group connects through. Left in, "剩余流量：712.92 GB" can win a ranking
	// and quietly become the node everything routes through.
	targets = dropInformationNodes(targets)
	if len(targets) == 0 {
		return marshalJson(speedTestEnvelope{
			OK:    false,
			Group: name,
			Error: "group has no measurable nodes",
		})
	}

	// A watch pass names one node: measuring the whole group every two
	// minutes would spend the traffic of every node in the group on the
	// check of one of them.
	if req.Only != "" {
		kept := make([]tunnel.SpeedTarget, 0, len(targets))
		for _, t := range targets {
			if t.Name == req.Only {
				kept = append(kept, t)
			}
		}
		if len(kept) == 0 {
			return marshalJson(speedTestEnvelope{
				OK:    false,
				Group: name,
				Error: fmt.Sprintf("node `%s` is not a measurable member of `%s`", req.Only, name),
			})
		}
		targets = kept
	}

	nodes := make([]probtest.GroupNode, 0, len(targets))
	for _, t := range targets {
		testURL := req.TestURL
		if testURL == "" {
			testURL = t.TestURL
		}
		nodes = append(nodes, probtest.GroupNode{
			Name:    t.Name,
			Delay:   t.Delay,
			Owner:   t.Owner,
			Path:    t.Path,
			TestURL: testURL,
			Proxy:   t.Proxy,
		})
	}

	results := probtest.GroupSpeedTest(
		context.Background(),
		name,
		nodes,
		req.options(),
		req.Rounds,
		req.MaxDelayMs,
		func(p probtest.GroupProgress) {
			C.fetch_report(callback, marshalJson(p))
		},
	)

	best, backup := probtest.RankGroupOutcomes(results)

	return marshalJson(speedTestEnvelope{
		OK:      true,
		Group:   name,
		Results: results,
		Best:    best,
		Backup:  backup,
	})
}

// dropInformationNodes removes the subscription's own account entries from a
// group's measurable members. The order is kept, because a ranking that
// reports on a subset has to report on the same subset the reader sees.
func dropInformationNodes(targets []tunnel.SpeedTarget) []tunnel.SpeedTarget {
	kept := targets[:0]
	for _, t := range targets {
		if probtest.InformationNode(t.Name) != "" {
			continue
		}
		kept = append(kept, t)
	}
	return kept
}

// monitorEnvelope is the single document monitorGroup hands to the JVM: the
// per-node share of the traffic the core is carrying right now, plus the
// core's own running totals so a reader can tell "nothing flowed anywhere"
// from "traffic flowed but not through this node".
type monitorEnvelope struct {
	OK        bool                 `json:"ok"`
	Error     string               `json:"error,omitempty"`
	Group     string               `json:"group,omitempty"`
	// InUse is the leaf the connection is on behind Group, reached by following
	// the selection down: for a group of groups the name the group itself reports
	// is a sub group, which is not a node the gate could measure.
	InUse     string               `json:"inUse,omitempty"`
	Nodes     []tunnel.NodeTraffic `json:"nodes,omitempty"`
	LiveBytes int64                `json:"liveBytes,omitempty"`
	TotalUp   int64                `json:"totalUp,omitempty"`
	TotalDown int64                `json:"totalDown,omitempty"`
}

// monitorGroup snapshots the traffic the core is carrying through the leaf
// nodes of one live group.
//
// This is the free half of the periodic watch of the node the connection is
// on: the core already counts the bytes every open connection carries and
// already names the chain of nodes behind it, so answering "is the node in
// use actually moving anything" costs no extra request. The per-node numbers
// are cumulative since the monitor first looked at that node, so subtracting
// a previous snapshot gives the bytes that moved over the window between the
// two reads.
//
//export monitorGroup
func monitorGroup(group C.c_string) (out *C.char) {
	defer func() {
		if r := recover(); r != nil {
			out = marshalJson(monitorEnvelope{
				OK:    false,
				Error: fmt.Sprintf("monitor panic: %v", r),
			})
		}

		// jni_new_string() calls strlen() on the result, so it must never be
		// nil even if the recover path above itself failed.
		if out == nil {
			out = marshalJson(monitorEnvelope{
				OK:    false,
				Error: "monitor produced no result",
			})
		}
	}()

	name := C.GoString(group)
	if name == "" {
		return marshalJson(monitorEnvelope{
			OK:    false,
			Error: "no group given",
		})
	}

	nodes, liveBytes, err := tunnel.MonitorTraffic(name)
	if err != nil {
		return marshalJson(monitorEnvelope{
			OK:    false,
			Group: name,
			Error: err.Error(),
		})
	}

	up, down := tunnel.Total()

	return marshalJson(monitorEnvelope{
		OK:        true,
		Group:     name,
		InUse:     tunnel.InUseNode(name),
		Nodes:     nodes,
		LiveBytes: liveBytes,
		TotalUp:   up,
		TotalDown: down,
	})
}
