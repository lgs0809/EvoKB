package wiki

import (
	"context"
	"fmt"
	"strings"
)

func (c *Compiler) compile(ctx context.Context, req CompileRequest) (CompileResult, error) {
	template, ok := TemplateFor(req.WikiType)
	if !ok {
		return CompileResult{}, fmt.Errorf("unsupported wiki type %q", req.WikiType)
	}
	if strings.TrimSpace(req.Title) == "" || len(req.SummaryIDs) == 0 {
		return CompileResult{}, fmt.Errorf("wiki title and source summaries are required")
	}
	summaries, err := c.Store.LoadSummaries(ctx, uniqueStrings(req.SummaryIDs))
	if err != nil {
		return CompileResult{}, err
	}
	facts, err := sourceFacts(summaries)
	if err != nil {
		return CompileResult{}, err
	}
	proposal, err := c.Generator.Generate(ctx, template, SourceSet{Summaries: summaries})
	if err != nil {
		return CompileResult{}, err
	}
	return c.compileProposal(ctx, req, template, facts, proposal)
}
