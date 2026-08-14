package graph

import "testing"

func TestCanonicalKey(t *testing.T) {
	got := CanonicalKey("  ＡＣＭＥ   Holdings Co., Ltd.  ")
	if got != "acme holdings co., ltd." {
		t.Fatalf("got %q", got)
	}
}

func TestRelationIDStable(t *testing.T) {
	a := relationID("a", RelationControls, "b", nil)
	b := relationID("a", RelationControls, "b", nil)
	if a != b || a == "" {
		t.Fatalf("ids differ: %q %q", a, b)
	}
}
