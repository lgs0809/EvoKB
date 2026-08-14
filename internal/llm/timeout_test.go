package llm

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

// SPEC-08 Task 11: the provider_timeout seam. Per-call deadline =
// min(remaining ctx deadline, callTimeout) — shorter caller deadlines win.

func slowServer(t *testing.T, sleep time.Duration) *httptest.Server {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		time.Sleep(sleep)
		w.Write([]byte(`{"choices":[{"message":{"content":"ok"}}],"usage":{"total_tokens":1}}`))
	}))
	t.Cleanup(srv.Close)
	return srv
}

func timeoutTestClient(t *testing.T, url string) *Client {
	t.Helper()
	c, err := NewClient("openai-compatible", "sk-test", url, 0)
	if err != nil {
		t.Fatalf("NewClient: %v", err)
	}
	// No retry delay: the timeout must surface without backoff waits.
	restore := SetBackoffDelayForTest(func(int) time.Duration { return time.Millisecond })
	t.Cleanup(restore)
	return c
}

func TestCallTimeoutAppliesWithoutCtxDeadline(t *testing.T) {
	srv := slowServer(t, 500*time.Millisecond)
	c := timeoutTestClient(t, srv.URL)
	c.SetCallTimeout(50 * time.Millisecond)

	start := time.Now()
	_, err := c.ChatCompletionCtx(context.Background(), []Message{{Role: "user", Content: "hi"}}, CallOpts{Model: "m"})
	if err == nil {
		t.Fatal("expected a timeout error")
	}
	if elapsed := time.Since(start); elapsed > 400*time.Millisecond {
		t.Errorf("call took %v — the 50ms call timeout did not bite", elapsed)
	}
}

func TestShorterCallerDeadlineWins(t *testing.T) {
	srv := slowServer(t, 500*time.Millisecond)
	c := timeoutTestClient(t, srv.URL)
	c.SetCallTimeout(10 * time.Second) // generous provider timeout

	ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()
	start := time.Now()
	_, err := c.ChatCompletionCtx(ctx, []Message{{Role: "user", Content: "hi"}}, CallOpts{Model: "m"})
	if err == nil {
		t.Fatal("expected a deadline error")
	}
	if elapsed := time.Since(start); elapsed > 400*time.Millisecond {
		t.Errorf("call took %v — the 50ms caller deadline did not win", elapsed)
	}
}

func TestFastCallSucceedsWithTimeout(t *testing.T) {
	srv := slowServer(t, 1*time.Millisecond)
	c := timeoutTestClient(t, srv.URL)
	c.SetCallTimeout(5 * time.Second)

	resp, err := c.ChatCompletionCtx(context.Background(), []Message{{Role: "user", Content: "hi"}}, CallOpts{Model: "m"})
	if err != nil {
		t.Fatalf("fast call must succeed: %v", err)
	}
	if resp.Content == "" {
		t.Error("empty response content")
	}
}

func TestSetCallTimeoutKeepsHTTPClientAligned(t *testing.T) {
	c := timeoutTestClient(t, "http://127.0.0.1")
	c.SetCallTimeout(10 * time.Minute)
	if c.callTimeout != 10*time.Minute {
		t.Fatalf("callTimeout = %v, want 10m", c.callTimeout)
	}
	if c.client.Timeout != 10*time.Minute {
		t.Fatalf("http timeout = %v, want 10m", c.client.Timeout)
	}
	c.SetCallTimeout(0)
	if c.callTimeout != 0 {
		t.Fatalf("callTimeout = %v, want disabled", c.callTimeout)
	}
	if c.client.Timeout != 0 {
		t.Fatalf("http timeout = %v, want disabled", c.client.Timeout)
	}
}
