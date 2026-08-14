package raw

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"path/filepath"
	"strings"
	"time"
)

type MinerUClient struct {
	BaseURL      string
	HTTP         *http.Client
	PollInterval time.Duration
	ParseMethod  string
	Language     string
}

func NewMinerUClient(baseURL string) *MinerUClient {
	return &MinerUClient{
		BaseURL:      strings.TrimRight(baseURL, "/"),
		HTTP:         &http.Client{Timeout: 2 * time.Minute},
		PollInterval: 500 * time.Millisecond,
		ParseMethod:  "auto",
		Language:     "ch",
	}
}

type minerUTaskSubmit struct {
	TaskID string `json:"task_id"`
	Status string `json:"status"`
	Error  any    `json:"error"`
}

type minerUTaskStatus struct {
	TaskID string `json:"task_id"`
	Status string `json:"status"`
	Error  any    `json:"error"`
}

type minerUResultEnvelope struct {
	Backend string                      `json:"backend"`
	Version string                      `json:"version"`
	Results map[string]minerUFileResult `json:"results"`
}

type minerUFileResult struct {
	Markdown    string          `json:"md_content"`
	MiddleJSON  json.RawMessage `json:"middle_json"`
	ContentList json.RawMessage `json:"content_list"`
}

func (c *MinerUClient) Parse(ctx context.Context, filename string, r io.Reader) (*ParsedDocument, error) {
	if c.HTTP == nil {
		c.HTTP = &http.Client{Timeout: 2 * time.Minute}
	}
	var body bytes.Buffer
	mw := multipart.NewWriter(&body)
	part, err := mw.CreateFormFile("files", filepath.Base(filename))
	if err != nil {
		return nil, err
	}
	if _, err := io.Copy(part, r); err != nil {
		return nil, err
	}
	fields := map[string]string{
		"backend":             "pipeline",
		"parse_method":        valueOr(c.ParseMethod, "auto"),
		"lang_list":           valueOr(c.Language, "ch"),
		"return_md":           "true",
		"return_middle_json":  "true",
		"return_content_list": "true",
	}
	for k, v := range fields {
		if err := mw.WriteField(k, v); err != nil {
			return nil, err
		}
	}
	if err := mw.Close(); err != nil {
		return nil, err
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.BaseURL+"/tasks", &body)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", mw.FormDataContentType())
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return nil, fmt.Errorf("mineru submit: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode/100 != 2 {
		b, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
		return nil, fmt.Errorf("mineru submit: HTTP %d: %s", resp.StatusCode, strings.TrimSpace(string(b)))
	}
	var submit minerUTaskSubmit
	if err := json.NewDecoder(resp.Body).Decode(&submit); err != nil {
		return nil, fmt.Errorf("mineru submit decode: %w", err)
	}
	if submit.TaskID == "" {
		return nil, fmt.Errorf("mineru submit: empty task_id")
	}

	poll := c.PollInterval
	if poll <= 0 {
		poll = 500 * time.Millisecond
	}
	for {
		status, err := c.taskStatus(ctx, submit.TaskID)
		if err != nil {
			return nil, err
		}
		switch strings.ToLower(status.Status) {
		case "completed", "succeeded", "success":
			return c.taskResult(ctx, submit.TaskID)
		case "failed", "error", "cancelled", "canceled":
			return nil, fmt.Errorf("mineru task %s %s: %v", submit.TaskID, status.Status, status.Error)
		}
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-time.After(poll):
		}
	}
}

func (c *MinerUClient) taskStatus(ctx context.Context, id string) (*minerUTaskStatus, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.BaseURL+"/tasks/"+id, nil)
	if err != nil {
		return nil, err
	}
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return nil, fmt.Errorf("mineru status: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode/100 != 2 {
		b, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
		return nil, fmt.Errorf("mineru status: HTTP %d: %s", resp.StatusCode, strings.TrimSpace(string(b)))
	}
	var s minerUTaskStatus
	if err := json.NewDecoder(resp.Body).Decode(&s); err != nil {
		return nil, fmt.Errorf("mineru status decode: %w", err)
	}
	return &s, nil
}

