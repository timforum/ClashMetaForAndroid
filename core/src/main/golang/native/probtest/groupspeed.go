package probtest

import (
	"context"
	"fmt"
	"sort"
	"sync"
	"sync/atomic"
	"time"

	C "github.com/metacubex/mihomo/constant"
)

// GroupNode is one live group member handed to the run. Unlike the gate's
// parsed candidates, the proxy already exists in the running core: nothing
// has to be read from YAML, and the delay the UI shows travels with it.
type GroupNode struct {
	Name  string
	Delay int
	Proxy C.Proxy
}

// GroupProgress is one event of a live group run, so a caller can show which
// node is being measured and how many are left without polling the core.
type GroupProgress struct {
	Group     string `json:"group"`
	Stage     string `json:"stage"`
	Done      int    `json:"done"`
	Total     int    `json:"total"`
	Rounds    int    `json:"rounds"`
	Passed    int    `json:"passed"`
	Failed    int    `json:"failed"`
	Current   string `json:"current,omitempty"`
	ElapsedMs int64  `json:"elapsedMs,omitempty"`
}

// GroupOutcome is what one node measured in this run. A node that cleared the
// gate has an empty Error and a positive Mbps; anything else carries the
// reason it cannot be ranked.
type GroupOutcome struct {
	Name  string  `json:"name"`
	Delay int     `json:"delay"`
	Mbps  float64 `json:"mbps,omitempty"`
	Tier  string  `json:"tier,omitempty"`
	Error string  `json:"error,omitempty"`
}

// GroupSpeedTest measures every node of a live group with the real-transfer
// gate: the YouTube reachability probes, then a timed throughput download.
//
// Unlike speedGate nothing is dropped from the result. The caller is picking
// a node to connect through, and "which node do I switch to" is answered by
// the broken entries as much as the healthy ones: a node that fails here is
// exactly the node the current connection may be sitting on.
//
// With more than one round a node's score is the worst round it measured, not
// the best: a node that is fast once and slow once is not the stable node a
// reader asked for, and the worst round is what the connection will feel like
// every time it hits the slow one. A node must clear every round to rank.
func GroupSpeedTest(ctx context.Context, group string, nodes []GroupNode, sp SpeedOptions, rounds int, onProgress func(GroupProgress)) []GroupOutcome {
	sp = sp.withDefaults()
	if rounds < 1 {
		rounds = 1
	}

	started := time.Now()
	total := len(nodes)
	outcomes := make([]GroupOutcome, total)
	var done, passed, failed int64

	emit := func(current string) {
		if onProgress == nil {
			return
		}
		onProgress(GroupProgress{
			Group:     group,
			Stage:     StageSpeedTest,
			Done:      int(atomic.LoadInt64(&done)),
			Total:     total,
			Rounds:    rounds,
			Passed:    int(atomic.LoadInt64(&passed)),
			Failed:    int(atomic.LoadInt64(&failed)),
			Current:   current,
			ElapsedMs: time.Since(started).Milliseconds(),
		})
	}
	emit("")

	var wg sync.WaitGroup
	sem := make(chan struct{}, sp.Concurrency)

	for i := range nodes {
		if ctx.Err() != nil {
			outcomes[i] = GroupOutcome{
				Name:  nodes[i].Name,
				Delay: nodes[i].Delay,
				Error: ctx.Err().Error(),
			}
			atomic.AddInt64(&failed, 1)
			atomic.AddInt64(&done, 1)
			continue
		}

		wg.Add(1)
		sem <- struct{}{}

		go func(i int) {
			defer wg.Done()
			defer func() { <-sem }()

			n := nodes[i]
			out := GroupOutcome{Name: n.Name, Delay: n.Delay}

			// Same watchdog as the gate: a probe that hangs before any byte
			// can be judged must not hold a concurrency slot for the rest of
			// the run.
			type checkResult struct {
				mbps  float64
				stage string
				err   error
			}
			res := make(chan checkResult, 1)

			go func() {
				var (
					worst float64
					stage string
					err   error
				)

				for r := 0; r < rounds; r++ {
					mbps, s, e := speedCheck(ctx, n.Proxy, sp)

					// The worst round is the score, and any round's failure
					// fails the node: one broken round is a broken node.
					if r == 0 || mbps < worst {
						worst = mbps
					}
					stage, err = s, e
					if err != nil {
						break
					}
				}

				res <- checkResult{mbps: worst, stage: stage, err: err}
			}()

			timer := time.NewTimer((probeTimeoutPerNode + 30*time.Second) * time.Duration(rounds))
			defer timer.Stop()

			select {
			case r := <-res:
				if r.err != nil {
					if r.stage != "" {
						out.Error = fmt.Sprintf("%s: %s", r.stage, r.err.Error())
					} else {
						out.Error = r.err.Error()
					}
					atomic.AddInt64(&failed, 1)
				} else {
					out.Mbps = r.mbps
					out.Tier = SpeedTier(r.mbps)
					atomic.AddInt64(&passed, 1)
				}
			case <-ctx.Done():
				out.Error = ctx.Err().Error()
				atomic.AddInt64(&failed, 1)
			case <-timer.C:
				out.Error = "probe did not answer in time"
				atomic.AddInt64(&failed, 1)
			}

			outcomes[i] = out
			atomic.AddInt64(&done, 1)
			emit(n.Name)
		}(i)
	}

	wg.Wait()
	emit("")

	return outcomes
}

// RankGroupOutcomes picks the default and the standby from a finished run.
//
// Ranking is by measured throughput among the nodes that cleared the gate in
// every round: the fastest carries the connection, the next fastest is the
// node that takes over the moment the default stops answering. Ties fall back
// to the name so the pair does not shuffle between identical runs.
func RankGroupOutcomes(outcomes []GroupOutcome) (best, backup string) {
	passed := make([]GroupOutcome, 0, len(outcomes))

	for _, o := range outcomes {
		if o.Error == "" && o.Mbps > 0 {
			passed = append(passed, o)
		}
	}

	sort.SliceStable(passed, func(i, j int) bool {
		if passed[i].Mbps != passed[j].Mbps {
			return passed[i].Mbps > passed[j].Mbps
		}
		return passed[i].Name < passed[j].Name
	})

	if len(passed) == 0 {
		return "", ""
	}

	best = passed[0].Name

	// A group may list the same node twice through two providers; the
	// standby has to be a different entry than the default.
	for _, o := range passed[1:] {
		if o.Name != best {
			backup = o.Name
			break
		}
	}

	return best, backup
}
