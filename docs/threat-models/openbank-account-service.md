<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->
# Threat model — account-service

- **Date:** 2026-05-30
- **Status:** Lightweight STRIDE/DFD (ADR-0030 D2). Money-path bounded context.
- **Service ADR:** see `docs/adr/` (account lifecycle); platform controls per ADR-0029/0030/0034.

## 1. Scope & purpose

BIAN-aligned account lifecycle: open / query / freeze / unfreeze / close, IBAN allocation,
multi-currency pockets. Source of truth for account *identity and state* (not balances —
that is balance-service).

## 2. Data flow (DFD)

```
[Operator/Admin UI] --OIDC--> (REST /api/v1/accounts*) --> [account-service] --> [(Postgres: accounts, account_pockets)]
                                                                |
                                                                +--> [(account_outbox)] --outbox--> [Kafka account events]
                                                                |
                                                                +--> [sanctions-service] (OIDC M2M, sync, ADR-0032)
[Kafka party events] --in--> [account-service PartyEventConsumer] --activate--> [account-service]
                                                                |
                                                                +--M2M client_credentials (ROLE_OPERATOR)--> [transaction-service POST /api/v1/transactions]   (welcome bonus, sandbox-only)
```

- **External entities:** operators/admins (human, OIDC via Keycloak), downstream consumers of account events, party-service (event source).
- **Trust boundaries:** UI↔service (mTLS + OIDC + OPA authz, ADR-0034); service↔Postgres; service↔Kafka (outbox + party-events-in); **service↔transaction-service (outbound M2M, new — welcome-bonus grant)**; **account-service↔sanctions-service** (new, OIDC M2M, ADR-0032 §C).
- **Assets:** account identity, IBAN, freeze/closure state, ownership linkage, **the oidc-client M2M secret** (grants ROLE_OPERATOR on the money path).

## 3. Authn/Authz

- Mutating endpoints: `@RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")`. Read/info: `@PermitAll`.
- Centralized policy via OPA sidecar (ADR-0034, advisory→enforce).

## 4. STRIDE

| Threat | Vector | Mitigation |
|---|---|---|
| **S**poofing | Caller impersonates operator | OIDC bearer + mTLS; no anonymous mutation |
| **T**ampering | Forced freeze/close, IBAN reassignment | RBAC on all mutations; state-machine guards; DB constraints; audit trail |
| **R**epudiation | Operator denies freezing an account | AuditEvent per lifecycle transition (immutable, ADR audit) |
| **I**nfo disclosure | IBAN / account enumeration via `/iban/{iban}` | AuthZ on lookup; rate limiting at gateway; no PII in IBAN response beyond need |
| **I**nfo disclosure / **IDOR** | Customer reads another party's account/balance via a guessed id (reads are gated by role, not ownership; the edge calls with a ROLE_OPERATOR M2M token) | Primary control is at the customer-edge (resolves ownership before proxying, finding A1). **Defense-in-depth here:** when a call carries `X-Customer-Party-Id` the read must belong to that party, else 404 (no existence oracle) — catches an edge bug/new route that forwards the header but skips its own check. Operator/service reads (no header) unaffected. |
| **I**nfo disclosure | Domain metrics leak PII / enable per-customer inference via high-cardinality labels | `DomainMetrics` low-cardinality contract (ADR-0077): `openbank.accounts.created` tagged only by `product_type` (closed `AccountType` enum) + `currency`; `openbank.accounts.closed` adds a `reason` **normalized to a closed set** (`customer_request`/`regulatory`/`fraud`/`inactivity`/`unspecified`/`other`) — the operator-supplied free-text reason never becomes a label; outbox-backlog gauge tagged only by `service`. Never an account id, IBAN, party id, or balance. Counters increment only after the commit + publish. `/q/metrics` is cluster-internal |
| **D**oS | Mass open/close churn | Gateway rate limits; outbox decouples event load |
| **E**oP | Viewer escalates to freeze/close | Distinct roles; OPA enforce; deny-by-default |
| **S**poofing / **E**oP (M2M) | Compromise of the `oidc-client` secret → mint a ROLE_OPERATOR token → inject arbitrary transactions via transaction-service `POST /api/v1/transactions` | Secret held only in a K8s Secret (ExternalSecret from Vault), never in image/git; rotatable; least-privilege client (`openbank-services`); welcome-bonus call is **flag-gated default-OFF** and **sandbox-only**. Residual: transaction-service does not currently distinguish caller identity beyond the role — accepted residual risk in sandbox (see §5) |

