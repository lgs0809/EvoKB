package llm

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
)

type structuredStubProvider struct {
	mechanismOK bool
	body        []byte
	repairBody  []byte
	calls       *int32
	parseCalls  *int32
	plainCalls  *int32
	plainBody   string
	url         string
}

func (p structuredStubProvider) Name() string { return "stub" }
func (p structuredStubProvider) FormatRequest(messages []Message, opts CallOpts) (*http.Request, error) {
	if p.plainCalls != nil {
		atomic.AddInt32(p.plainCalls, 1)
	}
	return http.NewRequest("POST", p.url, nil)
}
func (p structuredStubProvider) ParseResponse(body []byte) (*Response, error) {
	content := p.plainBody
	if content == "" {
		content = "plain"
	}
	return &Response{Content: content, Model: "stub-model", Usage: Usage{InputTokens: 1, OutputTokens: 1}}, nil
}
func (p structuredStubProvider) SupportsVision() bool { return false }

func (p structuredStubProvider) FormatStructuredRequest(messages []Message, schema JSONSchema, opts CallOpts) (func() (*http.Request, error), bool, error) {
	if !p.mechanismOK {
		return nil, false, nil
	}
	return func() (*http.Request, error) {
		atomic.AddInt32(p.calls, 1)
		return http.NewRequest("POST", p.url, nil)
	}, true, nil
}

func (p structuredStubProvider) ParseStructuredResponse(body []byte) (json.RawMessage, error) {
	if p.parseCalls != nil {
		call := atomic.AddInt32(p.parseCalls, 1)
		if call > 1 && len(p.repairBody) > 0 {
			return p.repairBody, nil
		}
	}
	return p.body, nil
}

func newStructuredClient(p Provider, server *httptest.Server) *Client {
	return &Client{
		provider: p,
		client:   *server.Client(),
		limiter:  newRateLimiter(0),
		tracker:  newCostTrackerWithRegistry("stub", 0, &Registry{entries: map[string]Price{}, prefix: map[string][]string{}}),
	}
}

func TestStructuredCompletionUnwrap(t *testing.T) {
	var calls int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte(`{"ok": true}`))
	}))
	defer server.Close()

	p := structuredStubProvider{mechanismOK: true, body: []byte(`{"items": [{"id": 1, "score": 0.5}]}`), calls: &calls, url: server.URL}
	c := newStructuredClient(p, server)
	schema := JSONSchema{
		Name: "test", IsArray: true,
		Schema: map[string]any{
			"type": "array",
			"items": map[string]any{
				"type": "object",
				"properties": map[string]any{
					"id":    map[string]any{"type": "integer"},
					"score": map[string]any{"type": "number"},
				},
				"required": []string{"id", "score"},
			},
			"minItems": 1,
		},
	}
	payload, raw, err := c.StructuredCompletion(t.Context(), []Message{{Role: "user", Content: "x"}}, schema, CallOpts{Model: "m"})
	if err != nil {
		t.Fatal(err)
	}
	if raw != "" {
		t.Error("rawText on native path")
	}
	if string(payload) != `[{"id": 1, "score": 0.5}]` {
		t.Errorf("unwrap failed: %s", payload)
	}
	if calls != 1 {
		t.Errorf("request built %d times, want 1", calls)
	}
}

func TestStructuredCompletionValidationErrorNoFallback(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte(`{"ok": true}`))
	}))
	defer server.Close()
	var calls int32
	p := structuredStubProvider{mechanismOK: true, body: []byte(`{"items": []}`), calls: &calls, url: server.URL}
	c := newStructuredClient(p, server)
	schema := JSONSchema{Name: "t", IsArray: true, Schema: map[string]any{
		"type": "array", "minItems": 1,
		"items": map[string]any{"type": "object", "properties": map[string]any{
			"id": map[string]any{"type": "integer"}}, "required": []string{"id"}},
	}}
	_, _, err := c.StructuredCompletion(t.Context(), []Message{{Role: "user", Content: "x"}}, schema, CallOpts{Model: "m"})
	if err == nil {
		t.Fatal("expected validation error (minItems), got nil — and it must NOT silently pass")
	}
}

func TestStructuredCompletionRepairsGatewaySchemaDriftOnce(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte(`{"ok": true}`))
	}))
	defer server.Close()
	var nativeCalls, parseCalls, plainCalls int32
	validRepair := `{"facts":[{"section":"Overview","fact_text":"Amount is 3 million.","evidence_block_ids":["b1"]}]}`
	p := structuredStubProvider{
		mechanismOK: true,
		body:        []byte(`{"facts":[{"source":"b1","fact":"Amount is 3 million."}]}`),
		repairBody:  []byte(validRepair),
		calls:       &nativeCalls,
		parseCalls:  &parseCalls,
		plainCalls:  &plainCalls,
		url:         server.URL,
	}
	c := newStructuredClient(p, server)
	schema := JSONSchema{Name: "facts", Schema: map[string]any{
		"type": "object",
		"properties": map[string]any{
			"facts": map[string]any{
				"type": "array",
				"items": map[string]any{
					"type": "object",
					"properties": map[string]any{
						"section":            map[string]any{"type": "string"},
						"fact_text":          map[string]any{"type": "string"},
						"evidence_block_ids": map[string]any{"type": "array", "items": map[string]any{"type": "string"}},
					},
					"required": []string{"section", "fact_text", "evidence_block_ids"},
				},
			},
		},
		"required": []string{"facts"},
	}}
	payload, _, err := c.StructuredCompletion(t.Context(), []Message{{Role: "user", Content: "x"}}, schema, CallOpts{Model: "m"})
	if err != nil {
		t.Fatal(err)
	}
	if string(payload) != validRepair {
		t.Fatalf("payload = %s", payload)
	}
	if nativeCalls != 2 || parseCalls != 2 || plainCalls != 0 {
		t.Fatalf("calls native=%d parse=%d plain=%d, want 2/2/0", nativeCalls, parseCalls, plainCalls)
	}
}

func TestStructuredCompletionRejectsInvalidRepair(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte(`{"ok": true}`))
	}))
	defer server.Close()
	var nativeCalls, parseCalls, plainCalls int32
	p := structuredStubProvider{
		mechanismOK: true,
		body:        []byte(`{"value":"wrong"}`),
		repairBody:  []byte(`{"value":"still-wrong"}`),
		calls:       &nativeCalls,
		parseCalls:  &parseCalls,
		plainCalls:  &plainCalls,
		url:         server.URL,
	}
	c := newStructuredClient(p, server)
	schema := JSONSchema{Name: "required", Schema: map[string]any{
		"type":       "object",
		"properties": map[string]any{"required_value": map[string]any{"type": "string"}},
		"required":   []string{"required_value"},
	}}
	_, _, err := c.StructuredCompletion(t.Context(), []Message{{Role: "user", Content: "x"}}, schema, CallOpts{Model: "m"})
	if err == nil {
		t.Fatal("expected repaired payload to remain fail-closed")
	}
	if nativeCalls != 2 || parseCalls != 2 || plainCalls != 0 {
		t.Fatalf("calls native=%d parse=%d plain=%d, want 2/2/0", nativeCalls, parseCalls, plainCalls)
	}
}
