package wiki

import (
	"context"
	"fmt"
	"strings"

	enterprisesummary "github.com/xoai/sage-wiki/internal/enterprise/summary"
)

func (c *Compiler) groundClaims(ctx context.Context, wikiID string, version uint64, template Template, proposed []ProposedClaim, facts map[string]enterprisesummary.Fact, decisions map[string]FactDecision) ([]Claim, map[string]bool, error) {
	covered := make(map[string]bool)
	var claims []Claim
	for _, item := range proposed {
		claim, err := c.groundOneClaim(ctx, wikiID, version, template, item, facts, decisions)
		if err != nil {
			return nil, nil, err
		}
		claims = append(claims, claim)
		for _, factID := range claim.SupportingFactIDs {
			covered[factID] = true
		}
	}
	return claims, covered, nil
}

func (c *Compiler) groundOneClaim(ctx context.Context, wikiID string, version uint64, template Template, item ProposedClaim, facts map[string]enterprisesummary.Fact, decisions map[string]FactDecision) (Claim, error) {
	item.Section = strings.TrimSpace(item.Section)
	item.ClaimText = strings.TrimSpace(item.ClaimText)
	item.SupportingFactIDs = uniqueStrings(item.SupportingFactIDs)
	if item.ClaimText == "" || !template.HasSection(item.Section) || len(item.SupportingFactIDs) == 0 {
		return Claim{}, fmt.Errorf("wiki claim is incomplete")
	}
	supportFacts, evidenceIDs, err := claimSupport(item.SupportingFactIDs, facts, decisions)
	if err != nil {
		return Claim{}, err
	}
	supported, reason, err := c.Judge.SupportsClaim(ctx, item, supportFacts)
	if err != nil {
		return Claim{}, err
	}
	if !supported {
		return Claim{}, fmt.Errorf("wiki claim is not grounded")
	}
	claimID := stableID("wiki-claim", wikiID, fmt.Sprintf("%d", version), item.Section, item.ClaimText, strings.Join(item.SupportingFactIDs, ","))
	return Claim{ID: claimID, WikiID: wikiID, WikiVersion: version, Section: item.Section, ClaimText: item.ClaimText, SupportingFactIDs: item.SupportingFactIDs, EvidenceIDs: evidenceIDs, GroundingStatus: "PASS", GroundingReason: reason, TemporalStatus: "PASS"}, nil
}
