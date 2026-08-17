# Security Policy

Enterprise Knowledge Base does not ship default credentials and does not require secrets to be committed to the repository.

For deployment hardening, secret handling, network exposure, upload limits and data-layer guidance, see [docs/SECURITY.md](docs/SECURITY.md).

## Reporting a vulnerability

If the public repository has private vulnerability reporting enabled, use that channel for security-sensitive reports. Do not include real credentials, private documents or exploit details in a public issue.

If a credential is accidentally committed, revoke or rotate it first. Removing it from the latest commit alone does not make an already-published credential safe.
