# Overview

## What the service does

`openbank-tpp-registry-service` is the **system of record for Third Party Provider (TPP) authorisations** under PSD2 / Open Banking in the OpenBank platform. It holds:

- **TppEntry aggregate** — the EBA/CNB unique identifier (`tppId`, e.g. `CZ-CNB-123456`), legal name, country (ISO 3166-1 alpha-2), National Competent Authority (`nca`, e.g. `CNB`, `BaFin`), the set of authorised **roles** (AISP / PISP / PIISP / ASPSP), status (ACTIVE / SUSPENDED / REVOKED / BLACKLISTED), and the eIDAS certificate metadata (QWAC and QSeal Subject DN + expiry dates).
- **EbaRegisterSyncState** — bookkeeping for the (planned) synchronisation against the EBA central register: last sync time, last success, total entries, last error.
- **Authorization check** — a fast read used by other services to decide whether a TPP may exercise a given role *right now* (active, holds the role, certificate not expired).

## What the service **does NOT** do

- ❌ Does not store or evaluate customer **consents** — that's `openbank-consent-service`.
- ❌ Does not perform **strong customer authentication (SCA)** — that's `openbank-sca-service` (ADR-0021).
- ❌ Is not the **Open Banking API façade** the TPP calls — that's `openbank-psd2-service`, which calls this registry to authorise the caller.
- ❌ Does not **validate the eIDAS certificate chain at the TLS layer** — it stores the Subject DN and expiry; live mTLS/QWAC pinning is an edge/gateway concern.
- ❌ Does not yet **pull from the EBA register automatically** — the EBA sync is a stub; TPPs are registered manually (see `attemptEbaSync`).

## Position in the domain

```
   ┌────────────┐   register / blacklist   ┌──────────────────────┐
   │  admin UI  │ ───────────────────────► │ tpp-registry-service │
   └────────────┘                          └─────────┬────────────┘
                                                      │ GET /check (authorize TPP)
   ┌────────────┐    AIS/PIS call    ┌──────────────┐ ▲
   │    TPP     │ ─────────────────► │ psd2-service │─┘
   └────────────┘                    └──────────────┘
                                                      │ outbox → Kafka
                                                      ▼
                                          ┌──────────────────────┐
                                          │ audit-service / other │
                                          │ (topic openbank.tpp.  │
                                          │  registry.event)      │
                                          └──────────────────────┘
                                                      │
                                                      ▼
                                                 PostgreSQL
                                          (db: openbank_tpp_registry)
```

## Key use cases

| Use case | API | Event |
|---|---|---|
| Check whether a TPP may exercise a role | `GET /api/v1/tpp-registry/check?tppId=…&role=AIS` | — (read) |
| Register a new TPP | `POST /api/v1/tpp-registry` | (outbox infra present; not yet emitted) |
| List / filter registered TPPs | `GET /api/v1/tpp-registry?countryCode=&role=&status=` | — (read) |
| Get a single TPP | `GET /api/v1/tpp-registry/{tppId}` | — (read) |
| Blacklist a TPP | `POST /api/v1/tpp-registry/{tppId}/blacklist` | (outbox infra present; not yet emitted) |
| Trigger EBA register sync | `POST /api/v1/tpp-registry/sync/eba` | — (stub) |
| Read EBA sync state | `GET /api/v1/tpp-registry/sync/state` | — (read) |

## Callers

- **psd2-service** — the primary caller; authorises every inbound TPP request through `GET /check` before serving an AIS/PIS/PIIS operation (declared upstream in `governance.yaml`).
- **admin-ui** (via Keycloak token) — operators / compliance register, list, inspect and blacklist TPPs.
- **consent-service / sca-service** — may read the registry to attribute a consent or authentication to a known TPP.

## Dependencies

- **PostgreSQL** (database `openbank_tpp_registry`)
- **Kafka** (`openbank-kafka`, topic `openbank.tpp.registry.event`)
- **Redis (Valkey)** — idempotency cache
- **Keycloak** — OIDC auth
- **OPA sidecar** (ADR-0034) — policy decision point for `@Authorize`, advisory by default
- **openbank-libs** — `IdempotencyStore`, `authz` (`@Authorize`, `OpaSidecarPolicyDecisionPoint`), outbox conventions, BuildInfo, DocsResource

## Business value

- **Single gate for TPP trust** — one authoritative answer to "is this TPP allowed?", so every PSD2 surface enforces the same registration and blacklist state.
- **Regulatory alignment** — mirrors the EBA / national competent authority register; certificate expiry and blacklist are first-class, supporting PSD2 RTS on secure communication.
- **Operational kill-switch** — blacklisting a TPP immediately fails its authorization checks, cutting off a compromised or de-licensed provider.
- **Auditable** — registrations and blacklisting are intended to propagate via outbox + Kafka for the audit trail (transport wired; event emission pending).
