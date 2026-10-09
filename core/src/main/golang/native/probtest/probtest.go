package probtest

import (
	"context"
	"fmt"
	"sort"
	"sync"
	"time"

	"github.com/metacubex/mihomo/common/utils"
)

// Options controls a candidate subscription probe.
type Options struct {
	// TestURL is the URL every node must reach. Empty uses defaultTestURL.
	TestURL string
	// Rounds is how many spaced rounds a node must pass to survive. Default 3.
	Rounds int
	// RoundGap is the pause between two rounds. Default 20s.
	RoundGap time.Duration
	// RoundTimeout bounds a single probe of a single node. Default 10s.
	RoundTimeout time.Duration
	// Concurrency caps parallel probes within one round. Default 16.
	Concurrency int
	// ExpectStatus, when non-empty, restricts the accepted HTTP status
	// (e.g. "204"). Empty accepts any status.
	ExpectStatus string
	// MaxDelayMs eliminates a node whose probe answered slower than this even
	// when the exit itself worked. Zero defers to defaultMaxDelayMs.
	MaxDelayMs int64
	// Template, when set, decides which groups the surviving nodes fill and
	// where each rule set sends its traffic. A round given none publishes the
	// fixed built in layout instead, which is what a caller that has no
	// template to offer still gets.
	Template *Template
	// SpeedTest, when enabled, requires every survivor of the latency rounds
	// to also carry real traffic: reach YouTube through the exit and sustain
	// a floor of throughput. Nodes that fail either are not published.
	SpeedTest SpeedOptions
}

const (
	// defaultTestURL has to be reachable from the exit of the node under test.
	// A Google endpoint times out from large parts of the world, which used to
	// make perfectly good nodes fail every round and get dropped.
	defaultTestURL      = "http://cp.cloudflare.com/generate_204"
	defaultRounds       = 3
	defaultRoundGap     = 20 * time.Second
	defaultRoundTimeout = 10 * time.Second
	defaultConcurrency  = 16
	// defaultMaxDelayMs answers the question of whether a reachable node is
	// fast enough to connect through: an exit that takes half a second per
	// request is technically alive but drags everything behind it.
	defaultMaxDelayMs = 500
)

// Node is the outcome of one candidate node.
type Node struct {
	Name   string `json:"name"`
	Type   string `json:"type"`
	Passes int    `json:"passes"`
	Delays []int  `json:"delays"`
	Error  string `json:"error,omitempty"`
	// SpeedMbps and SpeedTier are filled for nodes that cleared the speed
	// gate. An empty tier means the gate did not run or the node never got
	// that far.
	SpeedMbps float64 `json:"speedMbps,omitempty"`
	SpeedTier string  `json:"speedTier,omitempty"`
	// Rejected marks nodes that were never probed because they failed
	// pre-validation (a node that cannot be parsed safely).
	Rejected bool `json:"rejected,omitempty"`
}

// Report is the final outcome of a run.
type Report struct {
	Rounds     int    `json:"rounds"`
	RoundGapMs int64  `json:"roundGapMs"`
	TestURL    string `json:"testUrl"`
	Total      int    `json:"total"`
	Survivors  int    `json:"survivors"`
	Rejected   int    `json:"rejected"`
	ElapsedMs  int64  `json:"elapsedMs"`
	Nodes      []Node `json:"nodes"`
}

// normalize replaces nil slices with empty ones so encoding/json emits []
// rather than null. Only survivors ever get their Delays filled in, so without
// this every rejected or failed node serialises as "delays":null, which the
// Kotlin side cannot deserialize because it declares a non-null List<Int>.
// A single null is enough to fail the whole report.
func (r *Report) normalize() {
	for i := range r.Nodes {
		if r.Nodes[i].Delays == nil {
			r.Nodes[i].Delays = []int{}
		}
	}
}

// Progress is emitted once per round while a run is in flight.
type Progress struct {
	// Stage names the phase a run is in: empty for the latency rounds,
	// StageSpeedTest for the throughput gate. Empty keeps the wire format
	// unchanged for a caller compiled before the gate existed.
	Stage     string `json:"stage,omitempty"`
	Round     int    `json:"round"`
	Rounds    int    `json:"rounds"`
	ElapsedMs int64  `json:"elapsedMs"`
	// Passed counts nodes that have not failed yet and will be probed again.
	Passed   int `json:"passed"`
	Failed   int `json:"failed"`
	Total    int `json:"total"`
	Rejected int `json:"rejected"`
	// Done counts nodes finished within the current stage. The latency
	// rounds report at round boundaries, where done equals round, so it stays
	// empty there.
	Done int `json:"done,omitempty"`
}

// Result is the outcome of Run.
type Result struct {
	Report Report
	YAML   []byte
	// ProxiesYAML holds the same surviving nodes as YAML does, but as a
	// document containing only "proxies". Consumers that merge the nodes into
	// their own service need that narrower shape.
	ProxiesYAML []byte
}

