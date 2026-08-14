package graph

import (
	"context"
	"fmt"
	"regexp"
	"sort"
	"strings"
)

type WikiKeywordClaim struct {
	ClaimID     string
	WikiID      string
	WikiVersion uint64
	ClaimText   string
	EvidenceIDs []string
}

type WikiKeywordStore interface {
	LoadWikiKeywordClaims(ctx context.Context, wikiID string, wikiVersion uint64) ([]WikiKeywordClaim, error)
	FindCanonicalEntity(ctx context.Context, canonicalName string) (CanonicalEntity, bool, error)
	EnsureWikiKeywordRelation(ctx context.Context, relation RelationFact) (RelationFact, error)
	AddSupport(ctx context.Context, support EvidenceSupport) (EvidenceSupport, error)
}

type WikiKeywordProjector struct {
	Store      WikiKeywordStore
	Projector  Projector
	Confidence float64
}

type WikiKeywordProjection struct {
	WikiID         string
	WikiVersion    uint64
	ClaimsExamined int
	Relations      []RelationFact
	Supports       []EvidenceSupport
	SkippedClaims  map[string]string
}

type wikiKeywordPattern struct {
	Phrase       string
	RelationType string
	Reverse      bool
}

var wikiLinkPattern = regexp.MustCompile(`\[\[([^\[\]\r\n]{1,200})\]\]`)

var wikiKeywordPatterns = []wikiKeywordPattern{
	{Phrase: "is actual controller of", RelationType: RelationActualControllerOf},
	{Phrase: "actual controller of", RelationType: RelationActualControllerOf},
	{Phrase: "is controlled by", RelationType: RelationControls, Reverse: true},
	{Phrase: "controls", RelationType: RelationControls},
	{Phrase: "is shareholder of", RelationType: RelationShareholderOf},
	{Phrase: "is subsidiary of", RelationType: RelationSubsidiaryOf},
	{Phrase: "is parent of", RelationType: RelationParentOf},
	{Phrase: "is director of", RelationType: RelationDirectorOf},
	{Phrase: "is legal representative of", RelationType: RelationLegalRepOf},
	{Phrase: "is party to", RelationType: RelationPartyToContract},
	{Phrase: "signed with", RelationType: RelationSignsWith},
	{Phrase: "signs with", RelationType: RelationSignsWith},
	{Phrase: "guarantees", RelationType: RelationGuarantees},
	{Phrase: "borrows from", RelationType: RelationBorrowsFrom},
	{Phrase: "invests in", RelationType: RelationInvestsIn},
	{Phrase: "is involved in", RelationType: RelationInvolvedInCase},
	{Phrase: "owns intellectual property", RelationType: RelationOwnsIP},
	{Phrase: "holds license", RelationType: RelationHoldsLicense},
	{Phrase: "participates in", RelationType: RelationParticipatesIn},
	{Phrase: "approves", RelationType: RelationApproves},
	{Phrase: "supplies to", RelationType: RelationSuppliesTo},
	{Phrase: "is customer of", RelationType: RelationCustomerOf},
	{Phrase: "is related to", RelationType: RelationRelatedTo},
	{Phrase: "实际控制", RelationType: RelationActualControllerOf},
	{Phrase: "控制", RelationType: RelationControls},
	{Phrase: "股东", RelationType: RelationShareholderOf},
	{Phrase: "子公司", RelationType: RelationSubsidiaryOf},
	{Phrase: "母公司", RelationType: RelationParentOf},
	{Phrase: "董事", RelationType: RelationDirectorOf},
	{Phrase: "法定代表人", RelationType: RelationLegalRepOf},
	{Phrase: "签署", RelationType: RelationSignsWith},
	{Phrase: "签订", RelationType: RelationSignsWith},
	{Phrase: "担保", RelationType: RelationGuarantees},
	{Phrase: "投资", RelationType: RelationInvestsIn},
	{Phrase: "供应", RelationType: RelationSuppliesTo},
}

