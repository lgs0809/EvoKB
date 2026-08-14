package api

import (
	enterpriseevidence "github.com/xoai/sage-wiki/internal/enterprise/evidence"
	"github.com/xoai/sage-wiki/internal/enterprise/retrieval"
)

type Service struct {
	SearchEngine  *retrieval.Service
	SourcePackager *enterpriseevidence.Packager
}
