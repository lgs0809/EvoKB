package ask

import (
	"context"
	"fmt"
	"regexp"
	"sort"
	"strconv"
	"strings"

	enterpriseapi "github.com/xoai/sage-wiki/internal/enterprise/api"
	enterpriseevidence "github.com/xoai/sage-wiki/internal/enterprise/evidence"
)

type Answerer interface {
	Answer(ctx context.Context, question, groundedContext string) (string, error)
}

type Service struct {
	Search   *enterpriseapi.Service
	Answerer Answerer
	MaxChars int
}

type Request struct {
	Question string                      `json:"question"`
	Filters  enterpriseapi.SearchFilters `json:"filters,omitempty"`
	Limit    int                         `json:"limit,omitempty"`
}

type Citation struct {
	Index      int                         `json:"index"`
	ResultType string                      `json:"result_type"`
	Evidence   enterpriseevidence.Resolved `json:"evidence"`
}

type Response struct {
	SearchID     string     `json:"search_id"`
	Answer       string     `json:"answer"`
	Citations    []Citation `json:"citations"`
	EvidenceOnly bool       `json:"evidence_only"`
}

type source struct {
	Index      int
	ResultType string
	Knowledge  string
	Evidence   enterpriseevidence.Resolved
}

var citationPattern = regexp.MustCompile(`\[(\d+)\]`)

func (s *Service) Ask(ctx context.Context, req Request) (Response, error) {
	if s == nil || s.Search == nil {
		return Response{}, fmt.Errorf("enterprise ask search is unavailable")
	}
	question := strings.TrimSpace(req.Question)
	if question == "" {
		return Response{}, fmt.Errorf("question is required")
	}
	limit := req.Limit
	if limit <= 0 || limit > 20 {
		limit = 8
	}
	found, err := s.Search.SearchKnowledge(ctx, enterpriseapi.SearchKnowledgeRequest{Query: question, Filters: req.Filters, Limit: limit})
	if err != nil {
		return Response{}, err
	}
	sources := buildSources(found.Results)
	if len(sources) == 0 {
		return Response{SearchID: found.SearchID, Answer: "当前知识库中没有找到足够的可验证证据来回答这个问题。", EvidenceOnly: true, Citations: []Citation{}}, nil
	}
	contextText := groundedContext(sources, s.MaxChars)
	if s.Answerer == nil {
		return Response{SearchID: found.SearchID, Answer: "已找到可验证知识，但当前未配置 Ask 回答模型。请使用下方证据或高级知识检索继续查看。", EvidenceOnly: true, Citations: citationsFor(sources, nil)}, nil
	}
	answer, err := s.Answerer.Answer(ctx, question, contextText)
	if err != nil {
		return Response{}, err
	}
	answer = strings.TrimSpace(answer)
	if answer == "" {
		return Response{}, fmt.Errorf("ask model returned empty answer")
	}
	used, valid := validateCitations(answer, len(sources))
	if !valid || len(used) == 0 {
		return Response{
			SearchID:     found.SearchID,
			Answer:       "已找到可验证知识，但回答模型没有返回有效的 Evidence Citation，因此本次不交付未引用的自然语言答案。请查看下方证据或使用高级知识检索。",
			Citations:    citationsFor(sources, nil),
			EvidenceOnly: true,
		}, nil
	}
	return Response{SearchID: found.SearchID, Answer: answer, Citations: citationsFor(sources, used), EvidenceOnly: false}, nil
}

func buildSources(results []enterpriseapi.SearchKnowledgeResult) []source {
	seen := map[string]struct{}{}
	var out []source
	for _, result := range results {
		for _, evidence := range result.Evidence {
			if evidence.EvidenceID == "" {
				continue
			}
			if _, ok := seen[evidence.EvidenceID]; ok {
				continue
			}
			seen[evidence.EvidenceID] = struct{}{}
			out = append(out, source{Index: len(out) + 1, ResultType: result.ResultType, Knowledge: result.Knowledge, Evidence: evidence})
		}
	}
	return out
}

func groundedContext(sources []source, maxChars int) string {
	if maxChars <= 0 {
		maxChars = 24000
	}
	var b strings.Builder
	for _, item := range sources {
		block := fmt.Sprintf("[SOURCE %d]\nKnowledge type: %s\nKnowledge: %s\nRaw evidence: %s\nDocument version: %s\nRaw location: %v\n\n", item.Index, item.ResultType, item.Knowledge, item.Evidence.RawContent, item.Evidence.VersionID, item.Evidence.RawLocation)
		if b.Len()+len(block) > maxChars {
			break
		}
		b.WriteString(block)
	}
	return b.String()
}

func citedIndexes(answer string, max int) map[int]struct{} {
	used := map[int]struct{}{}
	for _, match := range citationPattern.FindAllStringSubmatch(answer, -1) {
		value, err := strconv.Atoi(match[1])
		if err == nil && value >= 1 && value <= max {
			used[value] = struct{}{}
		}
	}
	return used
}

func validateCitations(answer string, max int) (map[int]struct{}, bool) {
	matches := citationPattern.FindAllStringSubmatch(answer, -1)
	if len(matches) == 0 {
		return map[int]struct{}{}, false
	}
	used := map[int]struct{}{}
	for _, match := range matches {
		value, err := strconv.Atoi(match[1])
		if err != nil || value < 1 || value > max {
			return used, false
		}
		used[value] = struct{}{}
	}
	return used, true
}

func citationsFor(sources []source, used map[int]struct{}) []Citation {
	var out []Citation
	for _, item := range sources {
		if used != nil {
			if _, ok := used[item.Index]; !ok {
				continue
			}
		}
		out = append(out, Citation{Index: item.Index, ResultType: item.ResultType, Evidence: item.Evidence})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Index < out[j].Index })
	return out
}
