package main

import (
	"os"
	"strconv"
	"strings"
)

func firstEnv(names ...string) string {
	for _, name := range names {
		if value := strings.TrimSpace(os.Getenv(name)); value != "" {
			return value
		}
	}
	return ""
}

func mustFirstEnv(names ...string) string {
	if value := firstEnv(names...); value != "" {
		return value
	}
	fatalf("required environment variable is empty; set one of: %s", strings.Join(names, ", "))
	return ""
}

func envPositiveInt(name string, fallback int) int {
	value := strings.TrimSpace(os.Getenv(name))
	if value == "" {
		return fallback
	}
	parsed, err := strconv.Atoi(value)
	if err != nil || parsed <= 0 {
		fatalf("invalid positive integer %s=%q", name, value)
	}
	return parsed
}
