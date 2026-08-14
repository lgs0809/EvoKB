package main

import (
	"context"
	"strings"
	"time"

	enterpriseapi "github.com/xoai/sage-wiki/internal/enterprise/api"
	enterpriseask "github.com/xoai/sage-wiki/internal/enterprise/ask"
	"github.com/xoai/sage-wiki/internal/llm"
)

func openAskService(ctx context.Context, search *enterpriseapi.Service) *enterpriseask.Service {
	_ = ctx
	if search == nil {
		return nil
	}
	service := &enterpriseask.Service{Search: search, MaxChars: envPositiveInt("EKB_ASK_MAX_CONTEXT_CHARS", 24000)}
	model := strings.TrimSpace(firstEnv("EKB_ASK_MODEL", "EKB_LLM_MODEL"))
	baseURL := strings.TrimSpace(firstEnv("EKB_ASK_BASE_URL", "EKB_LLM_BASE_URL"))
	if model == "" || baseURL == "" {
		// Keep Ask available in evidence-only mode until an answer model is explicitly configured.
		return service
	}
	provider := firstEnvOr("openai", "EKB_ASK_PROVIDER", "EKB_LLM_PROVIDER")
	client, err := llm.NewClient(
		provider,
		firstEnv("EKB_ASK_API_KEY", "EKB_LLM_API_KEY"),
		baseURL,
		-1,
		llmExtraParams("EKB_ASK_REASONING_EFFORT", "EKB_LLM_REASONING_EFFORT"),
	)
	if err != nil {
		return nil
	}
	client.SetCallTimeout(time.Duration(envPositiveInt("EKB_ASK_TIMEOUT_SECONDS", 180)) * time.Second)
	service.Answerer = &enterpriseask.LLMAnswerer{Client: client, Model: model, MaxTokens: envPositiveInt("EKB_ASK_MAX_TOKENS", 1200)}
	return service
}
