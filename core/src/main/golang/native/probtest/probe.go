package probtest

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/metacubex/mihomo/adapter"
	"github.com/metacubex/mihomo/common/utils"
	C "github.com/metacubex/mihomo/constant"
)

type node struct {
	name    string
	typ     string
	proxy   C.Proxy
	passes  int
	delays  []int
	failed  bool
	lastErr string
	// speedMbps and speedTier are set by the speed gate for nodes that
	// cleared it.
	speedMbps float64
	speedTier string
}

// buildNode pre-validates a raw proxy entry and parses it. It returns a
// non-empty reason when the entry must not be parsed at all.
//
// Pre-validation is what keeps a hostile subscription from taking the process
// down: adapter.ParseProxy reaches constructors that call log.Fatalln (and
// log.Fatalln is os.Exit, which cannot be recovered from), so those inputs
// have to be filtered before any constructor runs.
func buildNode(idx int, raw any) (*node, string) {
	entry, ok := raw.(map[string]any)
	if !ok {
		return nil, fmt.Sprintf("entry is %T, expected a mapping", raw)
	}

	name, _ := entry["name"].(string)
	if name == "" {
		return nil, "missing name"
	}

	typ, ok := entry["type"].(string)
	if !ok || typ == "" {
		return nil, "missing type"
	}

	if reason := unsafeEntry(entry, typ); reason != "" {
		return nil, reason
	}

	proxy, err := parseSafe(entry)
	if err != nil {
		return nil, fmt.Sprintf("parse: %s", err.Error())
	}

	return &node{name: name, typ: typ, proxy: proxy}, ""
}

func unsafeEntry(entry map[string]any, typ string) string {
	switch typ {
	case "vless":
		// outbound.NewVless calls log.Fatalln (=> os.Exit(1)) for the
		// deprecated XTLS flows, so reject anything we do not positively
		// know is safe instead of gambling on a panic recover.
		flow, _ := entry["flow"].(string)
		if flow != "" && flow != "xtls-rprx-vision" {
			return fmt.Sprintf("unsafe vless flow %q (unsupported or fatal)", flow)
		}
	case "ssh":
		// outbound.NewSSH reads option.PrivateKey from disk at parse time;
		// a subscription must not be able to make us open arbitrary paths.
		if k := entry["private-key"]; k != nil {
			if s, _ := k.(string); s != "" {
				return "ssh private-key path refused"
			}
		}
	}
	return ""
}

func parseSafe(entry map[string]any) (proxy C.Proxy, err error) {
	defer func() {
		if r := recover(); r != nil {
			proxy = nil
			err = fmt.Errorf("%v", r)
		}
	}()

	proxy, err = adapter.ParseProxy(entry)

	return
}

// probeRounds runs spaced rounds over every node that is still in the
// running, eliminating a node as soon as it fails a single round.
func probeRounds(
	ctx context.Context,
	nodes []*node,
	opt Options,
	expected utils.IntRanges[uint16],
	onProgress func(Progress),
) ([]*node, error) {
	started := time.Now()

	emit := func(round int) {
		if onProgress == nil {
			return
		}
		running, failed := 0, 0
		for _, n := range nodes {
			if n.failed {
				failed++
			} else {
				running++
			}
		}
		onProgress(Progress{
			Round:     round,
			Rounds:    opt.Rounds,
			ElapsedMs: time.Since(started).Milliseconds(),
			Passed:    running,
			Failed:    failed,
		})
	}

	for round := 1; round <= opt.Rounds; round++ {
		if err := ctx.Err(); err != nil {
			return nil, err
		}

		eligible := make([]*node, 0, len(nodes))
		for _, n := range nodes {
			if !n.failed {
				eligible = append(eligible, n)
			}
		}

		if len(eligible) == 0 {
			break
		}

		emit(round)

		if err := probeOnce(ctx, eligible, opt, expected); err != nil {
			return nil, err
		}

		if round < opt.Rounds && opt.RoundGap > 0 {
			timer := time.NewTimer(opt.RoundGap)
			select {
			case <-ctx.Done():
				timer.Stop()
				return nil, ctx.Err()
			case <-timer.C:
			}
		}
	}

	emit(opt.Rounds)

	survivors := make([]*node, 0, len(nodes))
	for _, n := range nodes {
		if !n.failed && n.passes == opt.Rounds {
			survivors = append(survivors, n)
		}
	}

	return survivors, nil
}

