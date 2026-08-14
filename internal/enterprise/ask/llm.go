package ask

import (
	"context"
	"errors"
	"fmt"
	"strings"

	"github.com/xoai/sage-wiki/internal/llm"
)

type LLMAnswerer struct {
	Client    *llm.Client
	Model     string
	MaxTokens int
}

func (a *LLMAnswerer) Answer(ctx context.Context, question, groundedContext string) (string, error) {
	if a == nil || a.Client == nil || strings.TrimSpace(a.Model) == "" {
		return "", fmt.Errorf("ask answer model is not configured")
	}
	maxTokens := a.MaxTokens
	if maxTokens <= 0 {
		maxTokens = 1200
	}
	temperature := float64(0)
	response, err := a.Client.ChatCompletionCtx(ctx, []llm.Message{
		{Role: "system", Content: `You are an enterprise knowledge assistant. Answer only from the supplied evidence-backed context.
Rules:
1. Never invent facts, dates, amounts, people, entities, or relationships that are not supported below.
2. Every factual sentence or bullet that relies on a source must include one or more citations like [1] or [2].
3. Citation numbers may only refer to SOURCE numbers supplied in context.
4. If the evidence is incomplete or conflicting, explicitly say what is known, what is uncertain, and what additional evidence is needed.
5. Do not expose hidden reasoning. Be concise and useful.`},
		{Role: "user", Content: "Question:\n" + question + "\n\nEvidence-backed context:\n" + groundedContext},
	}, llm.CallOpts{Model: a.Model, MaxTokens: maxTokens, Temperature: &temperature})
	if err != nil {
		return "", err
	}
	if strings.TrimSpace(response.Content) == "" {
		return "", errors.New(response.EmptyContentDetails())
	}
	return response.Content, nil
}
