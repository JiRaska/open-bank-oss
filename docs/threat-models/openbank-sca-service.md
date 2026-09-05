<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->
# Threat model — sca-service

- **Date:** 2026-05-30
- **Status:** Lightweight STRIDE/DFD (ADR-0030 D2). **PSD2 SCA trust boundary — security-critical.**
- **Service ADR:** see `docs/adr/`; platform controls per ADR-0029/0030/0034. PSD2 RTS Art. 97/98.

## 1. Scope & purpose

Strong Customer Authentication: initiate and verify challenges (OTP, TOTP, biometric). This service
is the **authentication assurance gate** for payments and consent — defeating it defeats SCA bank-wide.

## 2. Data flow (DFD)

```
[Payment/Consent services] --> (POST /api/v1/sca/challenges) --> [sca-service] --> [(Postgres: sca challenges)]
[Customer channel] --------> (challenges/{id}/verify) ----------^                       |
                                                                                        +--> [(sca_outbox)] --> [Kafka sca events]
```

- **External entities:** payment/consent services (request challenge), customer channel (verify).
- **Trust boundaries:** customer edge (OTP delivery); service↔Postgres/Kafka.
- **Assets:** challenge secrets/OTP, verification state, attempt counters, biometric assertions.

## 3. Authn/Authz

- Challenge issuance is service-to-service (mTLS). Verify is bound to the challenge id + customer
  session. OPA enforce.

## 4. STRIDE

| Threat | Vector | Mitigation |
|---|---|---|
| **S**poofing | Attacker verifies on victim's behalf | Bind challenge to transaction + party + channel session |
| **T**ampering | Replay a captured verify | One-time challenge; short TTL; nonce; state→consumed |
| **R**epudiation | Customer denies authenticating | AuditEvent with challenge id + outcome (SCA evidence retained) |
| **I**nfo disclosure | OTP leakage / enumeration | Never log OTP; constant-time compare; opaque challenge ids |
| **I**nfo disclosure | Domain metrics leak PII / enable per-customer inference via high-cardinality labels | `DomainMetrics` low-cardinality contract (ADR-0077): `openbank.sca.challenges` tagged only by `method` (closed `ScaMethod` enum) and `openbank.sca.completions` adds an `outcome` from a **closed set** (`completed`/`failed`/`expired`/`cancelled`) derived from the terminal `ScaStatus` — never a challenge id, party id, OTP, or device credential; outbox-backlog gauge tagged only by `service`. Counters increment only after the terminal state is committed (a retryable failed attempt that stays `PENDING` is not counted). `/q/metrics` is cluster-internal |
| **D**oS | Challenge flooding / OTP cost abuse | Rate limit issuance per party; backoff |
| **E**oP | **Brute-force / bypass of verify** | Strict attempt cap → lock; short TTL; fail-closed; deny-by-default |
| **E**oP | **SCA bypass via push/biometric (audit K2)** | **FIXED (ADR-0021):** push/biometric `verify` no longer auto-approves; it consults a signature-verified, dynamic-linked decision recorded out-of-band by the enrolled device. No decision ⇒ challenge stays `PENDING` (never auto-completes). |
| **S**poofing | Forge a device approval | Decision must carry a signature over the challenge's dynamic-linking payload, verified against the party's enrolled public key; device must belong to the challenge party (ownership check) |
| **T**ampering | Replay an approval for a different amount/payee or flip DENIED→APPROVED | Signed payload binds challenge id + decision + amount + currency + creditor (RTS Art. 5); a captured signature is invalid for any other payload |

## 5. Residual risks / assumptions

- **Brute-force resistance** (attempt cap + TTL) is the dominant control — must fail closed.
- OTP delivery channel integrity is out of scope (assumed secure transport).
- **Dynamic linking is now enforced** for decoupled approval (ADR-0021): the device signs the
  exact amount+payee bound to the challenge. Full WebAuthn/FIDO2 *attestation* (CBOR/COSE
  attestation statement, device-integrity attestation) is a follow-up — the current verifier
  checks the *assertion* signature, not the attestation chain.
