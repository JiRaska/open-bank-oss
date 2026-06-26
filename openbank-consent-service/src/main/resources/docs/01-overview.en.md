# Overview

## What the service does

`openbank-consent-service` is the **system of record for consents** in the OpenBank platform — the explicit authorisations a customer (party) gives to a third party or agent to access account data or initiate payments. It holds:

- **Consent aggregate** — who granted it (`partyId`), to whom (`granteeId` + `granteeType`), the set of `scopes`, the optional list of `accountIbans` (null = all accounts), the validity window (`validFrom`/`validTo`) and lifecycle `status`.
- **Consent scopes** — PSD2 AISP (`ACCOUNTS_READ`, `BALANCES_READ`, `TRANSACTIONS_READ`, `STATEMENTS_READ`, plus ČOBS-specific `PAYMENT_ACCOUNTS_READ`, `STANDING_ORDERS_READ`, `DIRECT_DEBITS_READ`), PISP (`PAYMENTS_INITIATE`, `PAYMENTS_STATUS_READ`, `DOMESTIC_PAYMENT_INITIATE`, `SIPO_PAYMENT_INITIATE`), CBPII (`FUNDS_CONFIRMATION`), and AI-agent extension scopes (`AGENT_QUERY`, `AGENT_INITIATE`, `AGENT_NOTIFY`, `AGENT_ANALYZE`).
- **Grantee types** — `TPP` (eIDAS-certified Third Party Provider), `BANK_AGENT`, `CUSTOMER_AGENT`, `INTERNAL_SERVICE`.
- **Lifecycle state machine** — `PENDING_SCA → ACTIVE → (EXPIRED | REVOKED)`, plus `REJECTED` and `SUPERSEDED`.

The core runtime job is the **validation endpoint**: given a consent id, grantee, required scope and target account, return whether the access is permitted. This is what downstream PSD2/agent surfaces call before serving data.

## What the service **does NOT** do

- ❌ Does not run Strong Customer Authentication — it *delegates* to `openbank-sca-service` and only checks that a referenced SCA challenge is `COMPLETED` for purpose `CONSENT_GRANT` (ADR 0021).
- ❌ Does not speak the PSD2/Berlin-Group wire protocol — `psd2-service` translates external TPP calls into internal consent commands.
- ❌ Does not hold account, balance or transaction data — it only references `accountIbans` and authorises read/initiate access to them.
- ❌ Does not execute payments — it grants the *right* to initiate; payment services perform the transfer.
- ❌ Does not register or vet TPPs / eIDAS certificates — that is upstream (TPP registry / `psd2-service`).

## Position in the domain

```
   ┌──────────┐  create / activate    ┌──────────────────┐  getChallenge   ┌──────────────┐
   │  TPP /   │ ───────────────────►  │  consent-service │ ──────────────► │ sca-service  │
   │ psd2-svc │   (REST, OIDC)        └────────┬─────────┘  (REST client)  └──────────────┘
   │ / agent  │                                │
   └────┬─────┘  validate(scope)               │ outbox → Kafka
        │ ◄─── allow / deny                     ▼      openbank.consent.events
        ▼                              ┌──────────────────────────────┐
   account/balance/                    │ audit-service / admin-ui /    │
   transaction reads                   │ downstream consumers          │
   (gated by consent)                  └──────────────────────────────┘
        │
        ▼
    PostgreSQL (openbank_consents)
```

## Key use cases

| Use case | API | Event |
|---|---|---|
| Create consent (awaiting SCA) | `POST /api/v1/consents` | — |
| Activate consent after SCA | `POST /api/v1/consents/{id}/activate?scaSessionId=…` | `ConsentGranted` |
| Reject consent | `POST /api/v1/consents/{id}/reject?reason=…` | `ConsentRejected` |
| Revoke active consent | `DELETE /api/v1/consents/{id}?partyId=…` | `ConsentRevoked` |
| Validate access at request time | `POST /api/v1/consents/{id}/validate` | — |
| Get consent by id | `GET /api/v1/consents/{id}` | — |
| List consents for a party | `GET /api/v1/consents/party/{partyId}` | — |
| List consents for a grantee | `GET /api/v1/consents/grantee/{granteeId}` | — |

> `ConsentExpired` is also defined as a domain event for the expiry transition.

## Callers

- **psd2-service** — translates TPP requests into create/validate/revoke calls.
- **AI agent gateway / MCP** — validates `AGENT_*` scopes before serving queries or initiating payments (ADR 0031/0034).
- **admin-ui** — operators and compliance view/revoke consents on a customer's behalf.
- **payment & account/balance/transaction read surfaces** — call `validate` before serving data to a third party.
- **sca-service** — called *by* consent-service (read-only) to confirm challenge completion.

## Dependencies

- **PostgreSQL** (`openbank_consents`, tables in `public`) — consents, scopes, accounts, outbox.
- **Kafka** (`openbank-kafka`, topic `openbank.consent.events`) — lifecycle events via outbox.
- **Redis (Valkey)** — idempotency cache for consent creation.
- **Keycloak** — OIDC token validation.
- **openbank-sca-service** — SCA challenge verification (REST client, resilient).
- **OPA sidecar** — authorization decisions (ADR 0034; advisory by default).
- **openbank-libs** — `DomainEvent`, `IdempotencyStore`, `@Authorize`/`PolicyDecisionPoint`, `ApiError`/`ErrorCode`, outbox base, ServiceInfo/Docs resources.

## Business value

- **Regulatory gate for Open Banking** — no third party touches customer data without an active, scoped, time-bounded consent, satisfying PSD2 Art. 64–67 and the SCA RTS.
- **Auditable lifecycle** — every grant/reject/revoke emits a domain event persisted by `audit-service` for the statutory retention period.
- **Single, scope-aware authorisation point** — one `validate` call decides access, so downstream services need no consent logic of their own.
- **Future-proof for AI agents** — the same consent model extends to delegated agent access with explicit, revocable scopes.
