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
| **S**poofing | A person's device key enrolled to a COMPANY party approves any challenge raised for that company, unattributed (#10281 item 1) | Enrolment reads the party's register type from party-service and refuses anything but `INDIVIDUAL`/`SOLE_TRADER` (422); a register that cannot answer fails closed (503). Pre-existing entity-bound rows: inventory + reviewed purge (`openbank-sca-service/scripts/purge_entity_bound_devices.py`, dry-run default, `--expect-count` gate) |
| **T**ampering | A co-signature on one business approval spent on another approval, or on an edited payload (#10281 item 2) | `APPROVAL` purpose: the device signs the canonical `id|decision|APPROVAL|approvalRequestId|payloadSha256[|amount|currency|creditorIban]` (amount `0.00` form, currency and IBAN upper-case and compact); initiate refuses an `APPROVAL` challenge without both (400); consume compares both, so a mismatch is 409 and does not burn the challenge |
| **R**epudiation | A decision record that names only a credential cannot answer "who approved" once the transient decision expires (#10281 item 3) | The resolved challenge row carries `decided_by_party_id` + `decided_by_credential_id` (from the enrolled device) and `on_behalf_of_party_id` (the entity as context); both are returned by consume |
| **T**ampering | An `Idempotency-Key` reused with a different initiate body is answered with the first challenge, so the caller acts on a challenge minted for another purpose or redirect (#10946) | The key is claimed atomically in Redis (`reserve`) together with a SHA-256 fingerprint of method, path and the canonical request body (libs `RequestFingerprints`: sorted keys, null == absent) BEFORE any challenge is minted. A different body under the key is 409 `IDEMPOTENCY_KEY_REUSED`, a concurrent duplicate is 409 `IDEMPOTENCY_REQUEST_IN_PROGRESS`; neither mints anything. A failed initiate releases its marker. Residual: records stored before the fingerprint existed replay without a check until their 300 s TTL lapses; there is no DB-level check, so the binding lasts only as long as the Redis record |

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

- **2026-10-03** — **SENT outbox rows are purged after 7 days** (ADR-0329, ADR-0327 D8). `sca_outbox` kept every SENT row, payload included, indefinitely: `purgeSent` existed and nothing called it. The shared libs-runtime `OutboxSentRetentionJob` now deletes SENT rows whose `sent_at` is older than `openbank.outbox.retention.sent-days` (default 7) nightly in bounded batches; its v1 repository opts in by delegating `SentOutboxRetention` to `PanacheOutboxRetention`. PENDING, FAILED, DISPATCHING and DEAD rows are never touched. For this service the payloads at stake are `DEVICE_ENROLLED` (party id, credential id) and `SCA_DEVICE_DECIDED` with the signed payment payload (creditor IBAN). This closes the residual the durable-decision entry below records: the outbox copy no longer outlives the 1 826-day retention of `sca_device_decisions`. Information disclosure: shrinks the window in which a database read (replica, backup, operator query) exposes past event payloads. No new endpoint, caller or privilege; replaying an event older than 7 days now comes from the broker or audit-service, not this table.

- **2026-10-06** — SCA approval GET and pending-list responses expose `makerActorKind` from
  the durable approval record (#11588). The `ROLE_OPERATOR`/`ROLE_ADMIN` and
  `scaChallenge.approval.read` checks are unchanged. This is informational maker provenance,
  not a new permission or a substitute for the maker/checker identity comparison; old records
  return `UNKNOWN` rather than inferring a human or agent from a display name. The real-HTTP
  durability test reads an `AI_AGENT` record through GET as well as PostgreSQL and the outbox.
- **2026-10-03** — **Operator approvals publish what they bind (`summary`).** `GET
  /api/v1/sca/approvals` and `/{id}` now return a `summary` rendered by `ScaApprovalSummaryRenderer`
  (through the new optional libs-runtime `ApprovalSummaryRenderer` hook) from the same arguments the
  #11675 fingerprint covers, once, when the approval is issued, and stored in the existing V15
  `summary` column — so it is the summary of what was bound, never re-derived (a later revocation
  does not rewrite it; `ScaFourEyesFlowIT` proves it on an executed approval). **STRIDE-I (what it
  reveals, and why):** to OPERATOR/ADMIN only (`scaChallenge.approval.read`), the party id (already
  the approval's `resourceId`), an 8-character credential handle and device-id handle, the
  algorithm, the enrolment date, the first 8 hex of the SHA-256 of an enrolling public key, and for
  a consume the challenge purpose (only when the challenge belongs to the stated party), amount,
  currency and the creditor masked to its last 4 alphanumerics. That is the minimum a checker needs
  to recognise the target and compare it with what the maker claims; without it the checker
  approved blind. It never carries the public key, a full IBAN or any secret: caller-supplied
  values are shape-checked (amount, ISO currency, card action, SHA-256 hex) or shortened, so a
  crafted field cannot be echoed or impersonate another `key=value`. Before this change the shared
  generic summary — the full argument dump, public key and creditor IBAN included — was stored but
  never served; for these three actions it is no longer stored either. The `summary` is not in the
  `SCA_OPERATOR_APPROVAL_CHANGED` event (payload unchanged). A renderer failure refuses the call
  (503) rather than issuing an approval with the generic dump. **Tampering:** none — the summary is
  informational, the fingerprint alone decides a match. **Residual:** the two shared M2M
  service-accounts that can read the queue (see the slice 9b entry) now also read the summary.
  **Rollback:** revert the binary; stored summaries are inert text and need no data change.
- **2026-10-03** — **Durable challenge and device lifecycle** (#10041 slice 9a). Four changes,
  one risk class: integrity and non-repudiation of the decoupled-approval ceremony.
  (1) Optimistic `version` on `sca_challenges` (V12): every lifecycle write, including the
  compare-and-consume, carries the version it read, so a stale verify cannot erase a consumption or
  overwrite a newer attempt count; expiry is now inclusive at the exact deadline. Conflicts fail
  closed (422). (2) Device decisions move from Redis (`SET` with TTL) to `sca_device_decisions`
  (V13): the first signature-verified decision, the exact signed payload, the deciding party
  (#10281 item 3) and an `SCA_DEVICE_DECIDED` outbox event commit in one transaction under a row
  lock on the challenge; a second decision is refused. Expiry limits authorisation, not evidence
  retention, and losing Redis can no longer erase an acknowledged approval. New outbound event on
  the existing `openbank.sca.events` topic, published in `openbank-contracts/openbank-sca-service/asyncapi.yaml`;
  audit-service already subscribes. (3) New inbound operation `DELETE
  /api/v1/sca/parties/{partyId}/devices/{deviceId}` (`device.revoke`, V14 `revoked_at`): revokes
  the credential and cancels its pending or completed-but-unconsumed challenges in the same
  transaction as the audit event; a revoked credential cannot decide and cannot be re-enrolled.
  `sca_rest_ext.rego` gains `device-self-revocation` (HUMAN + `ROLE_CUSTOMER` + `principal.id ==
  resource.id`); the money-path four-eyes obligation is unchanged. (4) Customer party routes
  (pending, list/enrol devices, revoke) now require a UUID party identity for `ROLE_CUSTOMER`
  callers rather than skipping the ownership check when `sub` is not a UUID. Operator approvals
  (four-eyes resolution endpoints) are NOT in this change; they follow in slice 9b. Rollback: the
  three migrations are additive and must be retained; draining unexpired challenges is required
  before any binary rollback, and a rollback after the first revocation is unsafe (older binaries
  ignore `revoked_at`) — recover forward. Runbooks: `docs/runbooks/sca-lifecycle-conflicts.md`,
  `sca-durable-decisions.md`, `sca-device-revocation.md`.
  **Retention (STRIDE-I).** The durable evidence row keeps the exact signed payload, which for a
  payment embeds amount and creditor IBAN, so moving decisions out of a TTL store would otherwise
  have made that disclosure surface unbounded in time. It is now bounded: `openbank.sca.decision-retention-days`
  (default 1826 days, the AMLD Art. 40 record-keeping period for transaction evidence) and a daily
  `DecisionEvidencePurgeScheduler` (`suspend` `@Scheduled`, batched oldest-first by
  `idx_sca_device_decisions_decided_at`, liveness `sca-decision-evidence-purge`, counter
  `openbank.sca.decision.evidence.purged`) deletes rows decided before the cutoff; the challenge
  row is untouched. Residual: the `SCA_DEVICE_DECIDED` copy of the same payload in `sca_outbox`
  is not covered by this purge — sca-service does not call the shared outbox `purgeSent` today, so
  SENT outbox rows remain unbounded until outbox retention is wired; the audit store's copy follows
  audit-service retention.
- **2026-09-26** — Challenge initiation binds its Idempotency-Key to a request fingerprint
  (#10916, #10946). The key is reserved atomically before a challenge is minted. Same key + same
  body still replays; same key + different body is now 409 `IDEMPOTENCY_KEY_REUSED` instead of a
  replay of the first challenge, and a concurrent duplicate is 409
  `IDEMPOTENCY_REQUEST_IN_PROGRESS`. No new endpoint, caller or privilege; the inbound surface
  gains one error response (409, two codes).
- **2026-09-26** — **AuthzProducer replaced by the shared libs-runtime OPA PDP producer** (PR
  #10952). The service-local `infrastructure/authz/AuthzProducer.kt` is deleted;
  `application.yaml` now sets `openbank.authz.opa-pdp-producer.enabled: true` to opt into
  `OpaPolicyDecisionPointProducer` (openbank-libs-runtime), gated by that build
  property (`enableIfMissing = false`), so the wiring stays off for any service that does not set
  it. The producer is NOT `@DefaultBean`: if this service's own `src/main` ever produces a second
  `PolicyDecisionPoint` bean, `quarkusBuild` fails loudly on an ambiguous CDI dependency instead of
  one silently displacing the other. Same `opa.url`/`opa.path`/`opa.timeout-ms` defaults as the deleted producer
  (`http://localhost:8181`, `/v1/data/openbank/rest/allow`, 500 ms) and the same fail-closed
  behaviour on OPA sidecar failure — `OpaSidecarPolicyDecisionPoint` itself is unchanged, only its
  construction site moved from a per-service copy to the shared producer. No new caller, endpoint,
  network edge or privilege; no new trust boundary.

- **2026-09-19** — Business approvals and attribution (#10281 items 1 and 3, plus the SCA half of
  item 2). New outbound call sca → party-service (`GET /api/v1/parties/{id}`, `partyType` only,
  service token) gates device enrolment to natural persons; fail-closed on a register outage,
  so enrolment availability now depends on party-service. New `APPROVAL` purpose with dynamic
  linking to an approval request and its payload hash. Challenge rows record the deciding party.
  No new caller or privilege; the purge of existing entity-bound credentials is a documented,
  reviewed procedure and has not been executed.
- **2026-09-09** — Extended challenge consumption with an optional opaque `reference` for
  approval-group management dynamic linking (ADR-0284 D3). This changes the existing inbound REST
  trust boundary, but adds no endpoint or caller: delegation-service remains the authorised M2M
  consumer. When supplied, the value must exactly match the reference stored on the challenge before
  the one-shot transition to `COMPLETED`; a mismatch fails closed and leaves the challenge
  unconsumed. The reference is a SHA-256 fingerprint over operation, owner, roster, threshold and,
  for revisions, group id plus expected revision, so a completed ceremony cannot authorise altered
  authority. Compatibility is deliberate, in both directions. A challenge created WITHOUT a reference is
  legacy and binds exactly as before: a consumer that supplies one is still accepted, so today's
  app, which creates DELEGATION_GRANT challenges without the preview `scaReference`, keeps working
  while delegation-service starts sending it. A challenge created WITH a reference for an
  operation-bound purpose (DELEGATION_GRANT, DELEGATION_APPROVAL_GROUP, SAVINGS_WITHDRAW_APPROVAL)
  is strict: a differing or absent supplied reference is refused. For every other purpose the
  field is an informational payment remittance reference that payment consumers do not restate, so
  it is compared only when supplied. The binding therefore strengthens per challenge as clients
  opt in, never regresses an existing one (`ScaChallengeReferenceBindingTest`).
  Risk class = **tampering / elevation of privilege**. Verified by SCA service tests and the
  delegation→SCA Pact contract. Rollback: revert the optional field after delegation callers stop
  sending it; no schema or stored-data migration is involved.
- **2026-09-07** — Idempotency contract of the two creation POSTs verified and documented
  (ADR-0296, burn-down #8351). No code change: challenge initiation already replays on
  Idempotency-Key/X-Request-ID via the idempotency store (X-Idempotency-Replayed: true, 300 s
  window), and device enrollment already dedups on the credentialId natural key
  (`credential_id UNIQUE`, V4) — same party replays, a different party is refused. Spec-only:
  the OpenAPI now declares the replay headers and the credentialId semantics. No new endpoint,
  caller, privilege or control bypass.
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

- **2026-09-03** — `initiate` refuses `ScaMethod.TOTP` instead of minting a challenge nobody could
  ever satisfy (#8432). `preferredMethod` on `POST /api/v1/sca/challenges` comes straight off the
  request body, and TOTP had no delivery transport at all: a challenge was generated, stored, and
  the code sent nowhere, so whatever it was meant to authorise — a payment, a consent, a card
  action — could never proceed. `ScaResource` gained one new response class, no new endpoint: a
  `ScaMethodNotDeliverableMapper` maps the new `ScaMethodNotDeliverableException` to 422, same
  status family as an already-documented "valid request, cannot proceed" answer (expired
  challenge). Risk class = **denial of authorisation**, not an authentication bypass —
  `ScaChallenge.fail` still caps attempts on any challenge that *is* minted, so an undelivered code
  was never brute-forceable within its life; the defect was availability of the authorisation
  path, not its integrity. No new trust boundary: the check runs before a challenge exists, on the
  same authenticated `initiate` call, against a caller-supplied enum the service already validated.
  Rollback: revert the commit; TOTP goes back to silently minting a dead challenge.

- **2026-09-20** — **New inbound edge: a parallel private-CA mTLS listener (8443, client auth
  REQUIRED, TLSv1.3; server cert `sca-service-internal-tls`), the same shape as account-service's and
  document-service's.** HTTP/8110 stays for existing callers (document-service, consent-service).
  Note the two certificates in this namespace are different objects: `sca-internal-tls` is this
  service's CLIENT certificate for its read of party-service; `sca-service-internal-tls` is the new
  SERVER certificate. The only caller on 8443 is account-service's `SavingsProposalService`
  (`GET /api/v1/sca/challenges/{id}`, `POST /{id}/consume`) as the shared
  `service-account-openbank-services`. Both methods are
  `@RolesAllowed("ROLE_API","ROLE_OPERATOR","ROLE_ADMIN")` + `@Authorize("scaChallenge.read" /
  "scaChallenge.consume")`, and `sca_rest_ext.rego`'s `service-sca-shared-client-m2m` rule already
  admits that principal for both — no policy change. Before this, account-service had no
  `SCA_SERVICE_URL` and dialled localhost, so the edge existed in code but never reached this
  service, and its client sent no bearer at all (both fixed together, #10383). **Risk class:**
  integrity of challenge consumption — a consume is state-changing, so the caller is now both
  mutually authenticated at the transport and identified by an M2M token at the application layer,
  where previously it was neither. Rollback: drop the listener env and account-service's env var.

- **2026-09-20** — **Correction, and the fix: the 8443 listener's client-certificate validation was
  declared but not in effect.** Earlier entries describe this listener as "client auth REQUIRED".
  That posture was expressed only as the container env `QUARKUS_HTTP_SSL_CLIENT_AUTH`, and
  `quarkus.http.ssl.client-auth` is a **build-time** property: Quarkus fixes it into the image at
  build time and ignores a differing runtime value (it says so in the boot log). The deployed
  listener therefore ran with the default, `none` — server-authenticated TLS, encrypted in transit,
  but the caller's certificate was not demanded or validated. The transport-confidentiality claims in
  the earlier entries hold; the caller-authentication half did not, and those entries should be read
  with this one. Completed here by setting `quarkus.http.ssl.client-auth: required` in this service's
  `application.yaml`, the file the image is built from, so the value is baked rather than injected;
  the gitops env is kept in the same spelling so manifest and image cannot disagree. Every declared
  caller of this listener already mounts a private-CA client certificate and names it on its
  rest-client, so no caller changes posture. **Risk class:** authentication of east-west callers —
  restored to what the design always stated. Rollback: revert the property (and expect the listener
  to return to server-only TLS).

- **2026-10-03** — **Durable four-eyes for SCA operator actions (#10041 slice 9b).** New surface:
  `GET /api/v1/sca/approvals`, `GET /api/v1/sca/approvals/{id}` and `PATCH /api/v1/sca/approvals/{id}`
  (`@RolesAllowed(ROLE_OPERATOR, ROLE_ADMIN)`, `@Authorize("scaChallenge.approval.read"/".decide")`,
  admitted by the existing `operator-sca-write` (decide; excludes `service-account-*`) and base
  `operator-read-any` (read) rules — no rego or `rules.yaml` change). SCA now wires an `ApprovalStore`
  bean, so with `authz.four-eyes.enforce=true` (default false, unset in every manifest) the gated
  actions `device.enroll`, `device.revoke` and `scaChallenge.consume` park with 202 instead of
  failing closed with 503. **Four-eyes:** the paused request runs only on a retry carrying an
  `APPROVED` approval for the same action and maker whose request fingerprint (SHA-256 of endpoint +
  arguments, #11675) equals the retry's; the claim is one-use. So an approval for one device cannot
  revoke another, and one for an enrollment cannot enrol a different credential, key or algorithm.
  **Self-approval prevention:** `PostgresApprovalStore.decide` refuses `decidedBy == makerId` before
  the status check, under a row lock, and V15 repeats it as a table CHECK
  (`decided_by <> maker_id`), so neither a REST-layer omission nor a direct writer can record one.
  **Identity of the decider:** the checker id is the authenticated `principal.name`, resolved by
  `ApprovalEndpointSupport` after the body check, never taken from the request body — the same
  representation the interceptor records as the maker, so the comparison is between like values;
  `ScaOidcApprovalIT` proves it with real Keycloak tokens rather than injected identities. Every
  transition commits in one transaction with an `SCA_OPERATOR_APPROVAL_CHANGED` outbox event naming
  its actor, so who made, who decided and who claimed survive the authorization's expiry (a failed
  audit insert rolls the transition back). **Risk class:** segregation of duties on device
  credentials and settlement-gate consumption. **Residual:** the shared M2M client still classifies
  as HUMAN with `ROLE_OPERATOR` in some realms. Measured with `opa eval` on the generated bundle
  (2026-10-03): `service-account-openbank-services` and `-edge` are DENIED
  `scaChallenge.approval.decide` but ALLOWED `scaChallenge.approval.read` via `operator-read-any`,
  so they can see the queue (action, party id, maker id — not the request summary or fingerprint; the summary since the `summary` entry above)
  but cannot decide it (least-privilege restriction deferred to slice 10). **Retention:**
  `OperatorApprovalPurgeScheduler` (`suspend` `@Scheduled`, daily `0 45 3 * * ?`, bounded batches
  and a per-run cap, liveness `sca-operator-approval-purge` and counter
  `openbank.sca.operator.approval.purged` registered only when enabled) deletes approvals whose
  authorization expired more than `openbank.sca.approval-retention-days` (default 1826, AMLD Art. 40
  — the approval is part of the authorisation evidence for the operation it gated) ago, via V15
  `idx_sca_operator_approvals_retention`. Expiry, not status, makes a row terminal: an expired
  PENDING approval can never be decided or claimed, so it ages out on the same clock. A still-live
  approval never matches, because the cutoff lies in the past. The outbox events are not touched. A legal hold is `openbank.sca.approval-purge.enabled=false`. **Rollback:** revert
  the binary with four-eyes enforcement off; keep `sca_operator_approvals` and its outbox rows.
