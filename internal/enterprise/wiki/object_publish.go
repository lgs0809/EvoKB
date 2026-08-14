package wiki

import (
	"context"
	"strings"
)

func putWikiObject(ctx context.Context, objects ObjectStore, key, markdown string) error {
	content := []byte(markdown)
	return objects.Put(ctx, key, strings.NewReader(markdown), int64(len(content)), "text/markdown; charset=utf-8")
}
