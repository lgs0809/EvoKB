package auth

import (
	"strings"
	"testing"
)

func TestValidateCreateUser(t *testing.T) {
	validSecret := strings.Repeat("x", 12)
	if err := validateCreateUser(CreateUserRequest{Username: "reader.user", Password: validSecret, Role: RoleUser}); err != nil {
		t.Fatalf("valid user rejected: %v", err)
	}
	if err := validateCreateUser(CreateUserRequest{Username: "x", Password: validSecret, Role: RoleUser}); err == nil {
		t.Fatal("expected short username to be rejected")
	}
	if err := validateCreateUser(CreateUserRequest{Username: "reader", Password: "short", Role: RoleUser}); err == nil {
		t.Fatal("expected short password to be rejected")
	}
}

func TestSessionHashIsOneWayRepresentation(t *testing.T) {
	value, hashed, err := newSessionToken()
	if err != nil {
		t.Fatal(err)
	}
	if value == "" || hashed == "" || value == hashed {
		t.Fatal("session value and stored hash must be different non-empty values")
	}
	if hashToken(value) != hashed {
		t.Fatal("session hash is not deterministic")
	}
	if len(hashed) != 64 {
		t.Fatalf("hash length=%d want=64", len(hashed))
	}
}
