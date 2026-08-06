-- HTTP protocol session IDs are not bearer credentials and cannot cross accounts.
CREATE TABLE mcp_session_access (
  session_id VARCHAR(255) PRIMARY KEY,
  user_id VARCHAR(40) NOT NULL REFERENCES enterprise_user(user_id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX mcp_session_access_expiry ON mcp_session_access(expires_at);
