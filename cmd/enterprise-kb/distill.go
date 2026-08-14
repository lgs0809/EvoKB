package main

import (
	"context"
	"flag"
	"strings"

	enterprisepromotion "github.com/xoai/sage-wiki/internal/enterprise/promotion"
)

func runDistill(ctx context.Context, args []string) {
	fs := flag.NewFlagSet("distill", flag.ExitOnError)
	versionID := fs.String("version", "", "ACTIVE document version ID")
	reason := fs.String("reason", "user", "recommendation reason: user or existing-question")
	_ = fs.Parse(args)
	if strings.TrimSpace(*versionID) == "" {
		fatalf("distill: --version is required")
	}
	store, err := enterprisepromotion.OpenMySQL(ctx, mustEnv("EKB_MYSQL_DSN"), envPositiveInt("EKB_DISTILL_EFFECTIVE_HIT_THRESHOLD", 3))
	if err != nil {
		fatalf("distill mysql: %v", err)
	}
	defer store.Close()
	switch strings.ToLower(strings.TrimSpace(*reason)) {
	case "user", "manual", "user-marked":
		err = store.RecommendManual(ctx, *versionID)
	case "existing-question", "existing", "question":
		err = store.RecommendExistingQuestion(ctx, *versionID)
	default:
		fatalf("distill: unsupported --reason %q", *reason)
	}
	if err != nil {
		fatalf("distill recommend: %v", err)
	}
	printJSON(map[string]any{"version_id": *versionID, "reason": *reason, "status": "RECOMMENDED"})
}
