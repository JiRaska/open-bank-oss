# 5. OpenAPI 3.1 design-first for all external APIs

Date: 2026-05-26
Status: Accepted

## Context

API contract drift between server implementation and documentation is universal. Customers and TPPs receive stale docs; PSD2 conformance audits fail because the documented spec does not match runtime behaviour.

Two approaches exist:

- **Code-first**: write the implementation, generate the spec from annotations.
- **Design-first**: write the OpenAPI spec, generate server stubs and client SDKs from it.

Code-first looks easier but consistently produces incomplete specs and incentivises ad-hoc additions. Design-first front-loads design rigour, produces consistent specs, and enables parallel client + server work.

## Decision

Every externally-exposed REST API MUST be **design-first**:

- The OpenAPI 3.1 spec lives in `openbank-contracts/<service>/openapi.yaml`.
- Server stubs (DTOs, resource interfaces) are generated from the spec via openapi-generator at build time.
- Client SDKs (Kotlin, TypeScript) are generated and published from the same spec.
- The CI lint stage uses Spectral with a custom ruleset enforcing project conventions (BIAN operation patterns, error schema, security schemes, examples).
- Breaking changes to a spec require a new version path (`/v2/...`) and a deprecation timeline for the old version.

Internal east-west APIs (service-to-service within the cluster) may use code-first if the consumer is in the same repo and there is no external observer. Cross-service contracts should still be specced when teams are different.

Kafka topics are documented analogously via AsyncAPI 3.0 — see ADR-0006.

## Consequences

**Positive**
- Spec and implementation cannot drift; CI fails if they do.
- Client SDKs free.
- PSD2 / TPP integrators get authoritative documentation.
- Better API design — writing the spec first forces clarity.

**Negative**
- Adds friction for fast experimentation.
- Generated code may be verbose.
- Build-time code generation needs to be reliable.

**Mitigation**
- Allow `application/x-experimental+json` content type for early prototyping; promote to spec when stabilised.
- Pin openapi-generator version; pre-build CI caches.

## References

- OpenAPI Specification 3.1
- Stoplight Spectral
- EBA PSD2 conformance tools expect OpenAPI-described endpoints.
