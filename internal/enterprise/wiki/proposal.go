package wiki

import (
	"context"
	"fmt"
	"sort"
	"strings"
	"time"

	enterprisesummary "github.com/xoai/sage-wiki/internal/enterprise/summary"
)

func (c *Compiler) compileProposal(ctx context.Context, req CompileRequest, template Template, facts map[string]enterprisesummary.Fact, proposal Proposal) (CompileResult, error) {
	if strings.TrimSpace(proposal.WikiType) != "" && strings.ToUpper(strings.TrimSpace(proposal.WikiType)) != template.WikiType {
		return CompileResult{}, fmt.Errorf("wiki proposal type mismatch")
	}
	coverage, decisions, err := validateDecisions(facts, proposal.FactDecisions)
	if err != nil {
		return CompileResult{}, err
	}
	wikiID := stableID("wiki", template.WikiType, strings.ToLower(strings.TrimSpace(req.Title)), strings.TrimSpace(req.CanonicalSubjectID))
	version, err := c.Store.NextWikiVersion(ctx, wikiID)
	if err != nil {
		return CompileResult{}, err
	}
	claims, covered, err := c.groundClaims(ctx, wikiID, version, template, proposal.Claims, facts, decisions)
	if err != nil {
		return CompileResult{}, err
	}
	for factID, decision := range decisions {
		if decision.WikiEligible && !covered[factID] {
			coverage.Pass = false
			coverage.MissingFacts = append(coverage.MissingFacts, factID)
		}
	}
	sort.Strings(coverage.MissingFacts)
	coverage.CoveredFacts = coverage.EligibleFacts - len(coverage.MissingFacts)
	if !coverage.Pass {
		return CompileResult{}, fmt.Errorf("wiki coverage failed")
	}
	wiki := StoredWiki{
		ID: wikiID, Version: version, WikiType: template.WikiType,
		CanonicalSubjectID: strings.TrimSpace(req.CanonicalSubjectID),
		Title: strings.TrimSpace(req.Title), Lifecycle: "ACTIVE",
		Claims: claims, CreatedAt: time.Now().UTC(),
	}
	markdown := RenderMarkdown(template, wiki)
	wiki.ContentHash = contentHash(markdown)
	wiki.ObjectKey = fmt.Sprintf("wiki/%s/v%d.md", wiki.ID, wiki.Version)
	if c.Objects == nil {
		return CompileResult{}, fmt.Errorf("wiki object store is required")
	}
	if err := putWikiObject(ctx, c.Objects, wiki.ObjectKey, markdown); err != nil {
		return CompileResult{}, fmt.Errorf("wiki persist object: %w", err)
	}
	if err := c.Store.SaveWiki(ctx, wiki); err != nil {
		return CompileResult{}, fmt.Errorf("wiki persist metadata: %w", err)
	}
	if c.Indexer != nil {
		if err := c.Indexer.IndexWiki(ctx, wiki, markdown); err != nil {
			return CompileResult{}, fmt.Errorf("wiki derived index: %w", err)
		}
	}
	return CompileResult{Wiki: wiki, Markdown: markdown, Coverage: coverage, Writer: c.Generator.Identity(), Judge: c.Judge.Identity()}, nil
}