func (p *WikiKeywordProjector) Project(ctx context.Context, wikiID string, wikiVersion uint64) (WikiKeywordProjection, error) {
	if p == nil || p.Store == nil {
		return WikiKeywordProjection{}, fmt.Errorf("wiki keyword projector store is required")
	}
	wikiID = strings.TrimSpace(wikiID)
	if wikiID == "" || wikiVersion == 0 {
		return WikiKeywordProjection{}, fmt.Errorf("wiki keyword projector requires wiki ID and version")
	}
	claims, err := p.Store.LoadWikiKeywordClaims(ctx, wikiID, wikiVersion)
	if err != nil {
		return WikiKeywordProjection{}, err
	}
	result := WikiKeywordProjection{
		WikiID: wikiID, WikiVersion: wikiVersion, ClaimsExamined: len(claims),
		SkippedClaims: make(map[string]string),
	}
	if p.Projector != nil {
		if err := p.Projector.EnsureSchema(ctx); err != nil {
			return WikiKeywordProjection{}, fmt.Errorf("wiki keyword neo4j schema: %w", err)
		}
	}
	confidence := p.Confidence
	if confidence <= 0 || confidence > 1 {
		confidence = 0.85
	}
	seenRelations := make(map[string]struct{})
	seenSupports := make(map[string]struct{})
	for _, claim := range claims {
		parsed, reason := parseWikiKeywordClaim(claim.ClaimText)
		if reason != "" {
			result.SkippedClaims[claim.ClaimID] = reason
			continue
		}
		source, ok, err := p.Store.FindCanonicalEntity(ctx, parsed.SourceName)
		if err != nil {
			return WikiKeywordProjection{}, err
		}
		if !ok {
			result.SkippedClaims[claim.ClaimID] = "source entity is not uniquely resolved"
			continue
		}
		target, ok, err := p.Store.FindCanonicalEntity(ctx, parsed.TargetName)
		if err != nil {
			return WikiKeywordProjection{}, err
		}
		if !ok {
			result.SkippedClaims[claim.ClaimID] = "target entity is not uniquely resolved"
			continue
		}
		proposed := ProposedRelation{
			SourceName: source.CanonicalName, SourceType: source.EntityType,
			RelationType: parsed.RelationType,
			TargetName:   target.CanonicalName, TargetType: target.EntityType,
			SupportingFactIDs: []string{"wiki-claim:" + claim.ClaimID}, Confidence: confidence,
		}
		if err := ValidateRelation(proposed); err != nil {
			result.SkippedClaims[claim.ClaimID] = "schema rejected: " + err.Error()
			continue
		}
		relation := RelationFact{
			ID:             relationID(source.ID, parsed.RelationType, target.ID, nil),
			SourceEntityID: source.ID, RelationType: parsed.RelationType, TargetEntityID: target.ID,
			ConflictStatus: "NONE", Lifecycle: "ACTIVE",
		}
		storedRelation, err := p.Store.EnsureWikiKeywordRelation(ctx, relation)
		if err != nil {
			return WikiKeywordProjection{}, err
		}
		version := claim.WikiVersion
		var relationSupports []EvidenceSupport
		for _, evidenceID := range uniqueNonEmpty(claim.EvidenceIDs) {
			support := EvidenceSupport{
				ID:         supportID(storedRelation.ID, OriginWikiKeyword, evidenceID, "", claim.WikiID, &version),
				RelationID: storedRelation.ID, Origin: OriginWikiKeyword, EvidenceID: evidenceID,
				WikiID: claim.WikiID, WikiVersion: &version, Confidence: confidence, Active: true,
			}
			storedSupport, err := p.Store.AddSupport(ctx, support)
			if err != nil {
				return WikiKeywordProjection{}, err
			}
			relationSupports = append(relationSupports, storedSupport)
			if _, exists := seenSupports[storedSupport.ID]; !exists {
				seenSupports[storedSupport.ID] = struct{}{}
				result.Supports = append(result.Supports, storedSupport)
			}
		}
		if len(relationSupports) == 0 {
			result.SkippedClaims[claim.ClaimID] = "claim has no active raw evidence"
			continue
		}
		if p.Projector != nil {
			if err := p.Projector.UpsertEntity(ctx, source); err != nil {
				return WikiKeywordProjection{}, err
			}
			if err := p.Projector.UpsertEntity(ctx, target); err != nil {
				return WikiKeywordProjection{}, err
			}
			if err := p.Projector.UpsertRelation(ctx, storedRelation, relationSupports); err != nil {
				return WikiKeywordProjection{}, err
			}
		}
		if _, exists := seenRelations[storedRelation.ID]; !exists {
			seenRelations[storedRelation.ID] = struct{}{}
			result.Relations = append(result.Relations, storedRelation)
		}
	}
	sort.Slice(result.Relations, func(i, j int) bool { return result.Relations[i].ID < result.Relations[j].ID })
	sort.Slice(result.Supports, func(i, j int) bool { return result.Supports[i].ID < result.Supports[j].ID })
	return result, nil
}

type parsedWikiKeywordClaim struct {
	SourceName   string
	RelationType string
	TargetName   string
}

func parseWikiKeywordClaim(text string) (parsedWikiKeywordClaim, string) {
	text = strings.TrimSpace(text)
	if text == "" {
		return parsedWikiKeywordClaim{}, "empty claim"
	}
	if containsWikiNegation(text) {
		return parsedWikiKeywordClaim{}, "negated claim"
	}
	matches := wikiLinkPattern.FindAllStringSubmatchIndex(text, -1)
	if len(matches) != 2 {
		return parsedWikiKeywordClaim{}, "claim must contain exactly two wikilinks"
	}
	sourceName := wikiLinkName(text[matches[0][2]:matches[0][3]])
	targetName := wikiLinkName(text[matches[1][2]:matches[1][3]])
	between := normalizeKeywordPhrase(text[matches[0][1]:matches[1][0]])
	for _, pattern := range wikiKeywordPatterns {
		if !strings.Contains(between, normalizeKeywordPhrase(pattern.Phrase)) {
			continue
		}
		if pattern.Reverse {
			sourceName, targetName = targetName, sourceName
		}
		return parsedWikiKeywordClaim{SourceName: sourceName, RelationType: pattern.RelationType, TargetName: targetName}, ""
	}
	return parsedWikiKeywordClaim{}, "no allowed relation synonym"
}

func wikiLinkName(value string) string {
	value = strings.TrimSpace(value)
	if before, _, ok := strings.Cut(value, "|"); ok {
		value = before
	}
	return strings.TrimSpace(value)
}

func normalizeKeywordPhrase(value string) string {
	value = strings.ToLower(strings.TrimSpace(value))
	return strings.Join(strings.Fields(value), " ")
}

func containsWikiNegation(value string) bool {
	lower := strings.ToLower(value)
	for _, token := range []string{" not ", " never ", " without ", " no longer ", " isn't ", " aren't ", " wasn't ", " weren't ", "未", "不再", "不是", "并非", "无权"} {
		if strings.Contains(" "+lower+" ", token) {
			return true
		}
	}
	return false
}

func uniqueNonEmpty(values []string) []string {
	seen := make(map[string]struct{}, len(values))
	out := make([]string, 0, len(values))
	for _, value := range values {
		value = strings.TrimSpace(value)
		if value == "" {
			continue
		}
		if _, ok := seen[value]; ok {
			continue
		}
		seen[value] = struct{}{}
		out = append(out, value)
	}
	sort.Strings(out)
	return out
}
