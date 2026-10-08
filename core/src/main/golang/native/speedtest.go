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
}

func (r groupSpeedRequest) options() probtest.SpeedOptions {
	return probtest.SpeedOptions{
		Enabled:       true,
		FloorMbps:     r.FloorMbps,
		MaxBytes:      r.MaxBytes,
		MaxTimeMs:     r.MaxTimeMs,
		Concurrency:   r.Concurrency,
		ThroughputURL: r.ThroughputURL,
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
	if len(targets) == 0 {
		return marshalJson(speedTestEnvelope{
			OK:    false,
			Group: name,
			Error: "group has no measurable nodes",
		})
	}

	nodes := make([]probtest.GroupNode, 0, len(targets))
	for _, t := range targets {
		nodes = append(nodes, probtest.GroupNode{
			Name:  t.Name,
			Delay: t.Delay,
			Proxy: t.Proxy,
		})
	}

	results := probtest.GroupSpeedTest(
		context.Background(),
		name,
		nodes,
		req.options(),
		req.Rounds,
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
