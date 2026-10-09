package tunnel

import (
	"sort"
	"sync"
	"time"

	"github.com/metacubex/mihomo/tunnel/statistic"
)

func ResetStatistic() {
	statistic.DefaultManager.ResetStatistic()
}

func Now() (up int64, down int64) {
	return statistic.DefaultManager.Now()
}

func Total() (up int64, down int64) {
	return statistic.DefaultManager.Total()
}

// NodeTraffic is one node's share of the traffic the core is carrying.
//
// Bytes is cumulative since the monitor first looked at the node, so a reader
// subtracts its previous snapshot to get a rate over the window between the
// two reads; Live counts only the connections still open right now, Conns
// how many of those there are, and OldestMs how long the longest-running one
// has been going. A node with Bytes growing but Conns zero carried traffic
// through connections that ended between the two reads: the traffic is real,
// it just is not sitting on the wire at this instant.
type NodeTraffic struct {
	Name     string `json:"name"`
	Bytes    int64  `json:"bytes"`
	Live     int64  `json:"live"`
	Conns    int    `json:"conns"`
	OldestMs int64  `json:"oldestMs"`
}

// monitorMarks remembers how many bytes had passed through each connection the
// monitor saw last time, and monitorSum accumulates bytes per node across
// calls.
//
// The core forgets a connection the moment it closes (Tracker.Close leaves the
// manager), so reading only the live connections would hide the bytes a node
// carried through connections that ended between two snapshots, and a node
// serving many short connections would look idle while it was working. The
// monitor keeps a per-connection watermark and folds every increment into the
// node's running total while the connection is still there, which is what
// makes the number a reader reads monotonic.
var (
	monitorMarks map[string]int64
	monitorSum   map[string]int64
	monitorMu    sync.Mutex
)

// MonitorTraffic attributes the connections the core is carrying to the leaf
// nodes of one live group.
//
// A connection's chain names the adapters it goes through, leaf first and
// group last, so the first name that belongs to this group is the node
// carrying it. The attribution is free: the core already counts the bytes of
// every open connection and already names the chain behind it, so answering
// "is the node in use actually moving anything" costs no extra request.
//
// Returns one entry per member of the group sorted by name — a member
// carrying nothing still reports its cumulative count, which is the number a
// reader subtracts a previous snapshot from — and the bytes sitting on open
// connections of the whole core, so a reader can tell "nothing flowed
// anywhere" from "traffic flowed but not through this node".
func MonitorTraffic(group string) ([]NodeTraffic, int64, error) {
	targets, err := GroupSpeedTargets(group)
	if err != nil {
		return nil, 0, err
	}

	want := make(map[string]bool, len(targets))
	for _, t := range targets {
		want[t.Name] = true
	}

	monitorMu.Lock()
	defer monitorMu.Unlock()

	if monitorMarks == nil {
		monitorMarks = make(map[string]int64)
	}
	if monitorSum == nil {
		monitorSum = make(map[string]int64)
	}

	marks := make(map[string]int64)
	live := make(map[string]int64)
	conns := make(map[string]int)
	oldest := make(map[string]int64)
	var liveBytes int64

	statistic.DefaultManager.Range(func(c statistic.Tracker) bool {
		info := c.Info()
		up := info.UploadTotal.Load()
		down := info.DownloadTotal.Load()
		bytes := up + down
		liveBytes += bytes

		node := ""
		for _, name := range info.Chain {
			if want[name] {
				node = name
				break
			}
		}
		if node == "" {
			return true
		}

		id := c.ID()

		// A connection the monitor has not seen before reads as zero, so its
		// whole count folds in the first time; one it has seen contributes
		// only what arrived since the last snapshot.
		delta := bytes - monitorMarks[id]
		if delta > 0 {
			monitorSum[node] += delta
		}
		marks[id] = bytes

		live[node] += bytes
		conns[node] += 1
		age := time.Since(info.Start).Milliseconds()
		if age > oldest[node] {
			oldest[node] = age
		}
		return true
	})

	// Connections that closed since the last snapshot are simply absent from
	// the new marks: their bytes were already folded into the running totals
	// while they were still open.
	monitorMarks = marks

	result := make([]NodeTraffic, 0, len(targets))
	for _, t := range targets {
		result = append(result, NodeTraffic{
			Name:     t.Name,
			Bytes:    monitorSum[t.Name],
			Live:     live[t.Name],
			Conns:    conns[t.Name],
			OldestMs: oldest[t.Name],
		})
	}

	sort.Slice(result, func(i, j int) bool {
		return result[i].Name < result[j].Name
	})

	return result, liveBytes, nil
}