## 5. Residual risks / assumptions

- Relies on Keycloak realm integrity and OPA policy correctness.
- Freeze/close are single-actor today; consider four-eyes (ADR-0034 MakerChecker) for close.

## 6. Change log

- **2026-06-09** — Customer-mediated ownership guard on the read endpoints (`getAccount`,
  `getAccountByIban`, `getBalance`): when `X-Customer-Party-Id` is present the account must belong to
  that party (else 404). Defense-in-depth for the edge IDOR fix (security finding A1). No data-flow or
  trust-boundary change; operator/service reads unaffected. Tested by `AccountSecurityContractTest`
  (decision) + `AccountApiIT` (end-to-end header behaviour).
- **2026-05-30** — Added `account_outbox_seq` (Hibernate sequence fix). Additive DDL only:
  no new data flow, external surface or trust boundary. Risk class = **availability** (a missing/
  wrong sequence breaks INSERTs); mitigated by `HibernateSequenceGuardTest`. Rollback: `DROP SEQUENCE`.
- **2026-06-01** — IBAN generation correctness (`IbanGenerator` + new `CzechAccountNumber` in
  openbank-libs). The previous generator emitted a padded `System.nanoTime()` tail under a hard-coded
  `0000` bank code: ISO 13616 mod-97-valid but with a **BBAN that no Czech bank could issue** (no ČNB
  169/2011 Sb. mod-11 on prefix/base) — i.e. a syntactically-valid IBAN naming a nationally
  **non-existent** account. New code composes `bankCode(4) + prefix(6) + base(10)` where the base
  satisfies the national mod-11 weighting, *then* adds the mod-97 check digits — both checks now hold.
  **Risk class = integrity / correctness** (a money-path actor relies on the IBAN resolving to a real
  national account number; a bad BBAN would fail downstream STEP2/CERTIS validation or mis-route a
  credit). No new data flow, endpoint, or trust boundary — the BBAN is generated server-side, never
  caller-supplied, so no new injection surface. Bank code is config (`openbank.account.bank-code`,
  placeholder `2010` in sandbox; production MUST set the real ČNB-assigned code). Mitigated by
  `CzechAccountNumberTest` (known-good/known-bad mod-11 vectors incl. the published `19-2000145399/0800`
  → `CZ65…` vector, and a 2 000-iteration generate-and-revalidate loop) and `IbanGeneratorTest`
  (every generated IBAN re-validated under **both** mod-97 and decomposed mod-11). No DB change;
  rollback = revert the commit (new IBANs only; previously-issued values are unaffected).
- **2026-06-01** — Added `GET /api/v1/accounts/search?q=` (trigram IBAN-fragment search; pg_trgm GIN
  index via migration `V10`). Touches the **I — information disclosure** row: substring search is an
  account-enumeration surface. New endpoint, **no new trust boundary** (same OIDC + RBAC as the existing
  reads). The surface is bounded in the application layer: endpoint is `@RolesAllowed(SERVICE, VIEWER,
  OPERATOR, ADMIN)` — never `@PermitAll`, locked by `AccountSecurityContractTest`; a minimum fragment
  length (≥2 chars after normalization) refuses near-full scans; page size is capped at 50; keyset
  cursor; gateway rate limits apply as for `/iban/{iban}`. User input is escaped for LIKE wildcards
  (custom `ESCAPE '!'`) so a typed `%`/`_` matches literally — no wildcard-injection broadening.
  **Risk class = confidentiality** (over-broad enumeration); the trigram index changes only the access
  path, not who may query. Mitigated by `AccountServiceTest` (normalization, min-length refusal,
  cursor/cap behaviour) + the contract test. Rollback: `DROP INDEX idx_accounts_account_number_trgm`.
