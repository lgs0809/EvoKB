package main

import (
	"bufio"
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"sort"
	"strings"
	"time"

	enterpriseapi "github.com/xoai/sage-wiki/internal/enterprise/api"
)

type retrievalGoldenCase struct {
	ID                 string                      `json:"id"`
	Query              string                      `json:"query"`
	Filters            enterpriseapi.SearchFilters `json:"filters,omitempty"`
	Limit              int                         `json:"limit,omitempty"`
	ExpectedPhrases    []string                    `json:"expected_phrases,omitempty"`
	ExpectedVersionIDs []string                    `json:"expected_version_ids,omitempty"`
}

type retrievalEvalCaseResult struct {
	ID                 string  `json:"id"`
	KnowledgeRecall    float64 `json:"knowledge_recall"`
	EvidenceRecall     float64 `json:"evidence_recall"`
	Success            bool    `json:"success"`
	LatencyMilliseconds int64  `json:"latency_ms"`
	ResultCount        int     `json:"result_count"`
}

type retrievalEvalReport struct {
	Mode                  string                    `json:"mode"`
	Cases                 int                       `json:"cases"`
	KnowledgeRecallAtK    float64                    `json:"knowledge_recall_at_k"`
	EvidenceRecallAtK     float64                    `json:"evidence_recall_at_k"`
	RetrievalSuccessRate  float64                    `json:"retrieval_success_rate"`
	MeanLatencyMillis     float64                    `json:"mean_latency_ms"`
	P95LatencyMillis      int64                      `json:"p95_latency_ms"`
	TaskSuccessAvailable  bool                       `json:"task_success_available"`
	Results               []retrievalEvalCaseResult `json:"results"`
}

func runEval(ctx context.Context, args []string) {
	fs := flag.NewFlagSet("eval", flag.ExitOnError)
	casesPath := fs.String("cases", "", "JSONL Golden cases")
	mode := fs.String("mode", "A", "A=Raw, B=Raw+Summary, C=Raw+Summary+Wiki, D=Full Graph")
	_ = fs.Parse(args)
	if strings.TrimSpace(*casesPath) == "" {
		fatalf("eval: --cases is required")
	}
	applyRetrievalEvalMode(*mode)
	cases, err := loadRetrievalGoldenCases(*casesPath)
	if err != nil {
		fatalf("eval cases: %v", err)
	}
	if len(cases) == 0 {
		fatalf("eval: no cases loaded")
	}
	runtime, err := openRetrievalRuntime(ctx)
	if err != nil {
		fatalf("eval runtime: %v", err)
	}
	defer runtime.Close()
	// Evaluation traffic must never count as runtime effective usage.
	runtime.API.SearchAudit = nil

	report := retrievalEvalReport{Mode: strings.ToUpper(strings.TrimSpace(*mode)), Cases: len(cases), TaskSuccessAvailable: false}
	latencies := make([]int64, 0, len(cases))
	var knowledgeSum, evidenceSum float64
	var success int
	for _, item := range cases {
		started := time.Now()
		response, err := runtime.API.SearchKnowledge(ctx, enterpriseapi.SearchKnowledgeRequest{
			Query: item.Query, Filters: item.Filters, Limit: item.Limit,
		})
		latency := time.Since(started).Milliseconds()
		latencies = append(latencies, latency)
		caseResult := retrievalEvalCaseResult{ID: item.ID, LatencyMilliseconds: latency}
		if err == nil {
			caseResult.ResultCount = len(response.Results)
			caseResult.KnowledgeRecall = phraseRecall(response, item.ExpectedPhrases)
			caseResult.EvidenceRecall = versionRecall(response, item.ExpectedVersionIDs)
			caseResult.Success = expectationPassed(item.ExpectedPhrases, caseResult.KnowledgeRecall) && expectationPassed(item.ExpectedVersionIDs, caseResult.EvidenceRecall)
		}
		knowledgeSum += caseResult.KnowledgeRecall
		evidenceSum += caseResult.EvidenceRecall
		if caseResult.Success {
			success++
		}
		report.Results = append(report.Results, caseResult)
	}
	report.KnowledgeRecallAtK = knowledgeSum / float64(len(cases))
	report.EvidenceRecallAtK = evidenceSum / float64(len(cases))
	report.RetrievalSuccessRate = float64(success) / float64(len(cases))
	for _, value := range latencies {
		report.MeanLatencyMillis += float64(value)
	}
	report.MeanLatencyMillis /= float64(len(latencies))
	sort.Slice(latencies, func(i, j int) bool { return latencies[i] < latencies[j] })
	index := int(float64(len(latencies)-1) * 0.95)
	report.P95LatencyMillis = latencies[index]
	printJSON(report)
}

