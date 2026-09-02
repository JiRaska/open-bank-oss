# Operations

## Build

```
./gradlew :openbank-agent-service:build           # compile + test
./gradlew detekt ktlintCheck koverVerify build     # the local gate before a PR
```

- **Packaging:** fast-jar (never uber-jar — the runtime stage COPYs `quarkus-app/`). Generic build/push: `openbank-infra/scripts/build-push-service.sh openbank-agent-service` (host-side `quarkusBuild`, not in-Docker Gradle).
- **Image:** see [`Dockerfile`](../../../../openbank-agent-service/Dockerfile) — fast-jar runtime stage.
- **No DB:** there are no Flyway migrations to apply at startup, so the usual Flyway checksum/repair runbooks do not apply here.

## Configuration (environment)

| Variable | Default | Purpose |
|---|---|---|
| `OIDC_CLIENT_SECRET` | `CHANGE_ME_LOCAL_DEV_ONLY` | secret for the `openbank-services` client (resource server + outbound client-credentials) |
| `QUARKUS_OIDC_AUTH_SERVER_URL` | `http://localhost:8080/realms/openbank` | Keycloak realm issuer |
| `OIDC_TLS_VERIFICATION` | `none` | set to `required` in a real HTTPS deploy |
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
| 8109 | application HTTP (`/mcp`, `/agent/*`) |
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
- Stateless + no DB ⇒ cold start is cheap and safe; there is no data to recover, so RPO is effectively N/A for this service.

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

## Version & release

Released component (`version.txt` present) — current `1.5.0`. Versioning is owned by release-please from Conventional Commits; do not hand-edit `version.txt` or `CHANGELOG.md`. API-contract bumps (`openapi.yaml: info.version`) are classified from the OpenAPI diff, independent of the release version (ADR-0048).
