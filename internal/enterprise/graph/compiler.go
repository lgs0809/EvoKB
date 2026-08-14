package graph

import (
	"context"
	"fmt"
	"sort"
	"strings"

	enterprisesummary "github.com/xoai/sage-wiki/internal/enterprise/summary"
)

type Compiler struct {
	Store     Store
	Extractor Extractor
	Projector Projector
}

func (c *Compiler) CompileSummary(ctx context.Context, summaryID string) (CompileResult, error) {
	if c == nil || c.Store == nil || c.Extractor == nil {
		return CompileResult{}, fmt.Errorf("graph compiler: store and extractor are required")
	}
	source, err := c.Store.LoadGroundedSummary(ctx, strings.TrimSpace(summaryID))
	if err != nil {
		return CompileResult{}, err
	}
	if source.ID == "" || source.GroundingStatus != "PASS" || source.CoverageStatus != "PASS" || source.Lifecycle != "ACTIVE" {
		return CompileResult{}, fmt.Errorf("graph compiler: summary %s is not ACTIVE with grounding+coverage PASS", summaryID)
	}
	extracted, err := c.Extractor.Extract(ctx, source)
	if err != nil {
		return CompileResult{}, fmt.Errorf("graph extract: %w", err)
	}

	facts := groundedFacts(source.Facts)
	coverage, validRelations, err := validateExtraction(facts, extracted)
	if err != nil {
		return CompileResult{}, err
	}
	if !coverage.Pass {
		return CompileResult{}, fmt.Errorf("graph coverage failed: uncovered eligible facts: %s", strings.Join(coverage.MissingFacts, ","))
	}

	if c.Projector != nil {
		if err := c.Projector.EnsureSchema(ctx); err != nil {
			return CompileResult{}, fmt.Errorf("graph projector schema: %w", err)
		}
	}

	entityCache := make(map[string]CanonicalEntity)
	var entities []CanonicalEntity
	var relations []RelationFact
	var supports []EvidenceSupport
	for _, proposed := range validRelations {
		sourceEntity, err := c.resolveEntity(ctx, ProposedEntity{Name: proposed.SourceName, Type: proposed.SourceType}, entityCache, &entities)
		if err != nil {
			return CompileResult{}, err
		}
		targetEntity, err := c.resolveEntity(ctx, ProposedEntity{Name: proposed.TargetName, Type: proposed.TargetType}, entityCache, &entities)
		if err != nil {
			return CompileResult{}, err
		}
		if c.Projector != nil {
			if err := c.Projector.UpsertEntity(ctx, sourceEntity); err != nil {
				return CompileResult{}, fmt.Errorf("graph projector source entity %s: %w", sourceEntity.ID, err)
			}
			if err := c.Projector.UpsertEntity(ctx, targetEntity); err != nil {
				return CompileResult{}, fmt.Errorf("graph projector target entity %s: %w", targetEntity.ID, err)
			}
		}
		validFrom, _ := parseOptionalTime(proposed.ValidFrom)
		validTo, _ := parseOptionalTime(proposed.ValidTo)
		relation := RelationFact{
			ID:             relationID(sourceEntity.ID, proposed.RelationType, targetEntity.ID, validFrom),
			SourceEntityID: sourceEntity.ID,
			RelationType:   proposed.RelationType,
			TargetEntityID: targetEntity.ID,
			ValidFrom:      validFrom,
			ValidTo:        validTo,
			ConflictStatus: "NONE",
			Lifecycle:      "ACTIVE",
		}
		stored, err := c.Store.UpsertRelation(ctx, relation)
		if err != nil {
			return CompileResult{}, fmt.Errorf("graph persist relation %s: %w", relation.ID, err)
		}
		relationSupports, err := c.persistSupports(ctx, source.ID, stored.ID, proposed, facts)
		if err != nil {
			return CompileResult{}, err
		}
		if c.Projector != nil {
			if err := c.Projector.UpsertRelation(ctx, stored, relationSupports); err != nil {
				return CompileResult{}, fmt.Errorf("graph project relation %s: %w", stored.ID, err)
			}
		}
		relations = append(relations, stored)
		supports = append(supports, relationSupports...)
	}
	if c.Projector != nil {
		for _, entity := range entities {
			if err := c.Projector.UpsertEntity(ctx, entity); err != nil {
				return CompileResult{}, fmt.Errorf("graph project entity %s: %w", entity.ID, err)
			}
		}
	}

	sort.Slice(entities, func(i, j int) bool { return entities[i].ID < entities[j].ID })
	sort.Slice(relations, func(i, j int) bool { return relations[i].ID < relations[j].ID })
	sort.Slice(supports, func(i, j int) bool { return supports[i].ID < supports[j].ID })
	return CompileResult{
		SummaryID: source.ID,
		Entities:  entities,
		Relations: relations,
		Supports:  supports,
		Coverage:  coverage,
		Extractor: c.Extractor.Identity(),
	}, nil
}

func groundedFacts(facts []enterprisesummary.Fact) map[string]enterprisesummary.Fact {
	out := make(map[string]enterprisesummary.Fact)
	for _, fact := range facts {
		if fact.GroundingStatus == "PASS" && strings.TrimSpace(fact.ID) != "" {
			out[fact.ID] = fact
		}
	}
	return out
}

