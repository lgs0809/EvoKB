package graph

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"strings"
	"time"

	"golang.org/x/text/unicode/norm"
)

type RelationRule struct {
	SourceTypes map[string]struct{}
	TargetTypes map[string]struct{}
	Symmetric   bool
}

var allowedEntityTypes = map[string]struct{}{
	EntityCompany: {}, EntityPerson: {}, EntityContract: {}, EntityLegalCase: {},
	EntityProject: {}, EntityOrganization: {}, EntityAgency: {}, EntityProduct: {},
	EntityLocation: {}, EntityLicense: {}, EntityIP: {}, EntityPolicy: {}, EntityEvent: {},
	EntityTopic: {}, EntityGeneric: {},
}

var relationRules = map[string]RelationRule{
	RelationActualControllerOf: rule(types(EntityPerson, EntityCompany, EntityOrganization), types(EntityCompany)),
	RelationControls:           rule(types(EntityPerson, EntityCompany, EntityOrganization), types(EntityCompany, EntityOrganization)),
	RelationShareholderOf:      rule(types(EntityPerson, EntityCompany, EntityOrganization), types(EntityCompany)),
	RelationSubsidiaryOf:       rule(types(EntityCompany, EntityOrganization), types(EntityCompany, EntityOrganization)),
	RelationParentOf:           rule(types(EntityCompany, EntityOrganization), types(EntityCompany, EntityOrganization)),
	RelationDirectorOf:         rule(types(EntityPerson), types(EntityCompany, EntityOrganization)),
	RelationLegalRepOf:         rule(types(EntityPerson), types(EntityCompany, EntityOrganization)),
	RelationPartyToContract:    rule(types(EntityPerson, EntityCompany, EntityOrganization, EntityAgency), types(EntityContract)),
	RelationSignsWith:          rule(types(EntityCompany, EntityOrganization, EntityPerson, EntityAgency), types(EntityCompany, EntityOrganization, EntityPerson, EntityAgency)),
	RelationGuarantees:         rule(types(EntityCompany, EntityOrganization, EntityPerson), types(EntityCompany, EntityOrganization, EntityPerson)),
	RelationBorrowsFrom:        rule(types(EntityCompany, EntityOrganization, EntityPerson), types(EntityCompany, EntityOrganization, EntityAgency)),
	RelationInvestsIn:          rule(types(EntityCompany, EntityOrganization, EntityPerson), types(EntityCompany, EntityOrganization, EntityProject)),
	RelationInvolvedInCase:     rule(types(EntityCompany, EntityOrganization, EntityPerson, EntityAgency), types(EntityLegalCase)),
	RelationOwnsIP:             rule(types(EntityCompany, EntityOrganization, EntityPerson), types(EntityIP)),
	RelationHoldsLicense:       rule(types(EntityCompany, EntityOrganization, EntityPerson), types(EntityLicense)),
	RelationParticipatesIn:     rule(types(EntityCompany, EntityOrganization, EntityPerson, EntityAgency), types(EntityProject)),
	RelationApproves:           rule(types(EntityCompany, EntityOrganization, EntityAgency, EntityPerson), types(EntityProject, EntityContract, EntityPolicy, EntityEvent)),
	RelationSuppliesTo:         rule(types(EntityCompany, EntityOrganization), types(EntityCompany, EntityOrganization)),
	RelationCustomerOf:         rule(types(EntityCompany, EntityOrganization, EntityPerson), types(EntityCompany, EntityOrganization)),
	RelationRelatedTo:          {SourceTypes: allowedEntityTypes, TargetTypes: allowedEntityTypes, Symmetric: true},
}

func rule(source, target map[string]struct{}) RelationRule {
	return RelationRule{SourceTypes: source, TargetTypes: target}
}

func types(values ...string) map[string]struct{} {
	m := make(map[string]struct{}, len(values))
	for _, value := range values {
		m[value] = struct{}{}
	}
	return m
}

func AllowedEntityTypes() []string {
	out := make([]string, 0, len(allowedEntityTypes))
	for value := range allowedEntityTypes {
		out = append(out, value)
	}
	return out
}

func AllowedRelationTypes() []string {
	out := make([]string, 0, len(relationRules))
	for value := range relationRules {
		out = append(out, value)
	}
	return out
}

