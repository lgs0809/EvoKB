package raw

import (
	"context"
	"net/http"
	"net/url"
)

// DeleteVersion removes every derived Raw search document for a document
// version before retrying that same version ID. This prevents stale chunk IDs
// from an earlier failed attempt becoming active if parser/chunk output changes.
func (s *OpenSearchStore) DeleteVersion(ctx context.Context, versionID string) error {
	body := map[string]any{"query": map[string]any{"term": map[string]any{"version_id": versionID}}}
	_, err := s.doJSON(ctx, http.MethodPost, "/"+url.PathEscape(s.Index)+"/_delete_by_query?refresh=true&conflicts=proceed", body)
	return err
}