// probeOnce probes every given node concurrently within the concurrency cap.
func probeOnce(ctx context.Context, eligible []*node, opt Options, expected utils.IntRanges[uint16]) error {
	var wg sync.WaitGroup
	sem := make(chan struct{}, opt.Concurrency)

	maxDelay := effectiveMaxDelayMs(opt)

	for _, n := range eligible {
		if ctx.Err() != nil {
			break
		}

		wg.Add(1)
		sem <- struct{}{}

		go func(n *node) {
			defer wg.Done()
			defer func() { <-sem }()

			// A dial that never answers must not hold a semaphore slot
			// forever: sixteen of them is all it takes to deadlock the whole
			// round, because no other node can start. URLTest is therefore
			// run in its own goroutine behind a hard deadline of our own,
			// which no transport can opt out of. The leaked dial behind a
			// timed-out probe belongs to the node, not the round, so the
			// node is failed and the round moves on.
			res := make(chan probeResult, 1)
			go func() {
				delay, err := probeOne(ctx, n, opt, expected)
				res <- probeResult{delay: delay, err: err}
			}()

			timer := time.NewTimer(hardProbeCap(opt))
			defer timer.Stop()

			select {
			case r := <-res:
				applyProbe(n, r, maxDelay)
			case <-ctx.Done():
				markFailed(n, ctx.Err().Error())
			case <-timer.C:
				markFailed(n, "probe did not answer in time")
			}
		}(n)
	}

	wg.Wait()

	return ctx.Err()
}

type probeResult struct {
	delay uint16
	err   error
}

// hardProbeCap bounds one node's turn no matter how deep the dial hangs.
// RoundTimeout ends the probe cooperatively; the cap ends it regardless,
// with enough slack for a legitimate probe that is winding down to finish.
func hardProbeCap(opt Options) time.Duration {
	return opt.RoundTimeout + 30*time.Second
}

// applyProbe folds one probe outcome into the node. A node that answered at
// all but slower than the delay limit is failed the same way a dead exit is:
// reachable-but-slow is not worth connecting through, and the next rounds
// must not carry it into the published groups.
func applyProbe(n *node, r probeResult, maxDelayMs int64) {
	if r.err != nil {
		markFailed(n, r.err.Error())
		return
	}
	if maxDelayMs > 0 && int64(r.delay) > maxDelayMs {
		markFailed(n, fmt.Sprintf("delay %dms above %dms limit", r.delay, maxDelayMs))
		return
	}
	n.passes++
	n.delays = append(n.delays, int(r.delay))
}

// effectiveMaxDelayMs guards against a caller that skipped withDefaults: a
// zero must not turn into "every node fails", it means the default limit.
func effectiveMaxDelayMs(opt Options) int64 {
	if opt.MaxDelayMs <= 0 {
		return defaultMaxDelayMs
	}
	return opt.MaxDelayMs
}

// markFailed records one failure of a node and takes it out of the running.
func markFailed(n *node, reason string) {
	n.passes = 0
	n.failed = true
	n.lastErr = reason
}

func probeOne(ctx context.Context, n *node, opt Options, expected utils.IntRanges[uint16]) (uint16, error) {
	ctx, cancel := context.WithTimeout(ctx, opt.RoundTimeout)
	defer cancel()

	return n.proxy.URLTest(ctx, opt.TestURL, expected)
}
