package graph

import "testing"

func TestParseWikiKeywordClaimForwardAndReverse(t *testing.T) {
	forward, reason := parseWikiKeywordClaim("[[HengAn JiaXin]] signed with [[Harbin Example]].")
	if reason != "" {
		t.Fatalf("forward reason=%q", reason)
	}
	if forward.SourceName != "HengAn JiaXin" || forward.RelationType != RelationSignsWith || forward.TargetName != "Harbin Example" {
		t.Fatalf("unexpected forward parse: %#v", forward)
	}

	reverse, reason := parseWikiKeywordClaim("[[Subsidiary]] is controlled by [[Parent]].")
	if reason != "" {
		t.Fatalf("reverse reason=%q", reason)
	}
	if reverse.SourceName != "Parent" || reverse.RelationType != RelationControls || reverse.TargetName != "Subsidiary" {
		t.Fatalf("unexpected reverse parse: %#v", reverse)
	}
}

func TestParseWikiKeywordClaimRejectsNegationAndAmbiguousLinks(t *testing.T) {
	if _, reason := parseWikiKeywordClaim("[[A]] does not control [[B]]."); reason != "negated claim" {
		t.Fatalf("negation reason=%q", reason)
	}
	if _, reason := parseWikiKeywordClaim("[[A]] signed with [[B]] and [[C]]."); reason != "claim must contain exactly two wikilinks" {
		t.Fatalf("ambiguous link reason=%q", reason)
	}
}

func TestWikiLinkNameUsesCanonicalTargetBeforeDisplayLabel(t *testing.T) {
	if got := wikiLinkName("Canonical Entity|Display Name"); got != "Canonical Entity" {
		t.Fatalf("wiki link target=%q", got)
	}
}
