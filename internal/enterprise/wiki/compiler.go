package wiki

import (
	"context"
	"fmt"
	"strings"
)

type SearchIndexer interface {
	IndexWiki(ctx context.Context, wiki StoredWiki, markdown string) error
}

type Compiler struct {
	Store     Store
	Objects   ObjectStore
	Generator Generator
	Judge     Judge
	Indexer   SearchIndexer
}

func (c *Compiler) Compile(ctx context.Context, req CompileRequest) (CompileResult, error) {
	if c == nil || c.Store == nil || c.Generator == nil || c.Judge == nil {
		return CompileResult{}, fmt.Errorf("wiki compiler dependencies missing")
	}
	writerIdentity := strings.TrimSpace(c.Generator.Identity())
	judgeIdentity := strings.TrimSpace(c.Judge.Identity())
	if writerIdentity == "" || judgeIdentity == "" {
		return CompileResult{}, fmt.Errorf("wiki compiler writer and judge identities are required")
	}
	if writerIdentity == judgeIdentity {
		return CompileResult{}, fmt.Errorf("wiki compiler writer and judge must be independent")
	}
	return c.compile(ctx, req)
}