func applyRetrievalEvalMode(mode string) {
	switch strings.ToUpper(strings.TrimSpace(mode)) {
	case "A", "RAW":
		_ = os.Setenv("EKB_RETRIEVAL_ENABLE_SUMMARY", "false")
		_ = os.Setenv("EKB_RETRIEVAL_ENABLE_WIKI", "false")
		_ = os.Setenv("EKB_RETRIEVAL_ENABLE_GRAPH", "false")
	case "B", "SUMMARY":
		_ = os.Setenv("EKB_RETRIEVAL_ENABLE_SUMMARY", "true")
		_ = os.Setenv("EKB_RETRIEVAL_ENABLE_WIKI", "false")
		_ = os.Setenv("EKB_RETRIEVAL_ENABLE_GRAPH", "false")
	case "C", "WIKI":
		_ = os.Setenv("EKB_RETRIEVAL_ENABLE_SUMMARY", "true")
		_ = os.Setenv("EKB_RETRIEVAL_ENABLE_WIKI", "true")
		_ = os.Setenv("EKB_RETRIEVAL_ENABLE_GRAPH", "false")
	case "D", "GRAPH", "FULL":
		_ = os.Setenv("EKB_RETRIEVAL_ENABLE_SUMMARY", "true")
		_ = os.Setenv("EKB_RETRIEVAL_ENABLE_WIKI", "true")
		_ = os.Setenv("EKB_RETRIEVAL_ENABLE_GRAPH", "true")
	default:
		fatalf("eval: unsupported mode %q", mode)
	}
}

func loadRetrievalGoldenCases(path string) ([]retrievalGoldenCase, error) {
	file, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer file.Close()
	var out []retrievalGoldenCase
	scanner := bufio.NewScanner(file)
	scanner.Buffer(make([]byte, 64*1024), 2*1024*1024)
	lineNo := 0
	for scanner.Scan() {
		lineNo++
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		var item retrievalGoldenCase
		if err := json.Unmarshal([]byte(line), &item); err != nil {
			return nil, fmt.Errorf("line %d: %w", lineNo, err)
		}
		if strings.TrimSpace(item.ID) == "" || strings.TrimSpace(item.Query) == "" {
			return nil, fmt.Errorf("line %d: id and query are required", lineNo)
		}
		if item.Limit <= 0 {
			item.Limit = 5
		}
		out = append(out, item)
	}
	return out, scanner.Err()
}

func phraseRecall(response *enterpriseapi.SearchKnowledgeResponse, expected []string) float64 {
	if len(expected) == 0 {
		return 1
	}
	joined := strings.Builder{}
	for _, result := range response.Results {
		joined.WriteString("\n")
		joined.WriteString(strings.ToLower(result.Knowledge))
	}
	body := joined.String()
	matched := 0
	for _, phrase := range expected {
		if strings.Contains(body, strings.ToLower(strings.TrimSpace(phrase))) {
			matched++
		}
	}
	return float64(matched) / float64(len(expected))
}

func versionRecall(response *enterpriseapi.SearchKnowledgeResponse, expected []string) float64 {
	if len(expected) == 0 {
		return 1
	}
	seen := make(map[string]struct{})
	for _, result := range response.Results {
		for _, evidence := range result.Evidence {
			seen[evidence.VersionID] = struct{}{}
		}
	}
	matched := 0
	for _, versionID := range expected {
		if _, ok := seen[versionID]; ok {
			matched++
		}
	}
	return float64(matched) / float64(len(expected))
}

func expectationPassed(expected []string, recall float64) bool {
	return len(expected) == 0 || recall >= 0.999999
}
