<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->
# Threat model — sepa-instant-service (SCT Inst)

- **Date:** 2026-06-17
- **Status:** Lightweight STRIDE/DFD (ADR-0030 D2). **Money-path** bounded context.
- **Service ADR:** see `docs/adr/`; platform controls per ADR-0029/0030/0034.

## 1. Scope & purpose

SEPA Instant Credit Transfer (SCT Inst): initiate, query, query-by-debtor, recall. Payments are
**near-irrevocable and settle in seconds** — the window to catch fraud is minimal, raising stakes
above batch SEPA.

## 2. Data flow (DFD)

```
[Channels/Operators] --> (REST /api/v1/sepa-instant) --> [sepa-instant-service] --> [(Postgres: payments + outbox)]
                                                                |
                                                                +--> [outbox dispatcher] --> [Kafka events] --> clearing/scheme
                                                                |
                                                                +--> [fraud-service] (shadow, OIDC CC / mTLS, fail-open)
   recall <-- (POST /{paymentId}/recall)
```

- **External entities:** initiating channels/operators, SCT Inst scheme/clearing.
- **Trust boundaries:** caller↔service (mTLS+OIDC+OPA); service↔Postgres/Kafka; scheme edge;
  service↔fraud-service (OIDC client-credentials + mTLS, internal cluster-only, shadow/read-only).
- **Assets:** instant payment instructions, recall requests.

## 3. Authn/Authz

- Initiation/recall must be role-gated (payments) + OPA enforce; SCA for customer-initiated.

## 4. STRIDE

| Threat | Vector | Mitigation |
|---|---|---|
| **S**poofing | Forged instant initiation | OIDC + role; mTLS |
| **T**ampering | Amount/beneficiary change before send | Server-validated, immutable once accepted; audit |
| **R**epudiation | Deny initiating an instant payment | AuditEvent + SCA evidence + correlation id |
| **I**nfo disclosure | Debtor payment history (`/debtor/{id}`) leak | AuthZ scoping to owner/role |
| **D**oS | Flood to exhaust instant-rail capacity | Rate limit; idempotency |
| **E**oP | Unauthorized recall to claw back funds | Recall gated by distinct authority; audit; reason required |

## 4a. Four-eyes approval (ADR-0155) — STRIDE supplement