- Enrollment trust: a credential is bound at enrol time; enrollment must itself be SCA-gated /
  attested in production (sandbox: enrollment is open, behind the customer-edge auth of ADR-0065).

## 6. Change log

- **2026-09-03** — Resolve the four-eyes stalemate via per-action service-account exemptions
  (#8360, ADR-0280). `device.enroll` and `scaChallenge.consume` are now in
  `rules.yaml: four_eyes.actions` with `four_eyes.exemptions` naming their verified M2M callers
  (`service-account-openbank-edge` for both; `service-account-openbank-services` for consume —
  the delegation grant-accept and document-signing ceremonies, #3734). What becomes gated is the
  residual HUMAN path: `operator-sca-write` (ops-console enroll-on-behalf, manual challenge
  consumption) is flagged `four_eyes_required` once `authz.four-eyes.enforce` (ADR-0155) is
  enabled — until then the flag is computed and carried, and nothing pauses. The exemption trusts
  the edge client credentials to remain customer-edge's alone; no new privilege class is created
  beyond what those identities already hold. New `four_eyes_exempt` rule and clauses in
  `rest.rego` are additive and backward-compatible (a bundle predating the `exemptions` key
  behaves exactly as before — pinned by rest_test.rego).

- **2026-08-05** — Close the role-only M2M path on the SCA ceremony; widen the shared-client
  identity rule to `scaChallenge.consume` FIRST (#3734). `operator-sca-write` was role-only over
  the whole `scaChallenge.*`/`device.*` families, so both M2M clients (HUMAN-classified,
  ROLE_OPERATOR) were admitted to every SCA write — including `scaChallenge.verify` (the OTP
  fallback, documented human-channel-only) and any future action in those families. SCA differs
  from the other #3734 rows: the edge IS a legitimate ceremony caller (initiate/read/decide/
  consume via `service-sca-edge-m2m`; `device.*` via base `edge-service-notification`) and the
  shared client legitimately consumes challenges — delegation-service's grant-accept ceremony
  and document-service's DOCUMENT_SIGNING ceremony (ADR-0169 D2) both POST
  `/api/v1/sca/challenges/{id}/consume` and rode the role-only hole until now. So the ordering
  is the #3734 "identity-scoped rule FIRST" pattern: `service-sca-shared-client-m2m` gains
  `scaChallenge.consume`, THEN `operator-sca-write` excludes every `service-account-*`
  principal. No prohibition clause: `rules.yaml`'s matrix grants no `scaChallenge.*`/`device.*`
  write to ROLE_OPERATOR, so `matrix-allows` admits nothing the exclusion doesn't close (unlike
  balance/ledger/fraud). Falsified by `sca_rest_ext_test.rego` — stripping the exclusion turns
  4 of 11 red; removing the consume widening turns the delegation/document regression test red.
  The ext moved from a generator heredoc to a standalone `sca_rest_ext.rego` so `opa test` can
  load it. Rollback: revert the ext — the ceremonies keep working via the widened identity rule.
- **2026-06-11** — Domain metrics + outbox-backlog gauge (ADR-0077 / ADR-0079). New `DomainMetrics`
  call sites: `scaChallengeIssued(method)` after a challenge is persisted in `initiate`, and
  `scaChallengeResolved(method, outcome)` once a challenge reaches a terminal `ScaStatus`
  (`COMPLETED`/`FAILED`/`EXPIRED`/`CANCELLED`) in the `verify` paths; plus a `@Startup`
  `ScaOutboxBacklogGauge` publishing the PENDING+FAILED outbox count as `openbank.outbox.backlog`
  tagged `service="sca"`. Touches the **I — information disclosure** row: the only labels are the
  closed `ScaMethod` enum + a closed outcome set + the static service name — no challenge id, party
  id, OTP, or device credential ever becomes a label. **No new endpoint, data flow, or trust
  boundary** (metrics are scraped cluster-internally on `/q/metrics`). Risk class = **confidentiality
  / observability**. Mitigated by `ScaServiceTest` (issued + resolved tag/outcome assertions,
  including no-emit on a retryable failure) and `ScaOutboxBacklogGaugeTest`. Rollback: revert the
  commit (no DB or schema change).
- **2026-06-05** — ADR-0021 decoupled device approval. Closes audit **K2** (push/biometric SCA
  bypass): `verify` consults a signature-verified, dynamic-linked decision instead of returning
  `true`. New surface: `POST /api/v1/sca/parties/{partyId}/devices` (enrol) and
  `POST /api/v1/sca/challenges/{id}/decision` (record), both `@Authorize`-gated. New table
  `sca_enrolled_devices` (durable public keys); decisions held transiently (Redis, TTL=challenge).
  Risk class = **EoP/spoofing** — primary control is signature verification + dynamic-linking +
  ownership check; fail-closed on any malformed assertion. Rollback: `DROP TABLE sca_enrolled_devices`
  (forces re-enrollment, never a silent bypass).
- **2026-05-30** — Added `sca_outbox_seq` (Hibernate fix). Additive DDL only — no new flow/surface/
  boundary, no change to challenge/verify logic. Risk class = **availability**, mitigated by
  `HibernateSequenceGuardTest`. Rollback: `DROP SEQUENCE`.

- **2026-08-02** — **New inbound trust edge: the `delegation` namespace.** `#3414` added
  `delegation` as an allowed ingress peer in this component's `network-policies.yaml`, so
  `delegation-service` can now reach this service's API from inside the cluster. A NetworkPolicy is
  coarse — it decides *reach*, not *permission* — so the actual authorization is unchanged and still
  rests on OIDC (`@RolesAllowed`) plus the OPA sidecar (ADR-0034); this edge widens who may attempt a
  call, not who may succeed. Risk class = **elevation of privilege** if a policy gap exists on an
  endpoint that previously had no in-cluster caller: network reach was an implicit second control for
  such endpoints and is now gone for this peer. Per ADR-0232 delegation-service holds
  `DelegationGrant` and enforcement stays with the product services, which build their own local
  projection — so a compromised or buggy delegation-service should not be able to grant access it
  never had, and that property is the mitigation this edge depends on. Rollback: drop the
  `namespaceSelector` entry for `delegation`. Recorded here because #3431's measurement showed this
  change landed with no threat-model update.

- **2026-08-06** — **Error-envelope disclosure: `ApiError.timestamp` now carries a real
  clock reading.** `#3874` — the shared `ApiError` envelope (openbank-libs-domain) defaulted
  `timestamp` to `Instant.EPOCH` and no call site passed it, so every error this service served
  carried `1970-01-01T00:00:00Z`. The field is now a required constructor argument, stamped
  `Instant.now()` at construction in this service's mappers. **Risk class = information
  disclosure**, and it is a deliberate, bounded increase: error responses now reveal the server's
  wall-clock time to any caller who can provoke an error, including an unauthenticated one on
  endpoints that answer 401/403 through this envelope. Assessed as acceptable — the value is
  second-resolution UTC already implied by the HTTP `Date` header on the same response, so it
  discloses nothing a caller could not already read, and it is what makes the envelope's own
  instruction ("contact support with traceId=…") actionable by letting support bind a trace to a
  moment. No new field, no new endpoint, no authorization or ingress change; the response SHAPE is
  unchanged (`string`/`date-time`), so no API-contract bump under ADR-0048. Not a timing oracle:
  the stamp is taken when the error object is built, not measured against request start, so it
  does not expose per-request processing duration. Rollback: revert; the field is
  serialisation-only and nothing persists it.
