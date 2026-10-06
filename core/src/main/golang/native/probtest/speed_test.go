package probtest

import (
	"errors"
	"io"
	"testing"
	"time"
)

func TestSpeedTier(t *testing.T) {
	cases := []struct {
		mbps float64
		want string
	}{
		{0.5, "240p"},
		{0.99, "240p"},
		{1.0, "360p"},
		{2.49, "360p"},
		{2.5, "720p"},
		{7.99, "720p"},
		{8.0, "1080p"},
		{100, "1080p"},
	}
	for _, c := range cases {
		if got := SpeedTier(c.mbps); got != c.want {
			t.Errorf("SpeedTier(%v) = %q, want %q", c.mbps, got, c.want)
		}
	}
}

func TestRemoteMetadata(t *testing.T) {
	addr, err := remoteMetadata("https://www.youtube.com/watch?v=x")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if addr.Host != "www.youtube.com" || addr.DstPort != 443 {
		t.Errorf("got %s:%d, want www.youtube.com:443", addr.Host, addr.DstPort)
	}

	addr, err = remoteMetadata("http://cp.cloudflare.com:8080/generate_204")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if addr.Host != "cp.cloudflare.com" || addr.DstPort != 8080 {
		t.Errorf("got %s:%d, want cp.cloudflare.com:8080", addr.Host, addr.DstPort)
	}

	if _, err := remoteMetadata("ftp://example.com/file"); err == nil {
		t.Error("expected an error for an unsupported scheme")
	}
}

// rateReader produces bytes at a fixed real-time rate, the way a network
// connection would, so the measurement loop's caps can be exercised honestly.
type rateReader struct {
	chunk    int
	interval time.Duration
	total    int
	done     int
}

func (r *rateReader) Read(p []byte) (int, error) {
	if r.done >= r.total {
		return 0, io.EOF
	}
	time.Sleep(r.interval)
	n := r.chunk
	if n > len(p) {
		n = len(p)
	}
	if r.done+n > r.total {
		n = r.total - r.done
	}
	for i := 0; i < n; i++ {
		p[i] = 0
	}
	r.done += n
	return n, nil
}

type failingReader struct{}

func (failingReader) Read([]byte) (int, error) {
	return 0, errors.New("connection reset")
}

func TestDrainMeasuredEarlyExit(t *testing.T) {
	// 32 KB every 10 ms averages ~26 Mbps, over the early-exit bar, so the
	// loop must stop the moment the sample clears 128 KB instead of running
	// to the byte cap. The buffer is 32 KB, so that is four reads.
	r := &rateReader{chunk: 32 << 10, interval: 10 * time.Millisecond, total: 8 << 20}
	sp := SpeedOptions{FloorMbps: 0.5, MaxBytes: 4 << 20, MaxTime: 30 * time.Second}

	got, _, err := drainMeasured(r, sp.withDefaults())
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if got != 128<<10 {
		t.Errorf("got %d bytes, want the early exit at the 128 KB sample", got)
	}
}

func TestDrainMeasuredByteCap(t *testing.T) {
	// ~0.64 Mbps: above the floor but below the early-exit bar, so neither
	// early exit nor the time cap can fire before the byte cap does.
	// 8 KB every 100 ms is 0.64 Mbps; 256 KB takes 32 reads ≈ 3.2 s.
	r := &rateReader{chunk: 8 << 10, interval: 100 * time.Millisecond, total: 8 << 20}
	sp := SpeedOptions{FloorMbps: 0.5, MaxBytes: 256 << 10, MaxTime: 30 * time.Second}

	got, _, err := drainMeasured(r, sp.withDefaults())
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if got != 256<<10 {
		t.Errorf("got %d bytes, want the %d byte cap", got, 256<<10)
	}
}

func TestDrainMeasuredTimeCap(t *testing.T) {
	// ~0.26 Mbps: below the floor and below the early-exit bar, so the time
	// cap is what ends the measurement, with fewer bytes than its own cap.
	// 4 KB every 125 ms is 0.26 Mbps; 3 s is 24 reads ≈ 96 KB.
	r := &rateReader{chunk: 4 << 10, interval: 125 * time.Millisecond, total: 8 << 20}
	sp := SpeedOptions{FloorMbps: 0.5, MaxBytes: 1 << 20, MaxTime: 3 * time.Second}

	got, elapsed, err := drainMeasured(r, sp.withDefaults())
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if got >= 1<<20 {
		t.Errorf("got %d bytes, want the time cap to end the transfer first", got)
	}
	if elapsed < 3*time.Second {
		t.Errorf("elapsed %v, want the full time cap", elapsed)
	}
	mbps := float64(got) * 8 / elapsed.Seconds() / 1e6
	if mbps >= 0.5 {
		t.Errorf("measured %.2f Mbps, want a rate the gate would reject", mbps)
	}
}

func TestDrainMeasuredNoData(t *testing.T) {
	// A failure before the first byte leaves no sample: an error, not a
	// zero-speed measurement.
	r := io.MultiReader(failingReader{}, &rateReader{chunk: 1 << 10, interval: time.Millisecond, total: 100})
	sp := SpeedOptions{FloorMbps: 0.5, MaxBytes: 1 << 20, MaxTime: 30 * time.Second}

	if _, _, err := drainMeasured(r, sp.withDefaults()); err == nil {
		t.Error("expected an error when no data arrives at all")
	}
}

func TestDrainMeasuredEOFIsASample(t *testing.T) {
	// The server ends the range early. What arrived is still a measurement,
	// provided enough of it arrived. The total stays under the early-exit
	// sample so the loop reaches EOF rather than exiting early.
	r := &rateReader{chunk: 32 << 10, interval: 5 * time.Millisecond, total: 120 << 10}
	sp := SpeedOptions{FloorMbps: 0.5, MaxBytes: 1 << 20, MaxTime: 30 * time.Second}

	got, _, err := drainMeasured(r, sp.withDefaults())
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if got != 120<<10 {
		t.Errorf("got %d bytes, want the reader's full 120 KB", got)
	}
}

func TestSpeedOptionsDefaults(t *testing.T) {
	sp := SpeedOptions{}.withDefaults()
	if sp.FloorMbps != defaultSpeedFloorMbps {
		t.Errorf("FloorMbps = %v, want %v", sp.FloorMbps, defaultSpeedFloorMbps)
	}
	if sp.MaxBytes != defaultSpeedMaxBytes {
		t.Errorf("MaxBytes = %v, want %v", sp.MaxBytes, defaultSpeedMaxBytes)
	}
	if sp.MaxTime != defaultSpeedMaxTime {
		t.Errorf("MaxTime = %v, want %v", sp.MaxTime, defaultSpeedMaxTime)
	}
	if sp.Concurrency != defaultSpeedConcurrency {
		t.Errorf("Concurrency = %v, want %v", sp.Concurrency, defaultSpeedConcurrency)
	}
}