`POST /{paymentId}/recall` (`sctInstPayment.recall`) is a money-path action that OPA
(`rest.rego`) can flag `four_eyes_required` — recalling a SETTLED SCT Inst payment claws back
funds that have already left the debtor's account on a near-real-time rail, so a single actor
being able to trigger it unilaterally is the highest-stakes gap this service has. New endpoint
`PATCH /api/v1/sepa-instant/approvals/{id}` lets a DIFFERENT operator decide the resulting
`PendingApproval`; the maker retries `POST /{paymentId}/recall` with an `X-Approval-Id` header.
**`authz.four-eyes.enforce` stays `false` in this PR** — the `ApprovalStore`/endpoint are wired
(mirroring the sepa-payment pilot), but blocking is a deliberate follow-up flip, not bundled
here (see ADR-0155, issue #413).

| STRIDE | Threat | Mitigation |
|---|---|---|
| **S**poofing | A caller other than an operator decides an approval | `@RolesAllowed("ROLE_OPERATOR","ROLE_ADMIN","ROLE_PAYMENTS")` + OPA `@Authorize(action="sctInstPayment.approval.decide")` on the decide endpoint |
| **E**oP | The maker approves their own recall request (self-approval defeats maker-checker on an already-settled, near-irrevocable payment) | `ApprovalStore.decide` throws `SelfApprovalNotAllowedException` (mapped to 403) when `decidedBy == makerId` — enforced in the domain port itself, not just the REST layer, and `makerId`/`decidedBy` both resolve via the same `.principal.name` extraction (interceptor vs. `SecurityIdentity`) so the comparison can't silently mismatch for the same real person |
| **T**ampering | A stale, mismatched, or already-consumed `X-Approval-Id` is replayed to unlock a different recall | `AuthorizeInterceptor` requires the approval's `action` + `resourceId` + `makerId` to match the CURRENT request exactly, `status == APPROVED`, and marks it `EXECUTED` (one-time use) on success; any mismatch re-issues a fresh pending approval instead of proceeding |
| **R**epudiation | No record of who approved a recall clawing back settled funds | `PendingApproval.decidedBy` + `decidedAt` recorded in the approval record itself (Redis, TTL-bounded — see ADR-0155 Negative consequences: not yet a permanent audit trail) |
| **I**nfo disclosure | Approval id enumeration reveals payment/action metadata to an unauthorized caller | `find`/`decide` require the caller to already hold a valid, role-gated session; the id itself is a random UUID (`RedisApprovalStore`, not sequential) |
| **I**nfo disclosure | (issue #5679) `GET /api/v1/sepa-instant/approvals` lists every pending four-eyes request with its `makerId` and age | Role-gated `ROLE_OPERATOR`/`ROLE_ADMIN`/`ROLE_PAYMENTS` + `@Authorize(action = "sctInstPayment.approval.read")`; the payload carries approval metadata only — the action name, the resource id and who asked — never payment/account details. Limit clamped to 200 — an unbounded query parameter over a Redis scan is a trivially reachable amplification. Deliberately NOT filtered to exclude the caller's own requests: hiding a maker's request from them would not stop them attempting it (the guard is in `RedisApprovalStore.decide`, server-side) and would only make the queue lie about its own depth |
| **D**oS | Flooding `POST /{paymentId}/recall` to exhaust Redis with pending approvals | Bounded by the same rate-limit/idempotency controls as the gated endpoint itself; each `PendingApproval` is TTL-bounded (86400s) so abandoned records expire |

**DFD update:** adds `Operator (checker) → GET /api/v1/sepa-instant/approvals → Redis (approval:*)`
and `Operator (checker) → PATCH /api/v1/sepa-instant/approvals/{id} → Redis (approval:*)`
alongside the existing `POST /{paymentId}/recall` edge; the maker's retry reuses the existing DFD
edge.
**Risk class:** integrity (segregation of duties on a fund-clawback action) + confidentiality
(approval record scope).
**Rollback:** `authz.four-eyes.enforce=false` (default) — the endpoint and store exist but do
not change any existing request's outcome until explicitly flipped.

## 5. Residual risks / assumptions

- **Irrevocability** ⇒ pre-send fraud checks + SCA are the key controls; post-hoc recall is best-effort.
- Idempotency-key mandatory (instant retries must not double-send).
- **Four-eyes `PendingApproval` records are TTL-bounded (Redis), not a permanent audit
  trail** (ADR-0155) — a durable-audit requirement for "who approved what, forever" would
  need an additional store; not implemented in this PR.

## 6. Change log

- **2026-09-30** — Fraud verdict mapping (#4403 prerequisite, PR #11614). `FraudScoringAdapter.mapVerdict`
  folded any verdict outside `ALLOW | CHALLENGE | REVIEW | DECLINE` — including a blank one — into
  a non-synthetic `ALLOW`, so an unreadable answer from fraud-service was indistinguishable from a
  clean score at every layer that reads the outcome. `FraudVerdict.UNKNOWN` now names that case,
  counted apart from real and synthetic outcomes (`result="unrecognised"`) with the degraded gauge
  at 0, since the scorer was reachable. **No trust boundary, edge or privilege changed**; the
  verdict is still shadow-only, so payment decisions do not change. The log line records the event;
  the counter feeds `FraudScoringUnrecognisedVerdict`, which warns on an unreadable score.
  Mitigated by `FraudScoringAdapterTest` (an unrecognised and a blank verdict are `UNKNOWN`, never
  a clean `ALLOW`; red against the old mapper).
- **2026-09-27** — `ApprovalResource`'s body (limit clamping, null-body 400, unknown-id 404,
  checker id resolution from `SecurityIdentity`, self-approval propagation, wire DTOs) now
  delegates to shared `com.openbank.libs.approval.web.ApprovalEndpointSupport` (libs-runtime,
  issue #10915/#11031). Paths, `@RolesAllowed`/`@Authorize` values, status codes, JSON field
  names and `openapi.yaml` are unchanged — every row in §4a above still describes the deployed
  behaviour; only the `@Path`/`@RolesAllowed`/`@Authorize`/`@Tag` annotations remain per-service,
  and `checkerId(identity)` is resolved AFTER the null-body check (same ordering `decide`
  documents in libs-runtime, fixed in #11033/#11047).

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

- **2026-08-24** — Synthetic-journey taint now propagates over this service's existing internal REST clients through `SyntheticTaintClientFilter` (ADR-0252, #4348). This adds no caller, endpoint, network-policy edge, privilege or payment-control bypass: screening and SCA still run. It preserves the marker before a downstream persistence/event boundary; a fleet gate requires every new client to choose propagation or a reasoned external boundary.

- **2026-08-19** — `ApprovalResource` served only `PATCH /{id}` (decide), so a
  `sctInstPayment.recall` four-eyes decision parked at 202 was discoverable only by whoever had
  been handed its approval id out of band — the ceremony completed only if the two operators were
  already talking, and the 24h Redis TTL then expired the request silently otherwise (issue #5679,
  mirroring sanctions #3472, ledger and domestic-payment). Added
  `GET /api/v1/sepa-instant/approvals` (§4a new I row); additive-only OpenAPI change (1.4.0 ->
  1.5.0, ADR-0048).
  - **Checked the existing decide endpoint's own authz posture while here** (verify-by-effect, not
    by appearance): `opa eval` against the real `rest.rego` + `rules-opa-data.yaml` bundle showed
    `sctInstPayment.approval.decide` resolving **`allow=false` for a real ROLE_OPERATOR** — the
    action was missing from `rules.yaml`'s `role_action_matrix` (present for the sibling
    `sepaPayment.approval.decide`, absent for this service's own `sctInstPayment.approval.decide`
    since the four-eyes gate for this rail was wired). The decide endpoint has therefore been
    403ing every operator in any `AUTHZ_ENFORCE=true` environment since it shipped — same shape as
    the balance-service gap found by #5686/#5690. **Fixed** by adding the matrix grant (mirroring
    `sepaPayment.approval.decide`'s entry) to both `role_action_matrix.ROLE_OPERATOR` and
    `shared_m2m_matrix_write_grants.declared`, then regenerating `rules-opa-data.yaml` and every
    service's OPA bundle (a `role_action_matrix` edit restamps the fleet). Verified with `opa eval`:
    `sctInstPayment.approval.decide` now resolves `allow=true`/`reason=matrix-allows` for
    ROLE_OPERATOR, and a non-operator role (`ROLE_KYC_OPENER`) still resolves `allow=false`.
  - **Known residual, not fixed here**: `matrix-allows` is role-only, and the deployed realm
    template gives `service-account-openbank-edge` `ROLE_OPERATOR` in at least one environment (see
    root `CLAUDE.md`'s realm-drift note) — the same exposure balance-service closed with a
    per-service `prohibited` rule in `balance_rest_ext.rego`. sepa-instant has no such standalone
    ext-rego file to extend (its REST extension is a heredoc inside
    `gen-sepa-instant-opa-bundle.sh` with no `prohibited` block at all today), and there is no
    verified in-repo M2M caller of `ApprovalResource` (the gen script's own comment already notes
    this for `sctInstPayment.create`). Building a new prohibition mechanism was out of scope for
    this PR's mirrored fix; tracked as follow-up under issue #5679's own money-path-first ordering.
  - **Rollback:** revert both commits independently — the matrix grant only changes an OPA
    `allow` decision (advisory in this environment, `authz.four-eyes.enforce=false`), and the new
    `GET` is additive.

- **2026-08-09** — Fraud shadow scoring's fallback is now observable (#4221). **No new trust
  boundary and no new caller**: the outbound edge to fraud-service (OIDC client-credentials + mTLS,
  cluster-internal, shadow) is the same edge, and the verdict is still *observed, never enforced* —
  the caller logs a non-ALLOW and proceeds identically either way. What changed is that a failure of
  that edge is no longer indistinguishable from a clean payment.
  - **The property at stake is detectability, not integrity.** `catch (Exception)` returned a
    synthetic ALLOW down the same silent branch a real ALLOW takes, so fraud scoring being wholly
    down and every payment being clean produced identical observable behaviour. A control nobody
    can see fail is a control nobody knows they have lost.
  - **Mitigation**: the synthetic answer is flagged on the outcome (`FraudScoreOutcome.synthetic`),
    counted, and exported as the `openbank_fraud_scoring_degraded` gauge, where **`-1` means never
    attempted** — deliberately distinct from a healthy `0`, because a counter that has never been
    incremented is not created at all and an alert on it matches nothing, forever.
  - **`Throwable`, not `Exception`**, and this is a real change in fault containment: a fault
    crossing into a rest-client or fault-tolerance interceptor can surface as an `Error`, which the
    previous `catch (Exception)` did not hold. An `Error` escaping here would propagate out of a
    path whose entire contract is that it cannot affect the payment. Verified against `origin/main`:
    a `NoClassDefFoundError` escapes the old catch and the containment test fails.
    `CancellationException` is rethrown — cancelling the caller's coroutine is not a fraud-service
    outage and must not be reported as one.
  - **Fail-open is retained deliberately.** Failing closed would stop payments on a money-path rail
    to protect a value nothing reads. Real enforcement is tracked separately (#4403); until then
    this service must not pretend to have a fraud control it does not have.
  - **Rollback**: revert the commit; the previous behaviour was a silent synthetic ALLOW.

- **2026-05-30** — Added `sct_inst_outbox_seq` (Hibernate fix). Additive DDL only — no new flow/
  surface/boundary. Risk class = **availability**, mitigated by `HibernateSequenceGuardTest`.
  Rollback: `DROP SEQUENCE`.
- **2026-06-11** — Added outbox-backlog gauge (`openbank.outbox.backlog`, tagged `service="sepa-instant"`)
  + `countProcessable()` on the outbox port (ADR-0077 / ADR-0079). Touches the **I — information
  disclosure** row: a new domain metric. **No new data flow, endpoint, or trust boundary** — it is a
  read-only `COUNT(*)` of PENDING+FAILED `sct_inst_outbox` rows, refreshed by a scheduled in-process
  tick (not on the scrape thread), exposed on the cluster-internal `/q/metrics`. The gauge carries no
  payment id, IBAN, amount, or PII (low-cardinality contract). **Risk class = confidentiality / metric
  cardinality** (bounded to a single per-service series). Mitigated by `SctInstOutboxBacklogGaugeTest`
  (supplier tracks the refreshed cache). No DB change; rollback = revert the commit.
  **Superseded — see the 2026-08-17 entry below**: this gauge, `countProcessable()`, and the outbox
  pipeline it measured were removed as dead code (PR #1364); the corresponding STRIDE row above no
  longer applies and has been removed from §4.
- **2026-06-17** — ADR-0084 fraud shadow scoring (observe-only). New outbound trust boundary:
  `sepa-instant → fraud-service (POST /api/v1/fraud/score, OIDC client-credentials)`.
  **Shadow = fail-open and never-enforce**: `SctInstPaymentService.scoreFraudShadow()` wraps the call
  in `.onFailure().recoverWithUni {}` — any fault (timeout, circuit-open, 5xx) is swallowed; the
  payment outcome is unchanged. `FraudScoringAdapter` applies `@CircuitBreaker` (threshold 0.3,
  30% failure ratio) + `@Timeout(3 s)`. No retry (avoid double-scoring on near-real-time rail).
  **Risk class = availability** (fault in fraud-service cannot block a payment) and **confidentiality**
  (payment amount, debtor/creditor IBAN, currency sent to fraud-service; mitigated by mTLS +
  OIDC client-credentials for service-to-service authn; fraud-service is internal, cluster-only).
  **DFD update**: add `sepa-instant → fraud-service` edge with `OIDC client-credentials / mTLS`
  trust-boundary label. No DB schema change; rollback = revert adapter + port commits.
- **2026-07-05** — ADR-0122 Phase 2: `build.gradle.kts` now declares `openbank-libs-domain` +
  `openbank-libs-runtime` directly instead of the umbrella `openbank-libs` (which already re-exported
  both via `api()`). Pure Gradle dependency-graph change — no source import changed, no new transitive
  dependency introduced, no behavior change. Attack surface, trust boundaries, and STRIDE rows above are
  unaffected. No DB change; rollback = revert the commit.
- **2026-07-08** — ADR-0155 four-eyes enforcement, rolled out from the sepa-payment pilot
  (issue #413). New endpoint `PATCH /api/v1/sepa-instant/approvals/{id}` + `ApprovalConfig`
  (Redis-backed `ApprovalStore`) + `AuthorizeInterceptor` four-eyes gate (openbank-libs-runtime,
  shared, opt-in) on `sctInstPayment.recall`. New STRIDE supplement §4a. `authz.four-eyes.enforce`
  defaults `false` — no behavior change to any existing request in this PR; flipping it is a
  tracked follow-up. No DB schema change (Redis, TTL-bounded); rollback = revert the commit (or
  leave `authz.four-eyes.enforce=false`, its default).
- **2026-08-17** — Doc correction (issue #5127), no behavior change. PR #1364 (2026-07-17) had
  already removed the dead transactional-outbox pipeline —
  `SctInstOutboxPort`/`SctInstOutboxDispatcher`/`KafkaSctInstOutboxEventPublisher`/the
  outbox-backlog gauge — after confirming nothing ever wrote to it: `KafkaSctInstEventPublisher`
  (a direct, synchronous emitter) was always the pipeline actually in use (issue #1034). This
  entry corrects §2's DFD (the `sct_inst_outbox` node is replaced with the direct
  `KafkaSctInstEventPublisher` edge that was always the real path) and removes the now-void
  metric-cardinality row from §4 STRIDE, and lands alongside a Flyway migration
  (`V4__drop_sct_inst_outbox.sql`) dropping the vestigial `sct_inst_outbox` table and
  `sct_inst_outbox_seq` sequence that PR #1364 left behind (0 rows, unused since #1034).
  **Risk class = none** — this is a documentation and dead-schema cleanup only; the live pipeline
  (direct Kafka emitter) and every trust boundary above are unchanged. Rollback: revert this doc
  commit; the migration's own rollback is stated in `V4__drop_sct_inst_outbox.sql`.
- **2026-09-21** — **Own machine identity for the SCT Inst settlement booking (#10486 batch 2).** `TransactionServiceClient` now mints its bearer from the NAMED oidc-client `m2m`, Keycloak client `openbank-sepa-instant` (`ROLE_API` only); transaction-service grants it exactly `transaction.create` (`service-sepa-instant-transaction-create`). **STRIDE-S:** a new credential. Its secret is generated by Keycloak in the live realm, stored by the owner's provisioning script at Vault KV `keycloak/sepa-instant` (`client_secret`), projected by the `sepa-instant-m2m-oidc` ExternalSecret and never seen by the repo; the env ref is `optional: false`, so an unseeded entry blocks the new pod loudly (CreateContainerConfigError) rather than calling with an empty credential. Compromise of this secret reaches only `transaction.create`, against the shared secret's 54 money-path writes. **Repudiation improves:** the upstream's OPA decision reason and principal now name this service instead of "some caller on the shared client". The service's other rest-clients stay on the shared `openbank-services` client until their own edges migrate (asserted by `M2mOidcClientIdentityWiringTest`). Rollback: revert the commit (the clients return to the shared token).
- **2026-09-21** — **AML case open moves to the service's own machine identity (#10486 batch 3).** `AmlServiceClient` (`POST /api/v1/aml/cases`) now mints its bearer from the NAMED oidc-client `m2m` the service already has for its money-path booking, Keycloak client `openbank-sepa-instant` (`ROLE_API` only), instead of the shared `openbank-services` client. aml-service admits it by identity: `@RolesAllowed` on `createCase` gains `ROLE_API`, `@Authorize("amlCase.create")` is added with the rego rule `service-aml-case-create-m2m`, and because aml-service runs `AUTHZ_ENFORCE=false` a Kotlin check (`requireNamedMachineCaller`) refuses any ROLE_API-only caller not on its four-principal list. **STRIDE-S/E:** no new credential; the existing `m2m` secret now also reaches `amlCase.create` and nothing else new. **Repudiation improves:** a referred case now names this service. **Availability:** unchanged path; if the named client were missing the call would fail and the screening gate's existing failure handling applies, exactly as for a shared-client outage. Rollback: revert the commit (the client returns to the shared token).

- **2026-09-28** — **`ApprovalEndpointSupport.decide()` now resolves the checker identity lazily,
  after the null-body check (#11047/#11061).** The prior parameter was a bare `SecurityIdentity`,
  which looked like it deferred `checkerId()` resolution past `requireNotNull(request)` but did
  not: Kotlin evaluates a call's argument expressions before the function body runs, so passing a
  caller's `lateinit var identity` as that argument threw UninitializedPropertyAccessException at
  the call site whenever the body was null — before `decide()` ever reached its own null-body
  guard, inverting the documented and tested "null body rejected before any identity is resolved"
  contract (`ApprovalNullBodyTest`, #3029). `decide()` now takes an
  `identityProvider: () -> SecurityIdentity` supplier; this service's call site (stranded on the
  older `support.decide(id, request, identity)` form by #11079 merging just ahead of this fix) is
  migrated to `support.decide(id, request) { identity }`, and the supplier is invoked only after
  `requireNotNull(request)` returns. **Risk class:** none — fixes an incorrect 500
  (uninitialized-property crash) on a malformed request back to the intended 400; no endpoint,
  authorization, self-approval or wire-shape change. Rollback: revert to the eager
  `SecurityIdentity` parameter.
- **2026-09-21** — **SCT Inst reads admit agent-service's own machine identity (#10486 batch 7).** `GET /api/v1/sepa-instant/{paymentId}` adds `ROLE_API` to `@RolesAllowed`, matching the two list endpoints that already had it. The rego extension moved out of the generator heredoc into `sepa_instant_rest_ext.rego`; the bundle regenerated byte-identical before any rule changed. It then gained `service-agent-sct-inst-read`: `service-account-openbank-agent` (`ROLE_API` only) may `sctInstPayment.list` and `sctInstPayment.read`, never create or recall. **STRIDE-E:** OPA is the whole control for `ROLE_API` callers, which holds because `AUTHZ_ENFORCE` is on for sepa-instant. `sepa_instant_rest_ext_test.rego` denies another service account and the shared client every action (negative case run: widening the rule to a prefix turned it red); `SctInstMachineReadRbacTest` pins that only the three reads admit `ROLE_API`. **AI boundary:** the agent tools behind these reads map to `query.payments.readonly`, which only the compliance-officer charter holds; the agent gate refuses every other agent before any call. Rollback: revert the commit.
- **2026-10-03** — **Inbound amount and currency validated as kernel `Money` before the
  Idempotency-Key is looked up (#11604).** `POST /api/v1/sepa-instant` now builds a kernel `Money`
  with `Money.parseInbound(amount, currency)` in `SctInstResource`; `SubmitSctInstCommand` and
  `SctInstPayment` carry `Money`. **Tampering / input validation:** an amount that would need
  rounding to fit the currency (e.g. `1.005 EUR`, `0.000001 EUR`) or a currency that is not an ISO
  4217 code with a minor unit (`XYZ`, `XAU`, blank) was previously accepted, sanctions-screened,
  persisted at NUMERIC(20,6) and emitted on `SctInstPaymentSubmitted`; over-long currencies and
  out-of-range amounts failed only at the database flush. Each now answers **400
  `AMOUNT_SCALE_EXCEEDED` / `CURRENCY_UNSUPPORTED` / `VALIDATION_ERROR`** (libs-runtime
  `InvalidMoneyExceptionMapper`) before any idempotency lookup, screening call, row, event or
  downstream call (scheme gateway, settlement, fraud scoring) exists. The refusal names the field,
  never the rejected value. Lower-case or padded currency codes (`eur`, ` EUR `) are normalised to
  `EUR` instead of being stored verbatim (or failing at the column). Valid 2dp EUR input is
  persisted and emitted byte-identically; responses and events now carry the currency scale
  (`10.50` for a request of `10.5`, `100.00` read back instead of the column's `100.000000`),
  numerically equal. **Residual:** a legacy row with an over-scale amount or unknown currency now
  fails to load (500 naming the row) instead of being served; the sandbox count before this change
  was 8 rows, all EUR, 0 over-scale. No new endpoint, caller, privilege or event. Rollback: revert
  the commit.
- **2026-10-03** — **Submissions refused unless the currency is EUR (#11913).** SCT Inst is a
  euro-only scheme, yet `POST /api/v1/sepa-instant` accepted any currency kernel `Money` could hold
  (`USD`, `CZK`, `GBP` were 201, screened, persisted, emitted and sent towards the scheme gateway as
  a pacs.008 the scheme cannot settle). **Tampering / input validation:** `SctInstResource` now
  calls `SctInstSchemeRules.requireSchemeCurrency` right after `Money.parseInbound`, so a non-EUR
  currency answers **400 `CURRENCY_NOT_ALLOWED`** (service-owned ADR-0326 code, VALIDATION category,
  rendered by libs-runtime `DomainExceptionMapper`) before any idempotency lookup, screening call,
  row, event or downstream call exists. The violation names `currency`, never the rejected value.
  EUR (any case) is unchanged. Sandbox data re-checked read-only before the change: 8 rows, all EUR,
  so no stored row is affected. No new endpoint, caller, privilege or event. Rollback: revert the
  commit.

## 2026-10-04 — Staged Envoy Gateway public edge (ADR-0324 Phase 4)

The `payments-api` HTTPRoute stages `/api/v1/sepa-instant-payments` alongside nginx. The
generated `sepa-instant-ingress-allow-list` adds `envoy-gateway-system` on TCP 8127 and
retains ingress-nginx. DNS stays on nginx in this step, while the new internal proxy peer
can reach the service when the Gateway listener is installed. OIDC, OPA, idempotency,
screening and settlement decisions remain in the service.

**D1 residual risk at cutover:** the nginx cap of ten concurrent connections per client
IP has no Envoy Gateway equivalent. A 20/s per-client request rate, a per-backend circuit
breaker and the separately staged listener-wide connection limit bound load, but one
slow client may hold more than ten connections and compete with other instant-payment
callers. Verify the listener policy and 401/429 responses on the Gateway address before
moving DNS. Roll back all four `api.open-bank.tech` routes together to nginx; its Ingress
and NetworkPolicy peer remain through Phase 5.

- **2026-10-04** — **AML outbound mTLS boundary (#12106).** The production AML REST client in `sepa-instant` now selects
  the named `aml-authority` TLS bucket: it presents a client certificate, trusts the AML
  private CA and uses TLS 1.3 to the client-authenticated AML listener on port 8443.
  The deployment changes the client's URL and mounts the certificate material; dev and
  test HTTP fixtures retain their old transport. **STRIDE-S/T/I:** the TLS handshake
  authenticates the caller and server and protects requests and responses in transit;
  a missing, expired or wrong-CA certificate must fail the connection rather than fall
  back to plaintext. **Residual boundary:** AML keeps HTTP 8117 for readiness, Admin UI discovery and
  the security scanner. Its opt-in per-port NetworkPolicy admits migrated caller
  namespaces only on 8443; Admin UI and the scanner still reach 8117, and same-namespace
  traffic remains permitted. This enforces the migrated cross-namespace path but does
  not make every AML HTTP access mTLS. A successful readiness probe or local HTTP test
  therefore does not prove the production handshake.
  Rollout verification must exercise this caller against 8443 with valid and invalid
  client certificates and check that failures do not reroute to HTTP. Rollback restores
  the previous client URL/configuration while the AML listener remains available.

- **2026-10-06** — **Durable SCT Inst state events (#12181).** PROCESSING/Rejected inserts and
  SETTLED/RECALLED updates now write the established four-field event payload to
  `sct_inst_outbox` in the payment transaction. A failed event insert rolls back the
  state change; the dispatcher uses the shared aggregate-head claim, retry/backoff and
  terminal `DEAD` state after bounded failures. The separate `countDead` gauge and
  `SepaInstantOutboxDeadLettered` alert make that terminal state visible; reviewed,
  per-event disposition is in `docs/runbooks/sepa-instant-outbox-recovery.md`.
  A successful Kafka acknowledgement can still precede a crash before `markSent`.
  The unchanged outbox `ce-id` is now sent on each attempt and used by audit-service before
  the Kafka offset when the body has no `eventId`. Deploy and verify that consumer first;
  a retry against an older consumer can still append a second audit row.
  Historical transitions are not backfilled. The scheme-submission GitOps flag is
  explicitly false under its matching application env name until the settlement flow
  and recovery owner approve a rollout. The outbox dispatcher must stay enabled for
  the default PROCESSING flow, and pending/failed/dead counts need operational review.
  **Rollback:** stop writers, retain V5's table while any recoverable outbox rows exist,
  and run a compatible relay if the old app is restored because it has no dispatcher.
  Remove the table only after an approved per-row disposition and a verified empty
  recoverable set; never replay historical payment events automatically.
