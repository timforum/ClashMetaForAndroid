package main

//#include "bridge.h"
import "C"

import (
	"context"
	"encoding/json"
	"fmt"
	"time"
	"unsafe"

	"cfa/native/probtest"
)

// probTestEnvelope is the single document nativeProbTest hands to the JVM, so
// the Kotlin side only ever deserializes one shape whether the run succeeded
// or not. Report is present whenever probing actually started.
type probTestEnvelope struct {
	OK      bool             `json:"ok"`
	Error   string           `json:"error,omitempty"`
	YAML    string           `json:"yaml,omitempty"`
	Report  *probtest.Report `json:"report,omitempty"`
	Skipped []probtest.Skip  `json:"skipped,omitempty"`
	// ProxiesYaml is YAML narrowed down to just the surviving nodes, for
	// consumers that merge nodes into their own pool instead of serving the
	// whole rewritten configuration.
	ProxiesYaml string `json:"proxiesYaml,omitempty"`
}

// probTestRequest is the wire form of probtest.Options. Durations arrive in
// milliseconds because JSON has no duration type.
type probTestRequest struct {
	TestURL        string `json:"testUrl"`
	Rounds         int    `json:"rounds"`
	RoundGapMs     int64  `json:"roundGapMs"`
	RoundTimeoutMs int64  `json:"roundTimeoutMs"`
	Concurrency    int    `json:"concurrency"`
	ExpectStatus   string `json:"expectStatus"`
}

func (r probTestRequest) options() probtest.Options {
	return probtest.Options{
		TestURL:      r.TestURL,
		Rounds:       r.Rounds,
		RoundGap:     durationMs(r.RoundGapMs),
		RoundTimeout: durationMs(r.RoundTimeoutMs),
		Concurrency:  r.Concurrency,
		ExpectStatus: r.ExpectStatus,
	}
}

func durationMs(ms int64) time.Duration {
	if ms < 0 {
		return 0
	}
	return time.Duration(ms) * time.Millisecond
}

//export runProbTest
func runProbTest(callback unsafe.Pointer, candidates, options C.c_string) (out *C.char) {
	// The callback is a JNI global ref owned by this call: release it no
	// matter how we leave, otherwise the service leaks one ref per run.
	defer func() {
		if r := recover(); r != nil {
			out = marshalJson(probTestEnvelope{
				OK:    false,
				Error: fmt.Sprintf("probtest panic: %v", r),
			})
		}

		// jni_new_string() calls strlen() on the result, so it must never be
		// nil even if the recover path above itself failed.
		if out == nil {
			out = marshalJson(probTestEnvelope{
				OK:    false,
				Error: "probtest produced no result",
			})
		}

		C.release_object(callback)
	}()

	var rawCandidates []string
	if err := json.Unmarshal([]byte(C.GoString(candidates)), &rawCandidates); err != nil {
		return marshalJson(probTestEnvelope{
			OK:    false,
			Error: fmt.Sprintf("invalid candidates: %v", err),
		})
	}
	if len(rawCandidates) == 0 {
		return marshalJson(probTestEnvelope{
			OK:    false,
			Error: "no candidate subscriptions given",
		})
	}

	var req probTestRequest
	if err := json.Unmarshal([]byte(C.GoString(options)), &req); err != nil {
		return marshalJson(probTestEnvelope{
			OK:    false,
			Error: fmt.Sprintf("invalid options: %v", err),
		})
	}

	docs := make([][]byte, 0, len(rawCandidates))
	for _, c := range rawCandidates {
		docs = append(docs, []byte(c))
	}

	merged, skipped, err := probtest.Merge(docs)
	if err != nil {
		return marshalJson(probTestEnvelope{
			OK:      false,
			Error:   err.Error(),
			Skipped: skipped,
		})
	}

	res, err := probtest.Run(
		context.Background(),
		merged,
		req.options(),
		func(p probtest.Progress) {
			C.fetch_report(callback, marshalJson(p))
		},
	)

	env := probTestEnvelope{OK: err == nil, Skipped: skipped}

	if res != nil {
		env.Report = &res.Report
		if len(res.YAML) > 0 {
			env.YAML = string(res.YAML)
		}
		if len(res.ProxiesYAML) > 0 {
			env.ProxiesYaml = string(res.ProxiesYAML)
		}
	}
	if err != nil {
		env.Error = err.Error()
	}

	return marshalJson(env)
}