func ValidateEntity(entity ProposedEntity) error {
	entity.Name = strings.TrimSpace(entity.Name)
	entity.Type = strings.ToUpper(strings.TrimSpace(entity.Type))
	if entity.Name == "" {
		return fmt.Errorf("graph entity: empty name")
	}
	if _, ok := allowedEntityTypes[entity.Type]; !ok {
		return fmt.Errorf("graph entity %q: unsupported type %q", entity.Name, entity.Type)
	}
	return nil
}

func ValidateRelation(relation ProposedRelation) error {
	relation.SourceName = strings.TrimSpace(relation.SourceName)
	relation.TargetName = strings.TrimSpace(relation.TargetName)
	relation.SourceType = strings.ToUpper(strings.TrimSpace(relation.SourceType))
	relation.TargetType = strings.ToUpper(strings.TrimSpace(relation.TargetType))
	relation.RelationType = strings.ToUpper(strings.TrimSpace(relation.RelationType))
	if relation.SourceName == "" || relation.TargetName == "" {
		return fmt.Errorf("graph relation: source and target names are required")
	}
	if relation.SourceName == relation.TargetName && relation.SourceType == relation.TargetType {
		return fmt.Errorf("graph relation: self relation is not allowed")
	}
	rule, ok := relationRules[relation.RelationType]
	if !ok {
		return fmt.Errorf("graph relation: unsupported relation type %q", relation.RelationType)
	}
	if _, ok := rule.SourceTypes[relation.SourceType]; !ok {
		return fmt.Errorf("graph relation %s: source type %s is not allowed", relation.RelationType, relation.SourceType)
	}
	if _, ok := rule.TargetTypes[relation.TargetType]; !ok {
		return fmt.Errorf("graph relation %s: target type %s is not allowed", relation.RelationType, relation.TargetType)
	}
	if len(relation.SupportingFactIDs) == 0 {
		return fmt.Errorf("graph relation %s: at least one supporting fact is required", relation.RelationType)
	}
	if relation.Confidence < 0 || relation.Confidence > 1 {
		return fmt.Errorf("graph relation %s: confidence must be within [0,1]", relation.RelationType)
	}
	if _, err := parseOptionalTime(relation.ValidFrom); err != nil {
		return fmt.Errorf("graph relation %s valid_from: %w", relation.RelationType, err)
	}
	if _, err := parseOptionalTime(relation.ValidTo); err != nil {
		return fmt.Errorf("graph relation %s valid_to: %w", relation.RelationType, err)
	}
	return nil
}

func CanonicalKey(name string) string {
	name = norm.NFKC.String(strings.TrimSpace(name))
	name = strings.Join(strings.Fields(name), " ")
	return strings.ToLower(name)
}

func relationID(sourceID, relationType, targetID string, validFrom *time.Time) string {
	valid := ""
	if validFrom != nil {
		valid = validFrom.UTC().Format(time.RFC3339Nano)
	}
	return stableID("relation", sourceID, relationType, targetID, valid)
}

func supportID(relationID, origin, evidenceID, summaryID, wikiID string, wikiVersion *uint64) string {
	version := ""
	if wikiVersion != nil {
		version = fmt.Sprintf("%d", *wikiVersion)
	}
	return stableID("support", relationID, origin, evidenceID, summaryID, wikiID, version)
}

func stableID(parts ...string) string {
	h := sha256.Sum256([]byte(strings.Join(parts, "\x00")))
	encoded := hex.EncodeToString(h[:16])
	return fmt.Sprintf("%s-%s-%s-%s-%s", encoded[0:8], encoded[8:12], encoded[12:16], encoded[16:20], encoded[20:32])
}

func parseOptionalTime(value string) (*time.Time, error) {
	value = strings.TrimSpace(value)
	if value == "" {
		return nil, nil
	}
	for _, layout := range []string{time.RFC3339Nano, time.RFC3339, "2006-01-02"} {
		if parsed, err := time.Parse(layout, value); err == nil {
			parsed = parsed.UTC()
			return &parsed, nil
		}
	}
	return nil, fmt.Errorf("unsupported date/time %q", value)
}
