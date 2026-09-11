<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->
# Threat model — openbank-card-issuance-service

- **Date:** 2026-06-30
- **Status:** Lightweight STRIDE/DFD (ADR-0030 D2). Payment-instrument data context.
- **Service ADR:** [ADR-0113](../adr/0113-card-issuance-bounded-context.md) (card issuance bounded context — virtual-first, no external processor); money-path since [ADR-0283](../adr/0283-card-platform-scheme-agnostic-capability-ports.md) phase 0 (#8808)

## 1. Scope & purpose

Card registry and lifecycle manager: issue virtual/physical cards (debit, credit, prepaid),
enforce lifecycle transitions (PENDING→ACTIVE→SUSPENDED/BLOCKED→CANCELLED), store reference
spending limits. **Not** a card processor — no real-time authorisation, no PIN management, no 3DS,
no external network connection in sandbox. Sandbox `maskedPan` is synthetic (no real PAN stored).

## 2. Data flow (DFD)

```
[Operator / Admin UI] --OIDC--> (REST /api/v1/cards*) --> [card-issuance-service] --> [(Postgres: cards)]
                                                                   |
                                                                   +--> [(card_outbox)] --outbox--> [Kafka card events]
                                                                              card.issued.v1
                                                                              card.status_changed.v1
```

- **External entities:** operators/admins (human, OIDC via Keycloak), compliance officers
  (`ROLE_COMPLIANCE` on block); downstream event consumers (fraud-service, analytics).
- **Trust boundaries:** UI↔service (mTLS + OIDC + OPA authz, ADR-0034);
  service↔Postgres (connection pool, credentials from Vault/ExternalSecret);
  service↔Kafka (outbox relay, ADR-0050).
- **Assets:** card identity (`id`, `maskedPan`), cardholder PII (`cardholderName`,
  `embossedName`), lifecycle state, spending limits, `partyId`/`accountId` linkage.

## 3. Authn/Authz

| Endpoint | Allowed roles |
|---|---|
| `POST /api/v1/cards` (issue) | `ROLE_OPERATOR`, `ROLE_ADMIN` |
| `GET /api/v1/cards`, `GET /{id}`, `GET /account/{id}`, `GET /party/{id}` | `ROLE_VIEWER`, `ROLE_OPERATOR`, `ROLE_ADMIN` |
| `POST /{id}/activate`, `/{id}/suspend`, `/{id}/resume` | `ROLE_OPERATOR`, `ROLE_ADMIN` |
| `POST /{id}/block` | `ROLE_OPERATOR`, `ROLE_ADMIN`, `ROLE_COMPLIANCE` |

All mutations require `Idempotency-Key` or `X-Operator-Id` header; resource-level
`@Authorize` enforces OPA policy (ADR-0034, advisory→enforce).

## 4. STRIDE

| Threat | Vector | Mitigation |
|---|---|---|
| **S**poofing | Caller impersonates operator to issue or block a card | OIDC bearer + mTLS; no anonymous mutation; `ROLE_COMPLIANCE` scoped to block only |
| **T**ampering | Forced lifecycle skip (e.g. PENDING→BLOCKED direct), limit mutation | Domain state-machine guards (`Card.activate/block/suspend/resume`) enforce legal transitions; DB constraints; `version` for optimistic locking (if added — see §5) |
| **R**epudiation | Operator denies issuing or changing card status | `card.issued.v1` + `card.status_changed.v1` emitted for every mutation through transactional outbox (ADR-0050); `changedBy` field on status-change event carries operator identity |
| **I**nfo disclosure | Cardholder name / maskedPan / limits leaked via list endpoint | `ROLE_VIEWER` read access; OPA `card.read/list` policy gates on operator; no PII in event subjects; sandbox: maskedPan is synthetic — not a mask of a real PAN |
| **I**nfo disclosure | Card enumeration via `GET /party/{partyId}` returns another party's cards | OPA `card.list` resource policy scoped to `#partyId` — operators see all; customer-facing access requires a customer-edge ownership check (see §5) |
| **I**nfo disclosure | `cardholderName` / `embossedName` in Kafka events observed by unauthorized consumer | Kafka topic ACLs restrict consumption to authorized services; outbox events carry only `partyId`, `accountId`, `cardType`, `network`, `maskedPan`, `previousStatus`/`newStatus`, `changedBy` — no full name in event payload |
| **D**oS | Mass card issuance churn | Idempotency on `issueCard` (duplicate `idempotencyKey` returns existing card, no second DB write); gateway rate limits; outbox decouples event load |
| **E**oP | Viewer escalates to issue or block | Distinct roles enforced at JAX-RS layer (`@RolesAllowed`) + OPA; deny-by-default |
| **T**ampering | Race between `suspend` and `block` on same card | Domain `require` guards throw `IllegalArgumentException` on illegal state; a concurrent block on a SUSPENDED card is valid by design; concurrent suspend+block both succeed on ACTIVE → last writer wins idempotently (both move toward frozen state) — acceptable; optimistic locking would make this exact (see §5) |

## 4a. Card authorization decision point (D3) — STRIDE supplement

Three new surfaces: `GET /api/v1/cards/category-taxonomy`, `GET|PUT /api/v1/cards/{id}/category-limits`,
and `POST /api/v1/cards/{id}/authorizations` — the decision that answers whether one card
authorisation is approved.

**The pre-existing defect this closes.** The channel controls (`contactlessEnabled`,
`onlineEnabled`, `atmEnabled`, `abroadEnabled`) have been stored on `cards` and returned by the API
since V5, and **no code anywhere read them to decide anything**. A customer who switched off
"payments abroad" changed a boolean, got a 200, and their card kept working abroad. That is worse
than not offering the control: a security control that reports success without acting invites the
customer to stop taking other precautions. Everything below exists to make those toggles real.

| STRIDE | Threat | Mitigation |
|---|---|---|
| **E**oP | A caller obtains approvals for a card it does not own | `@RolesAllowed(ROLE_API, ROLE_OPERATOR, ROLE_ADMIN)` + `@Authorize(action = "card.authorization.decide", resource = "#id")`; the customer-facing path reaches this only through the edge, which already enforces card ownership against the JWT party |
| **T**ampering | The caller supplies its own spend totals, so a compromised caller could under-report and slip past a limit | Accepted and explicit: the totals are inputs, because this service holds no authorisation history. The trust boundary is that only trusted rails may call it (no `ROLE_CUSTOMER`). When spend tracking lands, the totals become server-derived and this row narrows — flagged in the response today as `spendTracking: false` so no client presents them as measured |
| **S**poofing | An unknown card id is used to fish for a permissive default | An unknown card **declines** (`CARD_NOT_ACTIVE`) rather than 404s. The acquirer needs an answer, and the safe answer to "may this unknown card spend" is no; it also avoids an existence oracle on card ids |
| **D**oS | A customer locks themselves out of every rail | `CHIP_AND_PIN` deliberately has no toggle. A customer able to disable every channel could not pay at any terminal and could not recover from one either |
| **T**ampering | A category block is bypassed by sending an MCC the taxonomy does not know | Unknown, malformed and absent MCCs all resolve to `OTHER`, which is **limitable but never blockable**. That asymmetry is deliberate: making OTHER blockable would let one unclassified code decline arbitrary legitimate spend as the acquirer estate changes, while leaving it unblockable means a customer's gambling block cannot be dodged by a merchant miscoding — because a miscoded gambling merchant is not in the gambling range for any control, including ours |
| **R**epudiation | A decline cannot be explained to the customer | The decision returns a specific `declineReason`, and the evaluation order is card state, then customer switches, then amounts — so the reason shown is the one the customer can act on ("you turned gambling off"), not a downstream limit they never set |
| **I**nfo disclosure | The taxonomy leaks something sensitive | It is public bank policy — category ids, labels and MCC ranges. Serving it is the point: a client that hardcoded MCC sets would keep enforcing a stale policy on every installed build |

**DFD update:** adds `acquirer rail → POST /cards/{id}/authorizations → decision` and
`customer (via edge) → PUT /cards/{id}/category-limits`. No new downstream dependency; the decision
reads only this service's own tables.
**Risk class:** integrity of a money-path control (an authorisation approve/decline).
**Rollback:** revert. Card behaviour returns to what it is today — controls stored and unenforced —
which is the defect, so a rollback should be paired with disabling the customer-facing toggles
rather than leaving them visibly ineffective.

## 4b. Single-use card lifecycle (D1) — STRIDE supplement

Adds the terminal status `CONSUMED`, a `closedReason`, and an `expiresAt` validity window for
SINGLE_USE cards. No new endpoint: the card list and detail responses carry the new fields.

**Why a distinct status rather than reusing CANCELLED.** Until now "cancelled" covered a customer
closing a card, a card reported lost, and a disposable card doing exactly what it promised. Those
are three different things a customer is owed three different sentences about — and one of them is
a security signal. "Your disposable card was just used" is the fraud alarm for this product; it
cannot be distinguished from routine closure if the status is the same.

| STRIDE | Threat | Mitigation |
|---|---|---|
| **E**oP | A consumed card is re-provisioned with a live PAN and spends again | `CONSUMED` is in `TERMINAL_STATUSES`, which the PAN-vault backfill excludes. A pinned test asserts the exact contents of that set, so growing it silently is not possible — that assertion caught this change and had to be updated deliberately |
| **T**ampering | A card is marked CONSUMED without having been used, hiding a decline or a failure | `consume()` requires `cardType == SINGLE_USE` **and** `status == ACTIVE`, and is driven by the processor's lifecycle event rather than by a customer or operator request. Any other type or status throws |
| **R**epudiation | The customer cannot tell why their card stopped working | `closedReason` distinguishes `SINGLE_USE_CONSUMED` from `VALIDITY_EXPIRED`, `LOST_OR_STOLEN` and `CUSTOMER_CANCEL`; an unused card that times out becomes EXPIRED, never CONSUMED, because nothing was spent |
| **I**nfo disclosure | Revealing the PAN of a card that already spent | Unchanged and still correct: `CONSUMED` is terminal, so the existing "card not live" guard on secure details refuses it |
| **D**oS | A disposable card issued and forgotten stays a live PAN indefinitely | `expiresAt` bounds the window even if the card is never presented; a partial index supports finding those cards without weighing on the hot path |

**Not in this change, and load-bearing:** the authorize-once guarantee itself lives at the card
processor. This models the outcome so the customer can see it; it does not enforce single use. Until
the processor is configured, a SINGLE_USE card is a virtual card with a validity window — the status
machine is ready, the guarantee is not. Anything that presents it to customers as "authorises once"
before the processor side lands would be claiming a control the bank does not yet have.

## 4c. Envelope encryption for the PAN vault (ADR-0262) — STRIDE supplement

Replaces the flat AES-256-GCM key `AesGcmCardSecretCipher` read from a Kubernetes Secret with
envelope encryption: a locally-held data-encryption key (DEK) does the actual PAN/CVV
encrypt/decrypt exactly as before, but the DEK itself is wrapped by an OpenBao Transit
key-encryption key (KEK, `transit/keys/card-pan`) and never leaves OpenBao unencrypted
(`OpenBaoEnvelopeCardSecretCipher`, opt-in via `openbank.card.key-source=openbao-transit`;
unconfigured deployments keep the flat key unchanged). Two follow-ups land in the same slice:
`OpenBaoTransitDekUnwrapper` (the shared login/retry client) and `CardPanKeyReencrypt` (the batch
job that migrates existing rows off a rotated-out DEK).

**The pre-existing defect this closes.** The flat key had no rotation path at all — the wire
format (`base64(IV ‖ ciphertext ‖ tag)`) carries no key identifier, so replacing the configured key
made every previously-written row permanently undecryptable. And the key itself sat in a
Kubernetes Secret with no split-knowledge or dual-control boundary: anyone who could read the
`payments` namespace's secrets could read the raw AES key and, offline, decrypt every stored PAN.
Both are structural gaps PCI DSS 3.5/3.6/3.6.4 expect closed even though this service's PAN data is
synthetic today (see §1) — closed now, before that stops being true, rather than as a migration on
live data later.

| STRIDE | Threat | Mitigation |
|---|---|---|
| **I**nfo disclosure | Reading the raw encryption key from a compromised pod/namespace and decrypting the vault offline | The KEK never leaves OpenBao; the service only ever holds an unwrapped DEK in memory (never persisted, never logged) after a Transit `decrypt` call. Compromising the pod exposes the same blast radius as before (the in-memory DEK) but no longer exposes a key an operator can read directly from a Secret — access to unwrap now requires OpenBao's own Kubernetes-auth role + ACL policy, a boundary this service does not itself control |
| **T**ampering | A rotated-out key can never be retired, so a leaked historical key stays valid forever | `vault write -f transit/keys/card-pan/rotate` versions the KEK; `CardPanKeyReencrypt` re-encrypts existing rows onto the new DEK (compare-and-swap on the old ciphertext, so a concurrent write is skipped, never clobbered — same idempotent shape as `CardPanVaultBackfill`, §4). Once every row has migrated, the old KEK version can be disabled in Transit |
| **R**epudiation | No record of when a key rotation happened or who triggered it | Not closed by this change — see Residual risks below |
| **I**nfo disclosure | The re-encrypt job's logs leak a PAN/CVV or a ciphertext while explaining why a row could not be migrated | `CardPanKeyReencrypt` logs counts and card ids only, never a decrypted value or a ciphertext — same discipline as `CardPanVaultBackfill` |
| **D**oS | A card-issuance pod cannot start because OpenBao is briefly unavailable at boot | `OpenBaoTransitDekUnwrapper` retries the whole (login, decrypt) pair with exponential backoff (4 attempts, ~3.5s worst case), well inside the Deployment's `startupProbe` budget. Does not help a sustained OpenBao outage — see Residual risks |
| **S**poofing | A workload other than card-issuance obtains a token for the `card-issuance-pan-dek` OpenBao role | Kubernetes-auth login binds the role to this service's ServiceAccount; OpenBao issues no token to a pod that cannot present that SA's projected JWT |

**DFD update:** adds `[card-issuance-service] --Kubernetes-auth + Transit decrypt--> [OpenBao]`
(`openbank.card.envelope.bao-addr`, declared in the gitops Deployment env so
`gen-network-policies.py`/`check-network-policy-code-edges.py` can see the egress — ADR-0262's own
delivery caught this as a code-only edge before the gitops declaration landed). No change to the
Postgres or Kafka trust boundaries.
**Risk class:** confidentiality and integrity of the PAN/CVV vault's encryption key material.
**Rollback:** revert to `flat-key` mode (the default) — existing rows encrypted under an unwrapped
DEK stay decryptable only as long as that DEK is still derivable, so a rollback must happen before
retiring the corresponding KEK version in Transit, not after.

## 5. Residual risks / assumptions

- **No optimistic locking today.** `Card` lacks a `version` column; two concurrent lifecycle
  mutations could overwrite each other at the DB layer. Acceptable in sandbox (single-operator
  workflow); add `@Version` / optimistic lock before production deployment.
- **Customer-edge ownership gap.** `GET /party/{partyId}` returns all cards for that party to
  any role. A customer-facing edge would need to validate that the requesting customer IS that
  party before proxying — this service does not enforce customer-identity binding.
- **`ROLE_COMPLIANCE` can block any card.** Compliance-initiated block has no four-eyes check.
  Consider MakerChecker (ADR-0034) for BLOCKED→permanent transitions in production.
- **Spending limits are reference data only.** `dailyLimitMinorUnits` / `monthlyLimitMinorUnits`
  are stored but not enforced in real time. A future authorisation component must consume them
  and be the enforcement point.
- **Relies on Keycloak realm integrity and OPA policy correctness.**
- **GDPR erasure.** `cardholderName` and `embossedName` are PII. The PARTY_ERASED event
  (ADR-0117) must trigger erasure of these fields from the `cards` table; until that subscriber
  is implemented, erasure is manual.
- **OpenBao runs as a single replica (ADR-0262).** No raft HA on the OpenBao side today. A
  sustained OpenBao outage blocks every NEW card-issuance pod start when `key-source=openbao-transit`
  (retry/backoff covers a brief blip, not a real outage) and blocks any in-flight key rotation or
  re-encrypt pass. Already-running pods are unaffected — the DEK is unwrapped once at boot and kept
  in memory. Fixing this is HA on OpenBao itself, out of scope for this service.
- **No audit trail for a KEK rotation.** `vault write -f transit/keys/card-pan/rotate` and setting
  `openbank.card.envelope.previous-wrapped-dek` are both manual operator actions today, and neither
  is logged anywhere queryable — `CardPanKeyReencrypt`'s own log line reports migration counts, not
  that a rotation was initiated or by whom. Acceptable while PAN data is synthetic; a real rotation
  process would need this before card data does.

## 6. Change log

- **2026-09-05** — Money-path classification (ADR-0283 phase 0, #8808). No code change. The
  service joins `rules.yaml: money_path_services` because the authorisation decision point (§4a)
  and the SCA-gated limit/control changes (ADR-0194) decide whether money may move. Measured
  before the change: `POST /cards/{id}/authorizations` has **no caller** anywhere in the fleet and
  `openbank.cards.events` never reaches the ledger — the decision exists, the traffic does not.
  That is the phase 1 defect (#8809), not a residual risk of this service. New governance tails:
  SLO pair (Pyrra), journey accountability (`journeys.yaml`, ADR-0252), four-eyes assessed in
  place (no operator verb gated today; see the `money_path_services` entry for why).

- **2026-08-17** — Envelope encryption for the PAN vault (ADR-0262). Adds
  `OpenBaoEnvelopeCardSecretCipher` (opt-in via `openbank.card.key-source=openbao-transit`),
  `OpenBaoTransitDekUnwrapper`, and the `CardPanKeyReencrypt` post-rotation batch job. STRIDE
  supplement in §4c. Closes the flat key's two structural gaps — no rotation path, no
  split-knowledge/dual-control boundary — before this service's PAN data stops being synthetic.
  New residual risks: OpenBao's own single-replica deployment, and no audit trail for a rotation
  (§5). Rollback: revert to `flat-key` mode, but only before retiring the KEK version a rollback
  would need.

- **2026-08-07** — Single-use card lifecycle (D1 server preparation). New terminal status `CONSUMED`, `CardClosedReason` (`SINGLE_USE_CONSUMED | VALIDITY_EXPIRED | LOST_OR_STOLEN | CUSTOMER_CANCEL`), and `expiresAt`; `consume()` and `expireUnused()` transitions; migration V9. STRIDE supplement in §4b. `CONSUMED` joins `TERMINAL_STATUSES`, which excludes a card from PAN-vault backfill — the pinned assertion on that set failed on this change, which is the change-detector working, and was updated deliberately rather than relaxed. **The authorize-once guarantee is NOT in this change:** it lives at the card processor, and until it is configured a SINGLE_USE card is a virtual card with a validity window. Do not present it to customers as "authorises once" before then. Rollback: revert; a card sitting in CONSUMED must be remapped to CANCELLED first, or it becomes a status no older build understands.

- **2026-08-07** — Card authorization decision point (D3). New `CardAuthorizationPolicy` (pure, 15 unit tests) plus `POST /cards/{id}/authorizations`, `GET|PUT /cards/{id}/category-limits` and `GET /cards/category-taxonomy`; new `card_category_rules` table. STRIDE supplement in §4a. **This closes a live defect, not just a missing feature:** the channel controls stored since V5 were read by nothing, so a customer switching off "payments abroad" got a 200 and no protection. The policy is deliberately pure — no repository, no clock — so every branch of a money-path decision is reachable in a unit test rather than only against a live acquirer. Per-category spend is not yet tracked; the response says `spendTracking: false` so a client shows "no data" instead of a progress ring against a zero that looks measured. Rollback: revert, but pair it with hiding the customer-facing toggles rather than leaving controls that visibly do nothing.

- **2026-06-30** — Initial threat model authored (ADR-0113 delivery gate).
  Sandbox: maskedPan synthetic, no real PAN stored, PCI DSS CHD scope not triggered.
- **2026-08-01** — Card delegation-grant enforcement projection (ADR-0232 D3, issue #2990):
  `card_delegation_projection` fed by `CardDelegationEventConsumer` from
  `openbank.delegation.events` (CARD-scoped events only), and a `CardDelegationGuard` answering
  holder OR an ACTIVE in-window grant for VIEW / MANAGE_LIMITS intents, exposed as
  `GET /api/v1/cards/{id}/delegation/check` (reuses the existing `card.read` OPA action — no rego
  change). **Risk class = elevation of privilege** (a delegate seeing card metadata they should
  not; PAN/secure-details stay outside delegation scope entirely — no CARD capability maps to
  `secure-details`). Same structural properties as the account slice: local-only enforcement (no
  synchronous call to delegation-service, tripwire test), additive guard (holder path untouched),
  DLQ so a close event is never destroyed, idempotent upsert per grant id. Residual: seconds-level
  revoke propagation per ADR-0232; customer-edge adoption of the check endpoint is its own slice.
  Rollback: revert; the projection tables are droppable without touching `cards`.
## Delegation lifecycle ordering

The card enforcement projection uses a durable monotonic cursor before changing access or blocking
a supplementary card. Revisionless opens are ignored and revisionless closes install permanent
legacy tombstones. A delayed lower revision cannot reopen a closed grant or trigger irreversible
card blocking after a newer lifecycle decision. Consumers must be deployed and verified before the
revisioned delegation producer.
