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
	// TemplateINI is the subconverter style template that decides which groups
	// the surviving nodes fill and where each rule set goes. Empty keeps the
	// fixed built in layout, so a caller without a template still publishes.
	TemplateINI string `json:"templateIni,omitempty"`
	// RuleFiles holds the rule files the template's ruleset lines name, keyed
	// by the file name their URLs end in.
	RuleFiles map[string]string `json:"ruleFiles,omitempty"`
	// SpeedTest, when non-nil and enabled, gates the survivors of the latency
	// rounds on real YouTube reachability and throughput.
	SpeedTest *speedTestRequest `json:"speedTest,omitempty"`
	// SpeedTestConfig carries the raw JSON of the speed gate's tunables, read
	// from the config file on the device. When non-empty it wins over the
	// structured fields above, so a round is retuned by editing the file
	// rather than rebuilding the app.
	SpeedTestConfig string `json:"speedTestConfig,omitempty"`
}

// speedTestRequest is the wire form of probtest.SpeedOptions. Absent means
// the gate does not run.
type speedTestRequest struct {
	Enabled     bool    `json:"enabled"`
	FloorMbps   float64 `json:"floorMbps,omitempty"`
	MaxBytes    int64   `json:"maxBytes,omitempty"`
	MaxTimeMs   int64   `json:"maxTimeMs,omitempty"`
	Concurrency int     `json:"concurrency,omitempty"`
}

func (r *speedTestRequest) options() probtest.SpeedOptions {
	if r == nil {
		return probtest.SpeedOptions{}
	}
	return probtest.SpeedOptions{
		Enabled:     r.Enabled,
		FloorMbps:   r.FloorMbps,
		MaxBytes:    r.MaxBytes,
		MaxTimeMs:   r.MaxTimeMs,
		Concurrency: r.Concurrency,
	}
}

// effectiveSpeedOptions resolves which gate configuration a round gets: the
// config file's JSON when the device pushed one, otherwise the structured
// fields the pipeline sent. A malformed file fails the round instead of
// silently running with defaults.
func (r probTestRequest) effectiveSpeedOptions() (probtest.SpeedOptions, error) {
	if r.SpeedTestConfig != "" {
		return probtest.ParseSpeedOptions(r.SpeedTestConfig)
	}
	return r.SpeedTest.options(), nil
}

func (r probTestRequest) options() (probtest.Options, error) {
	st, err := r.effectiveSpeedOptions()
	if err != nil {
		// A malformed gate config must not quietly become the RTT-only
		// default: that would publish nodes the reader was never promised
		// were tested. Fail the round instead.
		return probtest.Options{}, err
	}
	return probtest.Options{
		TestURL:      r.TestURL,
		Rounds:       r.Rounds,
		RoundGap:     durationMs(r.RoundGapMs),
		RoundTimeout: durationMs(r.RoundTimeoutMs),
		Concurrency:  r.Concurrency,
		ExpectStatus: r.ExpectStatus,
		SpeedTest:    st,
	}, nil
}

// template parses the template the request carried, or reports nil when it
// carried none. A template is only ever parsed here, so a round never has to
// notice that one arrived malformed: it fails before probing starts.
func (r probTestRequest) template() (*probtest.Template, error) {
	if r.TemplateINI == "" {
		return nil, nil
	}

	files := make(map[string][]byte, len(r.RuleFiles))
	for name, content := range r.RuleFiles {
		files[name] = []byte(content)
	}

	return probtest.ParseTemplate([]byte(r.TemplateINI), files)
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

	tpl, tplErr := req.template()
	if tplErr != nil {
		return marshalJson(probTestEnvelope{
			OK:    false,
			Error: fmt.Sprintf("invalid template: %v", tplErr),
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

	opt, err := req.options()
	if err != nil {
		return marshalJson(probTestEnvelope{
			OK:    false,
			Error: fmt.Sprintf("invalid speed config: %v", err),
		})
	}
	opt.Template = tpl

	res, err := probtest.Run(
		context.Background(),
		merged,
		opt,
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
