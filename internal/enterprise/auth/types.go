package auth

import "time"

type Role string

const (
	RoleAdmin Role = "ADMIN"
	RoleUser  Role = "USER"
)

type User struct {
	UserID      string     `json:"user_id"`
	Username    string     `json:"username"`
	DisplayName string     `json:"display_name"`
	Role        Role       `json:"role"`
	Status      string     `json:"status"`
	LastLoginAt *time.Time `json:"last_login_at,omitempty"`
	CreatedAt   time.Time  `json:"created_at"`
	UpdatedAt   time.Time  `json:"updated_at"`
}

type Status struct {
	SetupRequired bool `json:"setup_required"`
	Users         int  `json:"users"`
}

type LoginResult struct {
	User      User
	Token     string
	ExpiresAt time.Time
}

type CreateUserRequest struct {
	Username    string `json:"username"`
	DisplayName string `json:"display_name"`
	Password    string `json:"password"`
	Role        Role   `json:"role"`
}

type UpdateUserRequest struct {
	DisplayName *string `json:"display_name,omitempty"`
	Role        *Role   `json:"role,omitempty"`
	Status      *string `json:"status,omitempty"`
}
