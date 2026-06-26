# Compliance

> **Money-path classification:** `openbank-api-gateway` is **NOT** in `rules.yaml: money_path_services`. It owns no money state and executes no transactions — it only forwards requests to the money-path services (ledger, transaction, balance, sepa, domestic, …), which carry the 2-approval + threat-model obligations. As an **edge component**, however, the gateway is squarely in scope for the *availability* and *network-security* sides of the regulatory framework.

## Regulatory framework

| Regulation | Relation to this component | Implementation |
|---|---|---|
| **DORA** (Reg. (EU) 2022/2554) | Operational resilience of the front door; ICT availability | Always-on T0 tier (ADR-0057), `restart: unless-stopped` / `minReplicas ≥ 1`, retries + timeouts, Admin `/status` probe, runbooks in [05](./05-operations.md) |
| **NIS2** | Network & information security at the perimeter | Single north-south choke point; passthrough preserves end-to-end bearer auth; TLS termination at the edge; Admin API network-restricted in prod |
| **PSD2** (Reg. (EU) 2015/2366) | TPP access to Open Banking transits the gateway | `/api/v1/psd2` → `psd2-service` (`:8107`), `/api/v1/consents` → `consent-service` (`:8106`); the gateway does not decide consent — it routes; consent/PSD2 logic is downstream |
| **GDPR** | The gateway transports PII but stores none | No persistence (DB-less); bodies streamed not stored; `Authorization` treated as secret, not logged |
| **AMLD** | AML routing only | `/api/v1/aml` → `aml-service` (`:8117`); the gateway performs no screening itself |

There is **no service-specific banking regulation** at this tier (no CNB account-keeping decree applies — the gateway keeps no accounts).

## GDPR mapping

The gateway is a **processor/conduit, not a controller** of personal data. It does not store, index, or transform personal data.

| Aspect | Position |
|---|---|
| Lawful basis | N/A at this tier — basis is held by the upstream owning service (Art. 6(1)(b) contract / (c) legal obligation) |
| Data stored | **None** — stateless, DB-less |
| Data-subject rights (access/erasure/portability) | **Not applicable here** — exercised against the upstream services that own the records (see e.g. account-service `06-compliance`) |
| Logging | Access logs contain method/path/status/client IP, **not bodies**; bearer tokens must not be logged |
| Cross-border transfer | The gateway does not move data outside the platform; all upstreams are intra-OpenBank (EU/EEA) |

### Data flows

```
client/TPP ──Bearer──► api-gateway ──(verbatim)──► upstream owning service
                          │                              │
                   no store, no log of body        controller of the PII
                   forwards token + body           (lawful basis, retention)
```

The gateway is a **same-controller intra-platform hop**: it neither originates nor terminates personal data; it forwards it to the controller service.

## DORA mapping (Reg. (EU) 2022/2554)

| Article | Topic | Implementation at the gateway |
|---|---|---|
| Art. 5 | ICT risk management | Edge component is part of the platform operations register |
| Art. 9 | Protection & prevention | Passthrough preserves downstream OIDC; Admin API restricted; pinned image `kong:3.7.1` |
| Art. 10 | Detection | Kong access/error logs to stdout/stderr; `/status` health; upstream pass-through probes |
| Art. 11 | Response & recovery | `restart: unless-stopped` (local) / `minReplicas ≥ 1` (cluster); runbooks for `502`/`504`/config reload in [05](./05-operations.md) |
| Art. 28 | Third-party risk | Kong OSS, self-hosted; version pin governed by `rules.yaml: finops` managed-version lifecycle |

## PSD2 (Open Banking) — gateway role

PSD2 access **passes through** the gateway but is **decided downstream**:

```
TPP → api-gateway (route only, token forwarded)
    → consent-service  (validate consent)
    → psd2-service     (translate to internal)
    → account/balance/… (read)
    → response back through the gateway
```

The gateway adds no consent logic; it must remain transparent so the downstream chain sees the original bearer token and headers.

## Security controls

- ✅ **Single north-south entry point** — one place to attach future authn/z, rate-limiting, observability (the ADR-0051 direction).
- ✅ **Passthrough auth** — `Authorization` forwarded unchanged; downstream Quarkus services validate the Keycloak RS256 JWT (OIDC). End-to-end token integrity preserved.
- ✅ **Stateless / DB-less** — no gateway datastore to breach, back up, or leak.
- ✅ **Read-only config mount** — `kong/kong.yml` is immutable at runtime; changes go through git/PR.
- ✅ **Resilience defaults** — `retries: 2`, `connect 5s`, `read/write 30s` per upstream; bounded failure modes (`502`/`504`).
- ✅ **Pinned image** — `kong:3.7.1`, no `latest`; upgrades reviewed under FinOps version lifecycle.
- ⚠️ **Gateway-level JWT validation not enabled** — passthrough relies entirely on downstream OIDC. The Kong OSS `jwt` plugin is a documented, *not-yet-enabled* option (`.env.example` placeholders). Tracked as a maturity item, not an exploitable gap in the local-first setup.
- ⚠️ **No gateway rate-limiting today** — deliberately deferred; rate-limiting currently lives in the downstream services (`libs.web.RateLimitFilter`). Centralising it at the edge is a roadmap item.
- ⚠️ **Admin API exposure** — `:8001` must be network-restricted in any non-local deployment; it is unauthenticated in DB-less OSS Kong.

## Audit

The gateway emits **no domain audit events** (that is `audit-service`, reached via `/api/v1/audit` → `:8113`). Its audit evidence is operational: access/error logs and config change history in git. Business audit trails are produced by the upstream services it fronts.
