package main

import (
	"context"
	"flag"
	"strings"

	enterpriseevaluation "github.com/xoai/sage-wiki/internal/enterprise/evaluation"
)

func runArtifactEval(ctx context.Context, args []string) {
	fs := flag.NewFlagSet("artifact-eval", flag.ExitOnError)
	casesPath := fs.String("cases", "", "JSONL artifact Golden cases")
	_ = fs.Parse(args)
	if strings.TrimSpace(*casesPath) == "" {
		fatalf("artifact-eval: --cases is required")
	}

	cases, err := enterpriseevaluation.LoadGoldenJSONL(*casesPath)
	if err != nil {
		fatalf("artifact-eval cases: %v", err)
	}
	if len(cases) == 0 {
		fatalf("artifact-eval: no cases loaded")
	}
	store, err := enterpriseevaluation.OpenMySQL(ctx, mustEnv("EKB_MYSQL_DSN"))
	if err != nil {
		fatalf("artifact-eval mysql: %v", err)
	}
	defer store.Close()

	results := make([]enterpriseevaluation.ArtifactCaseResult, 0, len(cases))
	for _, item := range cases {
		actual, err := store.Load(ctx, item.VersionIDs)
		if err != nil {
			fatalf("artifact-eval %s: %v", item.ID, err)
		}
		results = append(results, enterpriseevaluation.EvaluateCase(item, actual))
	}
	printJSON(enterpriseevaluation.BuildReport(results))
}
