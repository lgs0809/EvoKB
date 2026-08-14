package evaluation

// TextExpectation defines one required fact or claim. Alternatives are accepted
// as exact normalized equivalents so Golden files can enumerate approved
// paraphrases without requiring an LLM judge during evaluation.
type TextExpectation struct {
	Text         string   `json:"text"`
	Alternatives []string `json:"alternatives,omitempty"`
}

// RelationExpectation defines one expected canonical graph relation.
type RelationExpectation struct {
	Source              string   `json:"source"`
	RelationType        string   `json:"relation_type"`
	Target              string   `json:"target"`
	SourceAliases       []string `json:"source_aliases,omitempty"`
	RelationTypeAliases []string `json:"relation_type_aliases,omitempty"`
	TargetAliases       []string `json:"target_aliases,omitempty"`
}

// EntityMappingExpectation defines one expected source surface -> canonical
// entity resolution decision.
type EntityMappingExpectation struct {
	SurfaceForm      string   `json:"surface_form"`
	EntityType       string   `json:"entity_type"`
	CanonicalName    string   `json:"canonical_name"`
	SurfaceAliases   []string `json:"surface_aliases,omitempty"`
	CanonicalAliases []string `json:"canonical_aliases,omitempty"`
}

// ArtifactGoldenCase scopes expected compiled artifacts to one or more exact
// Document Versions. Golden cases contain expectations only; actual artifacts
// are always loaded from the persisted enterprise stores.
type ArtifactGoldenCase struct {
	ID                     string                     `json:"id"`
	VersionIDs             []string                   `json:"version_ids"`
	ExpectedSummaryFacts   []TextExpectation          `json:"expected_summary_facts,omitempty"`
	ExpectedRelations      []RelationExpectation      `json:"expected_relations,omitempty"`
	ExpectedEntityMappings []EntityMappingExpectation `json:"expected_entity_mappings,omitempty"`
	ExpectedWikiClaims     []TextExpectation          `json:"expected_wiki_claims,omitempty"`
}

type RelationActual struct {
	Source       string `json:"source"`
	RelationType string `json:"relation_type"`
	Target       string `json:"target"`
}

type EntityMappingActual struct {
	SurfaceForm   string `json:"surface_form"`
	EntityType    string `json:"entity_type"`
	CanonicalName string `json:"canonical_name"`
}

type WikiClaimActual struct {
	Text     string `json:"text"`
	Grounded bool   `json:"grounded"`
}

type ActualArtifacts struct {
	SummaryFacts   []string              `json:"summary_facts,omitempty"`
	Relations      []RelationActual      `json:"relations,omitempty"`
	EntityMappings []EntityMappingActual `json:"entity_mappings,omitempty"`
	WikiClaims     []WikiClaimActual     `json:"wiki_claims,omitempty"`
}

// PrecisionRecall uses pointers for the numeric values so unavailable metrics
// serialize as null rather than accidentally looking like a real zero score.
type PrecisionRecall struct {
	Available bool     `json:"available"`
	Precision *float64 `json:"precision"`
	Recall    *float64 `json:"recall"`
	Matched   int      `json:"matched"`
	Actual    int      `json:"actual"`
	Expected  int      `json:"expected"`
}

type Accuracy struct {
	Available bool     `json:"available"`
	Accuracy  *float64 `json:"accuracy"`
	Matched   int      `json:"matched"`
	Expected  int      `json:"expected"`
}

type Rate struct {
	Available  bool     `json:"available"`
	Rate       *float64 `json:"rate"`
	Passing    int      `json:"passing"`
	Population int      `json:"population"`
}

type ArtifactCaseResult struct {
	ID                 string          `json:"id"`
	VersionIDs         []string        `json:"version_ids"`
	SummaryFact        PrecisionRecall `json:"summary_fact"`
	GraphRelation      PrecisionRecall `json:"graph_relation"`
	EntityResolution   Accuracy        `json:"entity_resolution"`
	WikiClaim          PrecisionRecall `json:"wiki_claim"`
	WikiClaimGrounding Rate            `json:"wiki_claim_grounding"`
}

type ArtifactEvalReport struct {
	Cases                    int                  `json:"cases"`
	SummaryFact              PrecisionRecall      `json:"summary_fact"`
	GraphRelation            PrecisionRecall      `json:"graph_relation"`
	EntityResolution         Accuracy             `json:"entity_resolution"`
	WikiClaim                PrecisionRecall      `json:"wiki_claim"`
	WikiClaimGrounding       Rate                 `json:"wiki_claim_grounding"`
	TaskSuccessAvailable     bool                 `json:"task_success_available"`
	RetrievalRoundsAvailable bool                 `json:"retrieval_rounds_available"`
	Results                  []ArtifactCaseResult `json:"results"`
}