func (c *MinerUClient) taskResult(ctx context.Context, id string) (*ParsedDocument, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.BaseURL+"/tasks/"+id+"/result", nil)
	if err != nil {
		return nil, err
	}
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return nil, fmt.Errorf("mineru result: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode/100 != 2 {
		b, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
		return nil, fmt.Errorf("mineru result: HTTP %d: %s", resp.StatusCode, strings.TrimSpace(string(b)))
	}
	var env minerUResultEnvelope
	if err := json.NewDecoder(resp.Body).Decode(&env); err != nil {
		return nil, fmt.Errorf("mineru result decode: %w", err)
	}
	if len(env.Results) != 1 {
		return nil, fmt.Errorf("mineru result: expected one file, got %d", len(env.Results))
	}
	var fr minerUFileResult
	for _, v := range env.Results {
		fr = v
	}
	middle, err := normalizeJSONField(fr.MiddleJSON)
	if err != nil {
		return nil, fmt.Errorf("mineru middle_json: %w", err)
	}
	contentList, err := normalizeJSONField(fr.ContentList)
	if err != nil {
		return nil, fmt.Errorf("mineru content_list: %w", err)
	}
	var rawBlocks []map[string]any
	if err := json.Unmarshal([]byte(contentList), &rawBlocks); err != nil {
		return nil, fmt.Errorf("mineru content_list parse: %w", err)
	}
	pageSizes := minerUPageSizes(middle)
	blocks := make([]ParsedBlock, 0, len(rawBlocks))
	for _, rb := range rawBlocks {
		block := parsedBlockFromMap(rb)
		if size, ok := pageSizes[block.Page]; ok {
			block.PageSize = append([]float64(nil), size...)
		}
		blocks = append(blocks, block)
	}
	return &ParsedDocument{
		Markdown:      fr.Markdown,
		MiddleJSON:    middle,
		ContentList:   contentList,
		ParserName:    "mineru-pipeline",
		ParserVersion: env.Version,
		Blocks:        blocks,
	}, nil
}

func normalizeJSONField(raw json.RawMessage) (string, error) {
	if len(raw) == 0 || bytes.Equal(raw, []byte("null")) {
		return "null", nil
	}
	var s string
	if err := json.Unmarshal(raw, &s); err == nil {
		return s, nil
	}
	var v any
	if err := json.Unmarshal(raw, &v); err != nil {
		return "", err
	}
	b, err := json.Marshal(v)
	return string(b), err
}

func minerUPageSizes(middle string) map[int][]float64 {
	var envelope struct {
		PDFInfo []struct {
			PageIdx  int       `json:"page_idx"`
			PageSize []float64 `json:"page_size"`
		} `json:"pdf_info"`
	}
	if err := json.Unmarshal([]byte(middle), &envelope); err != nil {
		return nil
	}
	out := make(map[int][]float64, len(envelope.PDFInfo))
	for _, page := range envelope.PDFInfo {
		if len(page.PageSize) >= 2 && page.PageSize[0] > 0 && page.PageSize[1] > 0 {
			out[page.PageIdx] = append([]float64(nil), page.PageSize...)
		}
	}
	return out
}

func parsedBlockFromMap(m map[string]any) ParsedBlock {
	p := ParsedBlock{Page: -1, Raw: m}
	p.Type, _ = m["type"].(string)
	p.Text, _ = m["text"].(string)
	p.ImagePath, _ = m["img_path"].(string)
	p.TableHTML, _ = m["table_body"].(string)
	if p.TableHTML == "" {
		p.TableHTML, _ = m["table_html"].(string)
	}
	p.TableBody = p.TableHTML
	if v, ok := numberAsInt(m["text_level"]); ok {
		p.TextLevel = v
	}
	if v, ok := numberAsInt(m["page_idx"]); ok {
		p.Page = v
	}
	if a, ok := m["bbox"].([]any); ok {
		for _, v := range a {
			if f, ok := v.(float64); ok {
				p.BBox = append(p.BBox, f)
			}
		}
	}
	if p.Type == "image" && p.Text == "" {
		if captions, ok := m["image_caption"].([]any); ok {
			var parts []string
			for _, v := range captions {
				if s, ok := v.(string); ok && strings.TrimSpace(s) != "" {
					parts = append(parts, s)
				}
			}
			p.Text = strings.Join(parts, " ")
		}
	}
	return p
}

func numberAsInt(v any) (int, bool) {
	switch n := v.(type) {
	case float64:
		return int(n), true
	case int:
		return n, true
	case json.Number:
		i, err := n.Int64()
		return int(i), err == nil
	default:
		return 0, false
	}
}

func valueOr(s, fallback string) string {
	if strings.TrimSpace(s) == "" {
		return fallback
	}
	return s
}
