package wiki

import "context"

func (s *MySQLStore) NextWikiVersion(ctx context.Context, wikiID string) (uint64, error) {
	var next uint64
	err := s.db.QueryRowContext(ctx, `SELECT COALESCE(MAX(wiki_version),0)+1 FROM wiki WHERE wiki_id=?`, wikiID).Scan(&next)
	return next, err
}
