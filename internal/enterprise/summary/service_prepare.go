package summary

import (
	"context"
	"fmt"
)

type PreparedUnit struct {
	Source DocumentUnit
	Result CompileResult
}

type Service struct {
	Store     *MySQLStore
	Generator CompilerModel
	Judge     QualityJudge
}

// PrepareVersion compiles one already-ACTIVE Raw version entirely from the
// persistent fact layer. It never re-runs MinerU and does not publish anything.
func (s *Service) PrepareVersion(ctx context.Context, versionID string) ([]PreparedUnit, error) {
	if s == nil || s.Store == nil || s.Generator == nil || s.Judge == nil {
		return nil, fmt.Errorf("summary service: adapters are incomplete")
	}
	src, err := s.Store.LoadActiveSource(ctx, versionID)
	if err != nil {
		return nil, err
	}
	if len(src.Blocks) == 0 || len(src.Evidence) == 0 {
		return nil, fmt.Errorf("summary service: active version %s has incomplete raw evidence", versionID)
	}
	classification, err := s.Generator.Classify(ctx, src.Blocks, src.ManualDocumentTypeOverride)
	if err != nil {
		return nil, fmt.Errorf("summary classify version: %w", err)
	}
	units, err := NormalizeClassification(src.VersionID, src.ManualDocumentTypeOverride, src.Blocks, classification)
	if err != nil {
		return nil, err
	}
	out := make([]PreparedUnit, 0, len(units))
	for _, unit := range units {
		unitSource, err := UnitSourceFromRaw(unit, src.Blocks, src.Evidence)
		if err != nil {
			return out, err
		}
		result, err := CompileUnit(ctx, unitSource, s.Generator, s.Judge)
		if err != nil {
			return out, err
		}
		out = append(out, PreparedUnit{Source: unit, Result: result})
	}
	return out, nil
}
