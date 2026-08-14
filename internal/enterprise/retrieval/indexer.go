package retrieval

import (
	"context"
	"fmt"
	"sort"
	"strings"

	"github.com/xoai/sage-wiki/internal/enterprise/raw"
	enterprisesummary "github.com/xoai/sage-wiki/internal/enterprise/summary"
	enterprisewiki "github.com/xoai/sage-wiki/internal/enterprise/wiki"
)

type DerivedMetadataStore interface {
	GetVersionContext(ctx context.Context, versionID string) (raw.VersionContext, error)
	GetEvidenceContexts(ctx context.Context, evidenceIDs []string) ([]raw.EvidenceContext, error)
}

type DerivedIndexStore interface {
	EnsureIndex(ctx context.Context, dimensions int) error
	DeactivateGroup(ctx context.Context, knowledgeType, groupID string) error
	IndexDocument(ctx context.Context, doc TextDocument) error
}

type DerivedIndexer struct {
	Store    DerivedIndexStore
	Embedder raw.Embedder
	Metadata DerivedMetadataStore
}

func (i *DerivedIndexer) IndexSummary(ctx context.Context, summary enterprisesummary.StoredSummary, markdown string) error {
	if i == nil || i.Store == nil || i.Embedder == nil || i.Metadata == nil {
		return fmt.Errorf("summary derived indexer dependencies are required")
	}
	if summary.Lifecycle != "ACTIVE" || summary.GroundingStatus != "PASS" || summary.CoverageStatus != "PASS" {
		return fmt.Errorf("summary %s is not eligible for derived indexing", summary.ID)
	}
	if strings.TrimSpace(markdown) == "" {
		return fmt.Errorf("summary %s markdown is empty", summary.ID)
	}
	evidenceIDs := summaryEvidenceIDs(summary)
	if len(evidenceIDs) == 0 {
		return fmt.Errorf("summary %s has no raw evidence", summary.ID)
	}
	version, err := i.Metadata.GetVersionContext(ctx, summary.VersionID)
	if err != nil {
		return err
	}
	embedding, err := i.embedOne(ctx, markdown)
	if err != nil {
		return err
	}
	if err := i.Store.EnsureIndex(ctx, i.Embedder.Dimensions()); err != nil {
		return err
	}
	if err := i.Store.DeactivateGroup(ctx, TypeSummary, summary.UnitID); err != nil {
		return fmt.Errorf("summary deactivate old derived documents: %w", err)
	}
	return i.Store.IndexDocument(ctx, TextDocument{
		ID: summary.ID, GroupID: summary.UnitID, KnowledgeType: TypeSummary,
		DocumentID: version.DocumentID, VersionID: summary.VersionID,
		Content: markdown, ContentHash: summary.ContentHash, EvidenceIDs: evidenceIDs,
		Metadata: version.Metadata, Embedding: embedding, Active: true,
	})
}

func (i *DerivedIndexer) IndexWiki(ctx context.Context, wiki enterprisewiki.StoredWiki, markdown string) error {
	if i == nil || i.Store == nil || i.Embedder == nil || i.Metadata == nil {
		return fmt.Errorf("wiki derived indexer dependencies are required")
	}
	if wiki.Lifecycle != "ACTIVE" {
		return fmt.Errorf("wiki %s v%d is not ACTIVE", wiki.ID, wiki.Version)
	}
	if strings.TrimSpace(markdown) == "" {
		return fmt.Errorf("wiki %s v%d markdown is empty", wiki.ID, wiki.Version)
	}
	evidenceIDs := wikiEvidenceIDs(wiki)
	if len(evidenceIDs) == 0 {
		return fmt.Errorf("wiki %s v%d has no raw evidence", wiki.ID, wiki.Version)
	}
	contexts, err := i.Metadata.GetEvidenceContexts(ctx, evidenceIDs)
	if err != nil {
		return err
	}
	if len(contexts) == 0 {
		return fmt.Errorf("wiki %s v%d has no active raw evidence contexts", wiki.ID, wiki.Version)
	}
	embedding, err := i.embedOne(ctx, markdown)
	if err != nil {
		return err
	}
	if err := i.Store.EnsureIndex(ctx, i.Embedder.Dimensions()); err != nil {
		return err
	}
	if err := i.Store.DeactivateGroup(ctx, TypeWiki, wiki.ID); err != nil {
		return fmt.Errorf("wiki deactivate old derived documents: %w", err)
	}
	docIDs, versionIDs, departments, categories, companies, documentTypes, tags := aggregateEvidenceContexts(contexts)
	return i.Store.IndexDocument(ctx, TextDocument{
		ID: fmt.Sprintf("%s:v%d", wiki.ID, wiki.Version), GroupID: wiki.ID, KnowledgeType: TypeWiki,
		DocumentIDs: docIDs, VersionIDs: versionIDs,
		Content: markdown, ContentHash: wiki.ContentHash, EvidenceIDs: evidenceIDs,
		Metadata: raw.Metadata{ManualTags: tags}, SourceDepartments: departments,
		MaterialCategories: categories, Companies: companies, DocumentTypes: documentTypes,
		Embedding: embedding, Active: true,
	})
}

func (i *DerivedIndexer) embedOne(ctx context.Context, content string) ([]float32, error) {
	vectors, err := i.Embedder.Embed(ctx, []string{content})
	if err != nil {
		return nil, err
	}
	if len(vectors) != 1 || len(vectors[0]) == 0 {
		return nil, fmt.Errorf("derived indexer expected one non-empty embedding")
	}
	return vectors[0], nil
}

func summaryEvidenceIDs(summary enterprisesummary.StoredSummary) []string {
	var ids []string
	for _, fact := range summary.Facts {
		if fact.GroundingStatus == "PASS" {
			ids = append(ids, fact.EvidenceIDs...)
		}
	}
	return uniqueSorted(ids)
}

func wikiEvidenceIDs(wiki enterprisewiki.StoredWiki) []string {
	var ids []string
	for _, claim := range wiki.Claims {
		if claim.GroundingStatus == "PASS" {
			ids = append(ids, claim.EvidenceIDs...)
		}
	}
	return uniqueSorted(ids)
}

func aggregateEvidenceContexts(contexts []raw.EvidenceContext) (docIDs, versionIDs, departments, categories, companies, documentTypes, tags []string) {
	for _, item := range contexts {
		docIDs = append(docIDs, item.DocumentID)
		versionIDs = append(versionIDs, item.VersionID)
		departments = append(departments, item.Metadata.SourceDepartment)
		categories = append(categories, item.Metadata.MaterialCategory)
		companies = append(companies, item.Metadata.Company)
		documentTypes = append(documentTypes, item.Metadata.DocumentType)
		tags = append(tags, item.Metadata.ManualTags...)
	}
	return uniqueSorted(docIDs), uniqueSorted(versionIDs), uniqueSorted(departments), uniqueSorted(categories), uniqueSorted(companies), uniqueSorted(documentTypes), uniqueSorted(tags)
}

func uniqueSorted(values []string) []string {
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
