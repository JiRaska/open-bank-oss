# Operations

## Build

```
./gradlew :openbank-agent-service:build           # compile + test
./gradlew detekt ktlintCheck koverVerify build     # the local gate before a PR
```

- **Packaging:** fast-jar (never uber-jar — the runtime stage COPYs `quarkus-app/`). Generic build/push: `openbank-infra/scripts/build-push-service.sh openbank-agent-service` (host-side `quarkusBuild`, not in-Docker Gradle).
- **Image:** see [`Dockerfile`](../../../../openbank-agent-service/Dockerfile) — fast-jar runtime stage.
- **DB:** Flyway migrations `V1`–`V6` apply at startup (proposal queue, kill-switch, audit outbox, merge records — see [04 — Data](./04-data.md)); the usual Flyway checksum/repair runbooks apply like for any JDBC service.

## Configuration (environment)

| Variable | Default | Purpose |
|---|---|---|
| `OIDC_CLIENT_SECRET` | `CHANGE_ME_LOCAL_DEV_ONLY` | secret for the `openbank-services` client (resource server + outbound client-credentials) |
| `QUARKUS_OIDC_AUTH_SERVER_URL` | `http://localhost:8080/realms/openbank` | Keycloak realm issuer |
| `OIDC_TLS_VERIFICATION` | `required` | `%dev` relaxes it to `none` for a local plain-http Keycloak |
| `AGENT_DEFAULT_MODEL` | `mock-echo` | gateway default model id |
| `AGENT_MODEL_ENDPOINT` | `http://litellm.ai-platform.svc:4000/v1` | base URL of the `openai-compat` backend. Defaults to the in-cluster LiteLLM gateway, the only workload permitted to egress to a hosted LLM provider (ADR-0174/0175) — the same value the deployment sets. The registered model ids are gateway `model_name`s, not provider ids, so a direct-to-provider base URL does not serve them (#5736) |
| `AGENT_MODEL_API_KEY` | *(falls back to `GROQ_API_KEY`)* | key for the `openai-compat` backend. Deployed, this is the LiteLLM **virtual** key, not a provider key (OpenBao/ESO — never in git) |
| `GROQ_API_KEY` | *(empty)* | legacy/local-dev fallback for the above when talking to Groq directly |
| `AGENT_POLICY_ENFORCEMENT` | `advisory` | `advisory` (audit-only) or `block` (enforce DENY) — ADR-0031 D9 |
| `BUILD_TIME`, `GIT_COMMIT` | `unknown` | provenance for `/api/v1/info` |

The committed config ships only the **offline `mock-echo`** provider, so build and tests need no network or API key. Real backends are added as `model-gateway.models` entries with no code change.

## Ports

| Port | Purpose |
|---|---|
| 8109 | application HTTP (`/mcp`, `/agent/*`, `/api/v1/agent-merge-records`) |
| 8085 | management — `/q/health/*`, `/q/metrics`, `/q/openapi`, `/q/openbank/docs` (root-path `/q`) |

## Health probes

- **Liveness:** `GET /q/health/live` (SmallRye Health).
- **Readiness:** `GET /q/health/ready`.
- Both on the management port 8085. Kubernetes probes target the management port.

## Observability

- **Metrics:** Micrometer → Prometheus at `/q/metrics`.
- **Tracing:** OpenTelemetry (Quarkus extension); log format carries `traceId` and `correlationId`.
- **AI observability:** every model completion and tool-call decision is an AI-attributed audit event (see [04 — Data](./04-data.md)); ADR-0031 D6 targets Langfuse on top of OTel for approval-without-edit-rate tracking (runtime, not in this module yet).

## Deploy & FinOps tier (ADR-0057)

- Cloud-agnostic substrate, GitOps via ArgoCD, scale-to-zero by default for the lowest tier the service's trigger allows ([ADR-0057](../../../../docs/adr/0057-scale-to-zero-workload-tiers-and-finops-classifier.md)).
- agent-service is **not** a money-path service, so it is **not** pinned to the always-on T0 tier. It is request-triggered (admin UI / MCP client) with no event-loop dependency, so it is a candidate for a **scale-to-zero** tier; the actual tier is **derived from measured traffic**, not hand-assigned, and the declared-vs-measured gate keeps it honest. (Exact tier: derived in CI — TBD here.)
- Stateless reasoning layer + no banking state ⇒ cold start is cheap; the owned evidence tables ([04 — Data](./04-data.md)) are ordinary Postgres data with no domain state to reconcile after a restart.

## SLO (proposed)

_These are design-target SLOs for a production-shaped deployment — they are not measured, guaranteed, or met in the single-node sandbox._


| Objective | Target |
|---|---|
| MCP `tools/list` / `ping` availability | 99.9% |
| `/agent/chat` p95 latency | bounded by the model backend; the loop is capped at 5 iterations × 512 output tokens |
| Policy-gate decision (OPA reachable) | p95 < 50 ms |

The chat path latency is dominated by the upstream model; the free Groq tier enforces a ~12k tokens/min budget, which is why tool results are capped (`MAX_TOOL_RESULT_CHARS = 3000`).

## Runbooks

### Assistant says "the model backend is temporarily unavailable"
The gateway degraded gracefully on a model error. Check: `AGENT_DEFAULT_MODEL`, `GROQ_API_KEY` presence, and the backend's status. A 429 / "rate-limited" message means the free-tier tokens-per-minute budget tripped — retry after a few seconds.

### Assistant says "those tools couldn't be reached"
A whole tool round errored (auth/connectivity). Verify: OIDC `openbank-services` client secret, the downstream service URLs (`quarkus.rest-client.*.url`), and that the downstream services are up. The loop stops offering tools after a failed round and answers in text — that is by design, not a crash.

### Every tool call is DENIED
Expected under `advisory` only if a tool is unmapped (deny-by-default) or no `X-Agent-Id` was asserted on `/mcp`. Under `block`: check the OPA sidecar (8181) is reachable and the `agents.yaml` charter bundle is loaded. If the **PDP itself is unreachable**, the gate degrades to advisory + WARN (it does **not** lock the assistant out) — fix the OPA sidecar to restore enforcement.

### Flip enforcement on
Set `AGENT_POLICY_ENFORCEMENT=block` in the deployment env once an OPA sidecar is present in the target. No redeploy of code needed; the kill-switch / charter changes live in `agents.yaml` + OPA bundle.

### Recorded human merge ingest (ADR-0031 phase 3)
The `record-agent-merge` GitHub Actions workflow POSTs every merged agent PR to `POST /api/v1/agent-merge-records` with a `ROLE_MERGE_RECORDER` service credential. Ingest is idempotent by `prNumber`: a redelivered webhook inserts nothing (`ON CONFLICT DO NOTHING`) and returns `200` with the original record and `inserted=false`, with **no second audit event**. If merges are missing from the evidence: check the workflow run log first (a rejected or unauthenticated POST fails loudly there), then the ingest credential's role and the service's Postgres reachability.

## Version & release

Released component (`version.txt` present) — current `1.27.4`. Versioning is owned by release-please from Conventional Commits; do not hand-edit `version.txt` or `CHANGELOG.md`. API-contract bumps (`openapi.yaml: info.version`) are classified from the OpenAPI diff, independent of the release version (ADR-0048).

## AML service mTLS in production

The production AML REST client uses the named `aml-authority` TLS configuration and the AML service's client-authenticated listener on port 8443. The deployment supplies a client certificate and the private CA trust bundle; TLS 1.3 is required. If AML calls fail after rollout, check the client certificate and CA mount, the production REST-client URL, and the network policy path to that listener together. Local development and tests continue to use their HTTP fixtures; a passing local HTTP test alone does not prove the deployed TLS handshake.

## Shared configuration defaults

This service's `application.yaml` does not declare the HTTP security response headers (`quarkus.http.header.*`) or the management interface (`quarkus.management.*`: enabled, port 8085, host 0.0.0.0, root path `/q`). These values come from the fleet defaults in `openbank-libs-runtime/src/main/resources/application.yaml`, which is on every service classpath at a lower precedence than the service's own file. The values are identical to what this service used to declare itself. To change one for this service only, set it in this service's `application.yaml`; the service file takes precedence.

## Shared build dependencies

This service's `build.gradle.kts` does not list the dependencies every Quarkus service shares: the Quarkus BOM, quarkus-kotlin, RESTEasy Reactive with Jackson, SmallRye Health and OpenAPI, OIDC, the Micrometer Prometheus registry and config-yaml, plus the quarkus-junit5, AssertJ and REST Assured test libraries. The `openbank.quarkus-service` convention plugin in `build-logic` declares them. The resolved classpath is unchanged. `build.gradle.kts` lists only what this service adds.

## Kafka consumer group override

The consumer group id and offset reset for this service's Kafka channels are not set as YAML keys in `application.yaml`, because SmallRye Config quotes dotted leaf keys and the connector never reads them. They are supplied by a properties-file override ConfigMap loaded through `QUARKUS_CONFIG_LOCATIONS`. The reference pattern for this is card-issuance-service's `party-events-in` channel; the `application.yaml` comments point at it. This is a documentation and comment change only. No configuration value or runtime behaviour changes.