func (o Options) withDefaults() Options {
	if o.TestURL == "" {
		o.TestURL = defaultTestURL
	}
	if o.Rounds <= 0 {
		o.Rounds = defaultRounds
	}
	if o.RoundGap < 0 {
		o.RoundGap = 0
	} else if o.RoundGap == 0 {
		o.RoundGap = defaultRoundGap
	}
	if o.RoundTimeout <= 0 {
		o.RoundTimeout = defaultRoundTimeout
	}
	if o.Concurrency <= 0 {
		o.Concurrency = defaultConcurrency
	}
	if o.MaxDelayMs <= 0 {
		o.MaxDelayMs = defaultMaxDelayMs
	}
	return o
}

// probeMutex serialises runs so two concurrent probes cannot interleave.
var probeMutex sync.Mutex

// Run parses rawYaml, probes every candidate node in spaced rounds and returns
// a rewritten Clash configuration containing only the nodes that passed every
// round, together with a report of the run.
//
// Run never touches the live tunnel configuration: proxies are constructed
// directly through adapter.ParseProxy and hub/executor is never called.
func Run(ctx context.Context, rawYaml []byte, opt Options, onProgress func(Progress)) (*Result, error) {
	opt = opt.withDefaults()

	probeMutex.Lock()
	defer probeMutex.Unlock()

	started := time.Now()

	root, err := decodeRoot(rawYaml)
	if err != nil {
		return nil, err
	}

	entries, err := collectEntries(root)
	if err != nil {
		return nil, err
	}

	expected, err := utils.NewUnsignedRanges[uint16](opt.ExpectStatus)
	if err != nil {
		return nil, fmt.Errorf("invalid expect-status: %w", err)
	}

	report := Report{
		Rounds:     opt.Rounds,
		RoundGapMs: opt.RoundGap.Milliseconds(),
		TestURL:    opt.TestURL,
		Total:      len(entries),
	}

	nodes := make([]*node, 0, len(entries))
	seen := make(map[string]struct{}, len(entries))

	for i, entry := range entries {
		name := nodeName(entry, i)
		n, reason := buildNode(i, entry)
		if reason != "" {
			report.Rejected++
			report.Nodes = append(report.Nodes, Node{
				Name: name, Type: stringType(entry), Rejected: true, Error: reason,
			})
			continue
		}
		if _, dup := seen[n.name]; dup {
			report.Rejected++
			report.Nodes = append(report.Nodes, Node{
				Name: n.name, Type: n.typ, Rejected: true, Error: "duplicate name",
			})
			continue
		}
		seen[n.name] = struct{}{}
		nodes = append(nodes, n)
		report.Nodes = append(report.Nodes, Node{Name: n.name, Type: n.typ})
	}

	sort.SliceStable(report.Nodes, func(i, j int) bool {
		return report.Nodes[i].Name < report.Nodes[j].Name
	})

	if len(nodes) == 0 {
		return nil, fmt.Errorf("no testable node (%d rejected of %d entries)", report.Rejected, report.Total)
	}

	pos := make(map[string]int, len(report.Nodes))
	for i := range report.Nodes {
		pos[report.Nodes[i].Name] = i
	}

	survivors, err := probeRounds(ctx, nodes, opt, expected, func(p Progress) {
		if onProgress == nil {
			return
		}
		p.Total = report.Total
		p.Rejected = report.Rejected
		onProgress(p)
	})
	if err != nil {
		return nil, err
	}

	// The gate runs after the latency rounds and before anything is built
	// from the survivors, so a node that cannot carry video never reaches a
	// published group. With the gate off this is the identity function.
	if opt.SpeedTest.Enabled {
		survivors = speedGate(ctx, survivors, opt, func(p Progress) {
			if onProgress == nil {
				return
			}
			p.Total = report.Total
			p.Rejected = report.Rejected
			onProgress(p)
		})
	}

	report.ElapsedMs = time.Since(started).Milliseconds()

	// Every node gets its outcome copied, not just the survivors: the
	// result file is the only record left once logcat rotates, and a dropped
	// node whose lastErr never reaches the report looks exactly like a node
	// nobody tested.
	kept := make(map[string]struct{}, len(survivors))
	for _, n := range survivors {
		kept[n.name] = struct{}{}
	}

	for _, n := range nodes {
		if i, ok := pos[n.name]; ok {
			report.Nodes[i].Passes = n.passes
			report.Nodes[i].Delays = n.delays
			report.Nodes[i].SpeedMbps = n.speedMbps
			report.Nodes[i].SpeedTier = n.speedTier
			if n.lastErr != "" {
				report.Nodes[i].Error = n.lastErr
			}
		}
	}
	report.Survivors = len(kept)
	report.normalize()

	if len(kept) == 0 {
		if opt.SpeedTest.Enabled {
			return &Result{Report: report}, fmt.Errorf("no node passed the speed gate")
		}
		return &Result{Report: report}, fmt.Errorf("no node passed %d/%d rounds", opt.Rounds, opt.Rounds)
	}

	out, err := rewrite(root, kept, opt.Template)
	if err != nil {
		return &Result{Report: report}, err
	}

	if err := verify(out, kept, requiredGroup(opt.Template)); err != nil {
		return &Result{Report: report}, fmt.Errorf("rewritten config failed verification: %w", err)
	}

	proxiesOnly, err := ProxiesDocument(out)
	if err != nil {
		return &Result{Report: report}, fmt.Errorf("rewritten config has no publishable proxies: %w", err)
	}

	return &Result{Report: report, YAML: out, ProxiesYAML: proxiesOnly}, nil
}
