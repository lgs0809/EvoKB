package main

import (
	"os"
	"strconv"
	"strings"
)

func envBool(name string, fallback bool) bool {
	value := strings.TrimSpace(os.Getenv(name))
	if value == "" {
		return fallback
	}
	parsed, err := strconv.ParseBool(value)
	if err != nil {
		fatalf("invalid boolean %s=%q", name, value)
	}
	return parsed
}
