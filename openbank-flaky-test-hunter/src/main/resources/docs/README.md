# Flaky Test Hunter API

Internal development-plane agent API.

## Interface

The committed `src/main/resources/openapi.yaml` defines the API contract. Read `/q/openapi` on the running service for the exact paths, request bodies, responses, and contract version of its image.

## GitHub proposal egress

The proposal adapter uses `SafeHttpClient` for its configurable GitHub API URL. `openbank.egress.allowed-hosts` defaults to `api.github.com`; explicitly allow the hostname when using a different GitHub API. DNS answers are checked before a connection to a pinned public address; TLS still verifies the original hostname, and redirects are refused. An unlisted host produces no outbound request.

## Build identity

The generated `00-build` page in this same documentation index reports the release version, full source commit, and API contract version packaged in the running image. The index is available at `/q/openbank/docs`.