- **2026-06-06** — Added sanctions screening gate at `openAccount` (ADR-0032 §C). New synchronous
  trust boundary: account-service → sanctions-service (OIDC M2M, client credentials, in-cluster).
  **STRIDE analysis:**
  - **S (Spoofing):** M2M call uses Keycloak-issued client credential token; the token is short-lived
    (60 s) and verified by sanctions-service. OidcClientRequestReactiveFilter handles refresh.
  - **T (Tampering):** the screening request is idempotent by `idempotencyKey`; the result cannot
    be replayed to open a second account (the key includes `partyId` + `openAccountIdempotencyKey`).
  - **R (Repudiation):** `sanctions_screened_at` + `sanctions_status` are persisted on the `accounts`
    row. The outbox `AccountCreatedEvent` carries the status so downstream can audit independently.
  - **I (Information disclosure):** the party name sent to the screening service is already an
    asset in party-service; sending it to sanctions-service adds a new recipient but not a new
    exposure to untrusted parties (M2M, in-cluster only).
  - **D (Denial of service):** gate **fails closed** — if sanctions-service is unreachable,
    `AccountScreeningUnavailableException` propagates and the account is NOT opened. The caller
    should retry after the availability event. Connect timeout 3 s, read timeout 5 s.
  - **E (Elevation of privilege):** no privilege change; the screening call is synchronous and
    blocking — the handler cannot be bypassed by an async race. A HIT result throws immediately
    before any DB write.
  **Risk class = AML/CFT compliance** (a missed HIT would allow a sanctioned entity to open an account).
  **Residual risk:** if sanctions-service itself returns a wrong CLEAR for a HIT, account-service
  cannot detect it. Mitigated by: sanctions-service has its own threat model; list refresh cadence
  is a compliance decision (outside scope here); manual periodic reconciliation via the admin-UI
  onboarding cockpit (ADR-0068).
  Flyway `V11__account_sanctions_screening.sql`: `sanctions_screened_at TIMESTAMPTZ`, `sanctions_status VARCHAR(20)`.
  Rollback: `ALTER TABLE accounts DROP COLUMN sanctions_screened_at, DROP COLUMN sanctions_status` + revert commit.
- **2026-06-08** — Welcome-bonus auto-grant (`PartyEventConsumer` → `WelcomeBonusPort`/
  `TransactionServiceClient`). On account activation, account-service makes its **first outbound
  service call**: an M2M `client_credentials` request (oidc-client `openbank-services`, ROLE_OPERATOR)
  to transaction-service `POST /api/v1/transactions` initiating a 100k CZK incoming credit. **New trust
  boundary** (service↔transaction-service, see §2 DFD) and a new STRIDE row (Spoofing/EoP via the M2M
  secret, see §4). **Risk class = integrity / elevation-of-privilege** (the secret can mint operator
  tokens on the money path). Mitigations: secret only in K8s Secret/Vault (never image/git), rotatable;
  the grant is **flag-gated `openbank.welcome-bonus.enabled` default-OFF and sandbox-only** (it conjures
  money from the bank clearing account — must never run in prod); idempotency-keyed on the account id so
  re-delivery cannot double-pay; grant is best-effort so a failure never blocks activation. Residual
  risk: transaction-service authorizes on role alone, not caller identity (accepted in sandbox; a
  per-caller allowlist / mTLS-SPIFFE identity is the production follow-up). Mitigated by
  `PartyEventConsumerTest` (enabled/disabled/grant-failure isolation). Rollback: flip the flag OFF (or
  revert the commit); no DB or schema change. Money-path PR — see PR #555 / #554.
