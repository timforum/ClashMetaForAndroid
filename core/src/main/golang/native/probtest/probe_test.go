package probtest

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/metacubex/mihomo/common/utils"
	C "github.com/metacubex/mihomo/constant"
)

// fakeProxy answers URLTest through a scripted per-call response, so the probe
// path can be exercised without a network. Embedding the interface leaves the
// rest of it nil: probeOnce only ever calls URLTest.
type fakeProxy struct {
	C.Proxy
	calls   int
	respond func(call int) (uint16, error)
}

func (f *fakeProxy) URLTest(context.Context, string, utils.IntRanges[uint16]) (uint16, error) {
	f.calls++
	return f.respond(f.calls)
}

// runProbeOnce probes one node through the scripted proxy and reports what the
// probe did to it, plus how many attempts the script saw.
func runProbeOnce(t *testing.T, respond func(call int) (uint16, error)) (*node, int) {
	t.Helper()

	p := &fakeProxy{respond: respond}
	n := &node{name: "n", typ: "vmess", proxy: p}
	opt := Options{Concurrency: 1, RoundTimeout: 3 * time.Second, MaxDelayMs: 500}.withDefaults()

	if err := probeOnce(context.Background(), []*node{n}, opt, utils.IntRanges[uint16]{}); err != nil {
		t.Fatalf("probeOnce: %v", err)
	}

	return n, p.calls
}

func TestProbeOnceRetriesASilentFailure(t *testing.T) {
	n, calls := runProbeOnce(t, func(call int) (uint16, error) {
		if call == 1 {
			return 0, errors.New("dial tcp 51.79.64.198:8119: i/o timeout")
		}
		return 300, nil
	})

	if calls != 2 {
		t.Fatalf("URLTest ran %d time(s), want the single retry (2)", calls)
	}
	if n.failed {
		t.Fatalf("node failed after its retry answered: %s", n.lastErr)
	}
	if n.passes != 1 || len(n.delays) != 1 || n.delays[0] != 300 {
		t.Fatalf("passes=%d delays=%v, want one pass at 300ms", n.passes, n.delays)
	}
}

func TestProbeOnceDoesNotRetryADefinitiveAnswer(t *testing.T) {
	n, calls := runProbeOnce(t, func(int) (uint16, error) {
		return 0, errors.New("HTTP need auth")
	})

	if calls != 1 {
		t.Fatalf("URLTest ran %d time(s), want 1: the exit answered, it did not go silent", calls)
	}
	if !n.failed || n.lastErr != "HTTP need auth" {
		t.Fatalf("failed=%v lastErr=%q, want the definitive answer recorded once", n.failed, n.lastErr)
	}
}

func TestProbeOnceRetryKeepsTheDelayLimit(t *testing.T) {
	n, calls := runProbeOnce(t, func(call int) (uint16, error) {
		if call == 1 {
			return 0, errors.New("dial tcp 51.79.64.198:8119: i/o timeout")
		}
		return 900, nil
	})

	if calls != 2 {
		t.Fatalf("URLTest ran %d time(s), want the single retry (2)", calls)
	}
	if !n.failed {
		t.Fatal("a 900ms retry must not survive the 500ms limit")
	}
	if !strings.Contains(n.lastErr, "900ms above 500ms limit") {
		t.Fatalf("lastErr=%q, want the delay limit to be the verdict", n.lastErr)
	}
}

// An answer over the limit is not the node's verdict yet: the limit sits on the
// noise floor, so the round asks once more and keeps the fastest sample it got.
func TestProbeOnceResamplesAnOverLimitSample(t *testing.T) {
	n, calls := runProbeOnce(t, func(call int) (uint16, error) {
		if call == 1 {
			return 900, nil
		}
		return 300, nil
	})

	if calls != 2 {
		t.Fatalf("URLTest ran %d time(s), want the re-sample (2)", calls)
	}
	if n.failed {
		t.Fatal("a node whose fastest sample clears the limit must survive")
	}
	if n.passes != 1 || len(n.delays) != 1 || n.delays[0] != 300 {
		t.Fatalf("passes=%d delays=%v, want one pass at the fastest sample (300ms)", n.passes, n.delays)
	}
}

// The re-sample only replaces the first answer when it is faster: a round
// reports the sample it got, never the one it happened to get last.
func TestProbeOnceResampleKeepsTheFastestSample(t *testing.T) {
	n, calls := runProbeOnce(t, func(call int) (uint16, error) {
		if call == 1 {
			return 900, nil
		}
		return 1200, nil
	})

	if calls != 2 {
		t.Fatalf("URLTest ran %d time(s), want the re-sample (2)", calls)
	}
	if !n.failed {
		t.Fatal("a node whose fastest sample misses the limit must not survive")
	}
	if !strings.Contains(n.lastErr, "900ms above 500ms limit") {
		t.Fatalf("lastErr=%q, want the fastest sample to be the verdict", n.lastErr)
	}
}

// A sample that clears the limit is already a pass, and re-asking for a second
// opinion of a live node would spend a probe slot to say the same thing.
func TestProbeOnceDoesNotResampleAPassingSample(t *testing.T) {
	n, calls := runProbeOnce(t, func(int) (uint16, error) {
		return 300, nil
	})

	if calls != 1 {
		t.Fatalf("URLTest ran %d time(s), want 1: a passing sample needs no re-sample", calls)
	}
	if n.failed || n.passes != 1 || len(n.delays) != 1 || n.delays[0] != 300 {
		t.Fatalf("failed=%v passes=%d delays=%v, want one pass at 300ms", n.failed, n.passes, n.delays)
	}
}

// The retry's deadline is the delay limit plus slack, never longer than the
// round's own probe timeout: waiting past the limit can only confirm a verdict
// the limit already made.
func TestProbeRetryDeadlineStaysUnderTheRoundTimeout(t *testing.T) {
	opt := Options{RoundTimeout: 10 * time.Second, MaxDelayMs: 500}.withDefaults()
	if d := probeRetryDeadline(opt, effectiveMaxDelayMs(opt)); d != time.Second {
		t.Fatalf("retry deadline %v, want the delay limit plus slack (1s)", d)
	}

	slow := Options{RoundTimeout: 10 * time.Second, MaxDelayMs: 60000}.withDefaults()
	if d := probeRetryDeadline(slow, effectiveMaxDelayMs(slow)); d != slow.RoundTimeout {
		t.Fatalf("a limit longer than the round must fall back to %v, got %v", slow.RoundTimeout, d)
	}
}

func TestRetryableProbeFailure(t *testing.T) {
	cases := []struct {
		err  error
		want bool
	}{
		{nil, false},
		{errors.New("dial tcp 51.79.64.198:8119: i/o timeout"), true},
		{errors.New("context deadline exceeded"), true},
		{errors.New("read tcp 192.168.2.77:1->1.2.3.4:443: connection reset by peer"), true},
		{errors.New("dial tcp: lookup x.invalid: no such host"), true},
		{errors.New("timeout: no recent network activity"), true},
		{errors.New("probe did not answer in time"), true},
		{errors.New("HTTP need auth"), false},
		{errors.New(`Head "http://www.gstatic.com/generate_204": unexpected status: 403 Forbidden`), false},
		{errors.New("unexpected status: 400 Bad Request"), false},
		{errors.New("xhttp packet-up download bad status: 502 Bad Gateway"), false},
	}

	for _, c := range cases {
		if got := retryableProbeFailure(c.err); got != c.want {
			t.Errorf("retryableProbeFailure(%v) = %v, want %v", c.err, got, c.want)
		}
	}
}
