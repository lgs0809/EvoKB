package wiki

import (
	"context"
	"fmt"
)

type SearchIndexer interface {
	IndexWiki(ctx context.Context, wiki StoredWiki, markdown string) error
}

type Compiler struct {
	Store Store
	Objects ObjectStore
	Generator Generator
	Judge Judge
	Indexer SearchIndexer
}

func (c *Compiler) Compile(ctx context.Context, req CompileRequest) (CompileResult, error) {
	if c == nil || c.Store == nil || c.Generator == nil || c.Judge == nil {
		return CompileResult{}, fmt.Errorf("wiki compiler dependencies missing")
	}
	return c.compile(ctx, req)
}
