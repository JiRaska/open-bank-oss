# openbank-devops-agent

Runs development-operations analysis and records reviewable findings.

## Interface

This module has no committed OpenAPI contract. Its endpoints and scheduled workflows are internal; consult the source and the service runbook for their current behavior.

## GitHub API egress

The metrics and remediation adapters use `SafeHttpClient` for the configured GitHub API URL. `openbank.egress.allowed-hosts` defaults to `api.github.com`; deployments using a different GitHub API host must explicitly list that hostname. The client checks resolved addresses and connects to a pinned public address, keeps the hostname for TLS verification, and does not follow redirects. A refused request does not justify adding an internal address to the allowlist.

## Build identity

The generated `00-build` page in this same documentation index reports the release version, full source commit, and API contract version packaged in the running image. The index is available at `/q/openbank/docs`.