func validateExtraction(facts map[string]enterprisesummary.Fact, extraction Extraction) (CoverageResult, []ProposedRelation, error) {
	if len(facts) == 0 {
		return CoverageResult{}, nil, fmt.Errorf("graph compiler: no grounded summary facts")
	}
	decisions := make(map[string]FactDecision, len(extraction.FactDecisions))
	for _, decision := range extraction.FactDecisions {
		decision.FactID = strings.TrimSpace(decision.FactID)
		if _, ok := facts[decision.FactID]; !ok {
			return CoverageResult{}, nil, fmt.Errorf("graph coverage: decision references unknown fact %q", decision.FactID)
		}
		if _, duplicate := decisions[decision.FactID]; duplicate {
			return CoverageResult{}, nil, fmt.Errorf("graph coverage: duplicate decision for fact %s", decision.FactID)
		}
		if !decision.GraphEligible && strings.TrimSpace(decision.SkipReason) == "" {
			return CoverageResult{}, nil, fmt.Errorf("graph coverage: non-eligible fact %s requires skip_reason", decision.FactID)
		}
		decisions[decision.FactID] = decision
	}
	for factID := range facts {
		if _, ok := decisions[factID]; !ok {
			return CoverageResult{}, nil, fmt.Errorf("graph coverage: missing decision for fact %s", factID)
		}
	}

	covered := make(map[string]bool)
	valid := make([]ProposedRelation, 0, len(extraction.Relations))
	for _, relation := range extraction.Relations {
		relation = normalizeRelation(relation)
		if err := ValidateRelation(relation); err != nil {
			return CoverageResult{}, nil, err
		}
		seenFact := make(map[string]struct{}, len(relation.SupportingFactIDs))
		for _, factID := range relation.SupportingFactIDs {
			factID = strings.TrimSpace(factID)
			fact, ok := facts[factID]
			if !ok {
				return CoverageResult{}, nil, fmt.Errorf("graph relation %s references unknown fact %s", relation.RelationType, factID)
			}
			decision := decisions[factID]
			if !decision.GraphEligible {
				return CoverageResult{}, nil, fmt.Errorf("graph relation %s cites fact %s marked non-eligible", relation.RelationType, factID)
			}
			if len(fact.EvidenceIDs) == 0 {
				return CoverageResult{}, nil, fmt.Errorf("graph relation %s cites fact %s without raw evidence", relation.RelationType, factID)
			}
			if _, duplicate := seenFact[factID]; !duplicate {
				seenFact[factID] = struct{}{}
				covered[factID] = true
			}
		}
		valid = append(valid, relation)
	}

	coverage := CoverageResult{Pass: true}
	for factID, decision := range decisions {
		if decision.GraphEligible {
			coverage.EligibleFacts++
			if covered[factID] {
				coverage.CoveredFacts++
			} else {
				coverage.Pass = false
				coverage.MissingFacts = append(coverage.MissingFacts, factID)
			}
		} else {
			coverage.SkippedFacts++
		}
	}
	sort.Strings(coverage.MissingFacts)
	return coverage, valid, nil
}

func normalizeRelation(relation ProposedRelation) ProposedRelation {
	relation.SourceName = strings.TrimSpace(relation.SourceName)
	relation.TargetName = strings.TrimSpace(relation.TargetName)
	relation.SourceType = strings.ToUpper(strings.TrimSpace(relation.SourceType))
	relation.TargetType = strings.ToUpper(strings.TrimSpace(relation.TargetType))
	relation.RelationType = strings.ToUpper(strings.TrimSpace(relation.RelationType))
	return relation
}

func (c *Compiler) resolveEntity(ctx context.Context, proposed ProposedEntity, cache map[string]CanonicalEntity, entities *[]CanonicalEntity) (CanonicalEntity, error) {
	proposed.Name = strings.TrimSpace(proposed.Name)
	proposed.Type = strings.ToUpper(strings.TrimSpace(proposed.Type))
	if err := ValidateEntity(proposed); err != nil {
		return CanonicalEntity{}, err
	}
	cacheKey := proposed.Type + "\x00" + CanonicalKey(proposed.Name)
	if entity, ok := cache[cacheKey]; ok {
		return entity, nil
	}
	entity, err := c.Store.ResolveEntity(ctx, proposed)
	if err != nil {
		return CanonicalEntity{}, fmt.Errorf("graph resolve entity %s: %w", proposed.Name, err)
	}
	cache[cacheKey] = entity
	*entities = append(*entities, entity)
	return entity, nil
}

func (c *Compiler) persistSupports(ctx context.Context, summaryID, relationID string, proposed ProposedRelation, facts map[string]enterprisesummary.Fact) ([]EvidenceSupport, error) {
	seen := make(map[string]struct{})
	var out []EvidenceSupport
	for _, factID := range proposed.SupportingFactIDs {
		fact := facts[strings.TrimSpace(factID)]
		for _, evidenceID := range fact.EvidenceIDs {
			evidenceID = strings.TrimSpace(evidenceID)
			if evidenceID == "" {
				continue
			}
			id := supportID(relationID, OriginLLMTriple, evidenceID, summaryID, "", nil)
			if _, ok := seen[id]; ok {
				continue
			}
			seen[id] = struct{}{}
			support := EvidenceSupport{
				ID:         id,
				RelationID: relationID,
				Origin:     OriginLLMTriple,
				EvidenceID: evidenceID,
				SummaryID:  summaryID,
				Confidence: proposed.Confidence,
				Active:     true,
			}
			stored, err := c.Store.AddSupport(ctx, support)
			if err != nil {
				return nil, fmt.Errorf("graph persist support %s: %w", support.ID, err)
			}
			out = append(out, stored)
		}
	}
	return out, nil
}
