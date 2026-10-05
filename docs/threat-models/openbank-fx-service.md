<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->
# Threat model — fx-service

- **Date:** 2026-06-17
- **Status:** Lightweight STRIDE/DFD (ADR-0030 D2). **Money-path** bounded context.
- **Service ADR:** see `docs/adr/`; platform controls per ADR-0029/0030/0034.

## 1. Scope & purpose

FX rates and currency conversion: publish/query rates, convert, query conversion. Rate integrity
directly determines monetary outcomes — a manipulated rate is a financial-loss vector.

## 2. Data flow (DFD)

```
[Rate source/operator] --> (REST /api/v1/fx/rates) --> [fx-service] --> [(Postgres: fx rates, conversions)]
[Payment/account callers] --> (convert) ----------------^                   |
                                                                            +--> [(fx_outbox)] --> [Kafka fx events]
                                                                            |
                                                                            +--> [fraud-service] (shadow, OIDC CC / mTLS, fail-open)
```

- **External entities:** rate-feed/operator, payment/account services requesting conversion.
- **Trust boundaries:** rate ingestion (integrity-critical); caller↔service; service↔Postgres/Kafka;
  service↔fraud-service (OIDC client-credentials + mTLS, internal cluster-only, shadow/read-only).
- **Assets:** FX rate table, conversion records.

## 3. Authn/Authz

- `@RolesAllowed` enforced: rate publish restricted to `ROLE_OPERATOR/ADMIN`; convert allows
  `ROLE_PAYMENTS`; read includes `ROLE_VIEWER`.

## 4. STRIDE

| Threat | Vector | Mitigation |
|---|---|---|
| **S**poofing | Fake rate-feed pushes a rate | Authenticated rate source; operator-only publish |
| **T**ampering | Off-market / stale rate used for conversion | Rate provenance + timestamp; staleness check; bounds/sanity limits; audit |
| **R**epudiation | Deny publishing a bad rate | AuditEvent per rate publish + conversion |
| **I**nfo disclosure | Rate scraping / conversion history | AuthZ; rate read is low-sensitivity but rate-limited |
| **I**nfo disclosure | (issue #5679) `GET /api/v1/fx/approvals` lists every pending four-eyes request with its `makerId` and age | Role-gated `ROLE_OPERATOR`/`ROLE_ADMIN` + `@Authorize(action = "fx.approval.read")`; the payload carries approval metadata only — the action name, the resource id and who asked — never rate/conversion details. Limit clamped to 200 — an unbounded query parameter over a Redis scan is a trivially reachable amplification. Deliberately NOT filtered to exclude the caller's own requests: hiding a maker's request from them would not stop them attempting it (the guard is in `RedisApprovalStore.decide`, server-side) and would only make the queue lie about its own depth |
| **D**oS | Conversion flooding | Rate limit; cache current rate |
| **E**oP | Viewer publishes a rate | Distinct publish role; deny-by-default |

## 5. Residual risks / assumptions

- **Rate-source integrity** is the dominant risk — compromise yields silent financial loss.
- Conversions should pin the rate id/timestamp used (auditability + dispute defense).

## 6. Change log

- **2026-10-03** — **SENT outbox rows are purged after 7 days** (ADR-0329, ADR-0327 D8). `fx_outbox` kept every SENT row, payload included, indefinitely: `purgeSent` existed and nothing called it. The shared libs-runtime `OutboxSentRetentionJob` now deletes SENT rows whose `sent_at` is older than `openbank.outbox.retention.sent-days` (default 7) nightly in bounded batches; its v1 repository opts in by delegating `SentOutboxRetention` to `PanacheOutboxRetention`. PENDING, FAILED, DISPATCHING and DEAD rows are never touched. Information disclosure: shrinks the window in which a database read (replica, backup, operator query) exposes past event payloads. No new endpoint, caller or privilege; replaying an event older than 7 days now comes from the broker or audit-service, not this table.

- **2026-10-04** — Trust-boundary change: ČNB policy-rate ingestion. **New outbound edge** to four public, unauthenticated ČNB files (`openbank.cnb.policy-rates.{repo,discount,lombard,min-reserves}-url`, the same `www.cnb.cz` host as the fixing feed, probed daily by `check-external-feeds.py`), fetched daily and on boot by `CnbPolicyRateIngestionScheduler`. **New outbound Kafka topic** `openbank.fx.cnb-policy-rate.published` (compacted; Write/Describe ACL for the existing `fx-service` KafkaUser only; Read for audit-service and risk-engine), fed from `fx_outbox` in the same transaction as the `cnb_policy_rate` rows. **New read** `GET /api/v1/fx/cnb/policy-rates/{instrument}?asOf=` on the existing `fx.read` action and staff roles (no `ROLE_API`: the risk engine gets these facts by event, so no machine caller is added). **Tampering / integrity — the point of this change:** the risk engine computes the minimum-reserve requirement from these facts, so a wrong rate is a wrong regulatory figure. Mitigations: each file is parsed all-or-nothing against its exact header (an HTML error page or the wrong file is rejected, never stored); every malformed row fails the whole file with a count; bytes are decoded as strict UTF-8 and SHA-256 hashed into the row, so any served value is traceable to the exact file; a changed rate for a stored date is applied as a *revision* with the previous rate kept, a WARN, and `openbank_fx_cnb_policy_rate_rows_total{outcome="revised"}` — the ČNB does not revise history, so a revision is a signal, not routine. The minimum-reserve ratio and remuneration are DOWNLOADED too, from the ČNB workbook `PMR_historie_zmen.xlsx` (a fourth outbound file on the same host), not seeded: it is read by a JDK-only hardened reader (`SafeXlsxReader`: zip-bomb limits enforced while inflating, StAX with DTD and external entities disabled, entries looked up by name and never written to disk — no new third-party parser on the money path), the header row is located by its labels on every sheet, and the whole file is rejected on any anomaly (a sheet that is not a year, a date outside its sheet's year or going backwards, a percentage above 100, or a non-numeric/ambiguous ratio or remuneration after the history began). The history before the first single-number row is not stored. A day with no fact in effect is 404 here and NOT_EVALUABLE downstream — never a default. **Spoofing:** TLS to `www.cnb.cz`; a compromised upstream would be caught only by the revision signal for historical rows, not for a new row — accepted, as for the fixing. **DoS:** three ~2 KB files and one ~100 KB workbook per day behind the same retry/timeout/circuit-breaker as the fixing client; a dead feed records its own `openbank_feed_fetch_total` outcome and does not block the other two. Rollback: revert; `cnb_policy_rate` is forward-only and the topic may stay (compacted, no secrets).
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

- **2026-09-24** — Trust-boundary change (ADR-0314 D5, #10618): a second outbound Kafka topic, `openbank.fx.fixing.published`, carrying each ingested ČNB fixing (`fx.fixing.published.v1`). New channel `fx-fixing-out`, new KafkaTopic, and a Write/Describe ACL on that topic only for the existing `fx-service` KafkaUser; no new inbound surface, caller, endpoint or privilege. The rates and the event commit in ONE transaction (`FxRateRepository.saveAllWithOutbox`), which also changes ingestion from one transaction per currency to one per fixing: a failure now stores none of that day's rates rather than some, and the next scheduled run retries the whole day. **Tampering:** the event is a copy of public statutory rates, not a new source of truth — `fx_rates` stays the record and a consumer that needs certainty re-reads it by `rateId`; the payload carries only ids the same transaction wrote. **Info disclosure:** none beyond the public ČNB list. **Repudiation:** outbox `event_id` + `ce-*` headers as for conversions. **Routing:** both event families share `fx_outbox`; `KafkaFxOutboxEventPublisher` routes by event-type prefix, pinned by a test that asserts the other emitter is untouched. Rollback: revert; the topic may stay, it has no consumer yet.
- **2026-08-24** — Synthetic-journey taint now propagates over this service's existing internal REST clients through `SyntheticTaintClientFilter` (ADR-0252, #4348). This adds no caller, endpoint, network-policy edge, privilege or control bypass. The public CNB feed is explicitly a non-banking external boundary and does not receive the marker; a fleet gate requires every new client to choose one of these treatments.
  The accompanying client-source normalization is formatting-only: request payloads, client targets,
  authentication, retry policy, and the propagation decision are unchanged. No additional trust
  boundary or STRIDE row is introduced by that refactor.

- **2026-08-19** — `ApprovalResource` served only `PATCH /{id}` (decide), so an `fx.convert`
  four-eyes decision parked at 202 was discoverable only by whoever had been handed its approval
  id out of band — the ceremony completed only if the two operators were already talking, and the
  24h Redis TTL then expired the request silently otherwise (issue #5679, mirroring sanctions
  #3472, ledger, domestic-payment and sepa-instant). Added `GET /api/v1/fx/approvals` (§4 new I
  row); additive-only OpenAPI change (1.7.0 -> 1.8.0, ADR-0048).
  - **Checked the existing decide endpoint's own authz posture while here** (verify-by-effect, not
    by appearance, per this sweep's own prior findings on balance-service and sepa-instant):
    `opa eval` against the real `fx_rest_ext.rego` + `rest.rego` + `rules-data.yaml` bundle
    (extracted from `fx-opa-bundle.yaml`) showed `fx.approval.decide` resolving `allow=true` for a
    real ROLE_OPERATOR, `reason=operator-fx-approval-decide` — fx-service already carries a
    dedicated ext-rego rule for this action (added 2026-08-05, #3734), independent of
    `rules.yaml`'s `role_action_matrix` (which does not list `fx.approval.decide` at all, unlike
    the matrix-only sepa-instant case). The new `fx.approval.read` action also resolves
    `allow=true`/`reason=operator-read-any` — the base `rest.rego` grants any `*.read`-suffixed
    action to ROLE_OPERATOR/ROLE_ADMIN fleet-wide, so no fx-specific rule was needed for the read
    side either. A non-operator role (`ROLE_KYC_OPENER`) resolves `allow=false` on both actions.
    **No matrix or ext-rego gap found** — nothing to fix in `rules.yaml` for this service.
  - **Known residual, not fixed here**: `operator-read-any` is role-only like `matrix-allows`, and
    the deployed realm template gives `service-account-openbank-services` `ROLE_OPERATOR` in at
    least one environment (see root `CLAUDE.md`'s realm-drift note) — the same shape balance-service
    and sepa-instant flagged for their own new read endpoints. Unlike `fx.approval.decide` (whose
    2026-08-05 `prohibited` veto and `not startswith(input.principal.id, "service-account-")`
    guards already exclude M2M), `fx.approval.read` carries no such exclusion, so a service account
    holding `ROLE_OPERATOR` in that realm could read the pending-approvals queue. Both verified
    fx-service M2M callers (`service-account-openbank-edge`, `service-account-openbank-services`)
    are documented read-only rate-sheet/revaluation consumers with no reason to read this queue.
    Building a new prohibition mechanism was out of scope for this PR; tracked as follow-up under
    issue #5679's own money-path-first ordering, same as sepa-instant's #5694 residual.
  - **Rollback:** the new `GET` is additive; revert the commit.

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
- **2026-08-09** — Trust-boundary change (#3921): `GET /api/v1/fx/rates/{base}/{quote}` gains an optional `asOf` query parameter, so ledger's daily revaluation can ask for the CNB fixing that was **in effect on the business day it is marking** instead of the newest one. Same authz (`fx.read`, unchanged roles), same response shape, no new caller and no new outbound edge; the surface change is one read parameter on an existing authenticated route.
  Tampering column, sharpened: the row already named "off-market / stale rate used for conversion" with "staleness check" as the mitigation, and no such check was reachable — the seam carried no date at all until #3921 step 1, and even then the *lookup* was still date-blind. `asOf` closes the second half. Three properties were chosen so the parameter cannot itself become the tampering vector it exists to remove: a day with no fixing in effect is **404, never a silent fall-back to the newest fixing** (falling back is the defect — it is how a backfill marks an old day at today's rate and reports success); the stored `validTo` bound is kept, so a dead feed still resolves to *absent* rather than to the last fixing it ever published; and `asOf` without `source=CNB` is **rejected 400 rather than ignored**, because a silently-dropped date parameter reads as a correct backfill. `asOf` is parsed with `runCatching`, so a malformed value is 400 (libs-runtime maps `IllegalArgumentException`), not a 500 — and it is declared **nullable**, the #3104 rule in this same log one entry down.
  Info-disclosure delta: the parameter widens what a `fx.read`-authorised caller can retrieve from "the current fixing" to "any historical fixing", which the pre-existing `GET .../history` route already exposed to the same roles — no new class of data, and CNB fixings are public statutory rates. DoS: bounded, single indexed row per call, no unbounded range. Rollback: revert; omitting `asOf` is byte-for-byte the previous behaviour, which `getCnbRate without asOf keeps asking for the latest still-valid fixing` pins.

- **2026-08-05** — Trust-boundary change (#3734): the three operator rules (`operator-fx-write`, `operator-fx-trigger`, `operator-fx-approval-decide`) now exclude `service-account-*` principals, and a new `prohibited` veto closes `fx.convert` to `service-account-openbank-edge` — the only fx write in the role_action_matrix's ROLE_OPERATOR grant, which matrix-allows re-admits regardless of the exclusion. Both M2M clients are verified read-only (edge: rate-sheet proxy; shared client: ledger FX revaluation + agent-service MCP read tools) and keep their identity-scoped reads. `fx.trigger`/`fx.approval.decide` are absent from the matrix grant, so the exclusion closes them outright. Ext moved from generator heredoc to standalone `fx_rest_ext.rego` with an 11-test opa suite.
- **2026-08-03** — Missing required query/header parameter answered 500, not 400 (#3104). A required `@QueryParam`/`@HeaderParam` declared with a non-nullable Kotlin type was fed `null` by JAX-RS when the caller omitted it, and answered **500** rather than 400 (#3104). Kotlin's null-safety is compile-time only, so the declared type only decided where the failure landed: a non-suspend handler threw `Intrinsics.checkNotNullParameter` at the method boundary, and a **suspend** handler got no intrinsic at all, so the null flowed into the body. `Idempotency-Key` on convert, the sibling of the null-body guard added by #3050 one argument position over. Same defect as domestic-payment: `require(key.isNotBlank())` in a `suspend` handler threw NPE on an absent header, so the replay control answered 500 for a missing key and 400 only for a blank one. Now `require(!key.isNullOrBlank())`. No new caller or boundary. Rollback: revert.
- **2026-09-03** — Doc correction, no behavior change: the 2026-06-17 entry credited the
  fail-open shadow-scoring wrapper to `FxConversionService`, a class that exists in no Kotlin
  source in this repository — `git grep -l FxConversionService -- '*.kt'` returns nothing, and the
  name occurs nowhere outside this document. **The control is real and unchanged**: the method is
  `FxService.scoreFraudShadow()`
  (`openbank-fx-service/src/main/kotlin/com/openbank/fx/application/usecase/FxService.kt`), called
  at line 123 and defined at line 337, and it does wrap the fraud call so that any fault leaves the
  conversion outcome untouched. Only the class name was wrong. The sibling entry in
  `openbank-sepa-instant.md` names its equivalent (`SctInstPaymentService.scoreFraudShadow()`)
  correctly, which is what makes this one identifiable as a typo rather than a renamed control.

- **2026-05-30** — Added `fx_outbox_seq` (Hibernate fix). Additive DDL only — no new flow/surface/
  boundary. Risk class = **availability**, mitigated by `HibernateSequenceGuardTest`.
  Rollback: `DROP SEQUENCE`.
- **2026-06-17** — ADR-0084 fraud shadow scoring (observe-only). New outbound trust boundary:
  `fx-service → fraud-service (POST /api/v1/fraud/score, OIDC client-credentials)`.
  **Shadow = fail-open and never-enforce**: `FxService.scoreFraudShadow()` wraps the call
  in `.onFailure().recoverWithItem {}` — any fault (timeout, circuit-open, 5xx) is swallowed; the
  conversion outcome is unchanged. `FraudScoringAdapter` applies `@CircuitBreaker` (30% failure ratio)
  + `@Timeout(3 s)`. No retry (avoid double-scoring on the same conversion).
  **Risk class = availability** (fault in fraud-service cannot block a conversion) and **confidentiality**
  (conversion amount, currency pair, account id sent to fraud-service; mitigated by mTLS +
  OIDC client-credentials; fraud-service is internal, cluster-only).
  **DFD update**: added `fx-service → fraud-service` edge (see §2). No DB schema change;
  rollback = revert adapter + port commits.
- **2026-06-15** — V5 seed migration + CNB validTo window extension.
  - **V5 seed** (`V5__seed_fx_history.sql`): inserts synthetic `INTERNAL` rates (90-day history + today)
    and approximate CNB reference rows for sandbox/CI. Pure INSERT — no DDL, no conversion-path change.
    Seed values are synthetic; actual rates still require operator publication via the authenticated
    `/api/v1/fx/rates` endpoint. Risk class = **data integrity (sandbox only)**: stale seed rates cannot
    affect conversion accuracy once a real operator publish or CNB ingest overrides them (findLatest
    returns highest validFrom row within the current window). No prod-settlement risk.
    Rollback: delete rows WHERE source IN ('INTERNAL','CNB') AND created_at >= '2026-06-15'.
  - **CNB validTo 1→3 days** (`CnbRateIngestionService`): extends the reference-rate window to cover
    weekends and public holidays (scheduler runs at 14:40 Prague time on business days). Idempotency
    unaffected (keyed on `(source, pair, validFrom)`). Risk: a slightly stale CNB mid rate used in
    `spreadPct` display for at most 3 days. Rate table integrity and conversion accuracy are not affected
    (INTERNAL bid/ask are the settlement rates; CNB mid is display-only). Residual risk = **negligible**.
- **2026-08-05** — Derived inverse-quote identity (#3374). `GET /api/v1/fx/rates/{base}/{quote}`
  answered a pair derived by inverting the stored direction with the **source row's `id`**, so both
  directions shared one identifier — a quote id could not be replayed to a direction (the §4
  repudiation concern, and §5's "pin the rate id" assumption). The endpoint now answers `id: null` +
  `derivedFrom: <source row id>` on a derived quote; a stored quote keeps its own id and no
  `derivedFrom`. No new flow/surface/boundary — same endpoint, same data, honest identity.
  `FxConversion.rateId` still references the real `fx_rates` row on both paths (FK unchanged), so
  dispute defense via §5 is preserved. Risk class = **repudiation/auditability (reduced)**.
  API contract: additive, `info.version` 1.5.0 → 1.6.0.

- **2026-09-20** — **New inbound edge over a new private-CA mTLS listener** (8443, client auth
  REQUIRED, TLSv1.3; server cert `fx-service-internal-tls`), the same shape as account-service,
  ledger-service and transaction-service. HTTP/8119 is unchanged for its existing callers
  (customer-edge, ledger-service, agent-service), so this is additive, not a migration. The caller
  on 8443 is transaction-service `FxRateClient` reading
  `GET /api/v1/fx/rates/{base}/{quote}` (`fx.read`) as the shared
  `service-account-openbank-services`. Before this, transaction-service's gitops manifest set no
  `FX_SERVICE_URL`, so it dialled `http://localhost:8119` inside its own pod and every
  cross-currency rate lookup was a connection refused.
  **No policy change, and that is measured against the realm that ships.** `fx_rest_ext.rego`'s
  identity-gated `service-fx-shared-client-m2m` admits `fx.read`/`fx.list` for that account with no
  role predicate, so it holds even though the deployed realm template grants the account `ROLE_API`
  only. Evaluating the committed `fx-opa-bundle` ConfigMap with `opa eval`: `ROLE_API` →
  `allow=true` (`reason: service-fx-shared-client-m2m`), and the must-DENY controls hold —
  `fx.convert` is denied for that account and for the edge identity, and a principal with no roles
  is denied `fx.read`. **Risk class:** confidentiality of published FX rates (already readable by
  three other in-cluster callers over HTTP) to one more authenticated service; read-only, no
  mutation, no new action, no widened role. Rollback: drop the listener env block and
  transaction-service's `FX_SERVICE_URL`.
- **2026-09-21** — **Own machine identity for the AML case open (#10486 batch 3).** `AmlServiceClient` (`POST /api/v1/aml/cases`) now mints its bearer from a NAMED oidc-client `m2m`, Keycloak client `openbank-fx` (`ROLE_API` only); aml-service grants it exactly `amlCase.create` (`service-aml-case-create-m2m`, plus a Kotlin named-caller check while aml-service runs OPA advisory). **STRIDE-S:** a new credential. Its secret is generated by Keycloak in the live realm, stored by the owner's provisioning script at Vault KV `keycloak/fx-service` (`client_secret`), projected by the `fx-service-m2m-oidc` ExternalSecret and never seen by the repo; the env ref is `optional: false`, so an unseeded entry blocks the new pod loudly (CreateContainerConfigError) rather than calling with an empty credential. Compromise of this secret reaches only `amlCase.create`, against the shared secret's operator write set. **Repudiation improves:** a referred case now names fx-service. The sanctions and fraud clients stay on the shared client until their own edges migrate (their calls already pass with `ROLE_API`, measured by `m2m-shared-client-denylist.py`). Rollback: revert the commit.
- **2026-09-21** — **Published-rate reads admit ROLE_API at the RBAC gate (#10486 batch 4).** `GET /api/v1/fx/rates` and `/rates/{base}/{quote}` add `ROLE_API` to `@RolesAllowed` so ledger, transaction and agent-service keep reading rates once the shared `openbank-services` client loses `ROLE_OPERATOR`. OPA is unchanged and identity-gated (`service-fx-shared-client-m2m`, `service-fx-edge-m2m`; fx enforces), so another service account with `ROLE_API` still gets 403 there. Conversion reads and `fx.convert` do not admit `ROLE_API` (`FxRateMachineReadRbacTest`). **Risk class:** published reference data only; no write, no customer record. Rollback: revert the commit.

- **2026-09-27** — **ApprovalResource migrated onto ApprovalEndpointSupport (#10917/#11031/#11062),
  no wire change.** The maker-checker four-eyes endpoints (`GET .../approvals`,
  `PATCH .../approvals/{id}`) now delegate their body — limit clamping, the null-body-is-400
  guard (#3029), unknown-id-is-404, checker-identity resolution and the self-approval refusal — to
  the shared `ApprovalEndpointSupport` (libs-runtime). Only the `@Path`/`@RolesAllowed`/
  `@Authorize` annotations and the Quarkus resource class stay per-service. Paths, roles, status
  codes and JSON field names are unchanged; `ApprovalResourceMappingTest` covers the mapping.
  **Risk class:** none — response-plumbing de-duplication only; the self-approval check (a maker
  cannot approve their own request) is preserved verbatim in the shared implementation, and a
  maker-cannot-approve-own test exists for this service. Rollback: revert to the inline
  implementation this PR replaces.

- **2026-09-28** — **`ApprovalEndpointSupport.decide()` now resolves the checker identity lazily,
  after the null-body check (#11047/#11061).** The prior parameter was a bare `SecurityIdentity`,
  which looked like it deferred `checkerId()` resolution past `requireNotNull(request)` but did
  not: Kotlin evaluates a call's argument expressions before the function body runs, so passing a
  caller's `lateinit var identity` as that argument threw UninitializedPropertyAccessException at
  the call site whenever the body was null — before `decide()` ever reached its own null-body
  guard, inverting the documented and tested "null body rejected before any identity is resolved"
  contract (`ApprovalNullBodyTest`, #3029). `decide()` now takes an
  `identityProvider: () -> SecurityIdentity` supplier; this service's call site is migrated to
  `support.decide(id, request) { identity }`, and the supplier is invoked only after
  `requireNotNull(request)` returns. **Risk class:** none — fixes an incorrect 500
  (uninitialized-property crash) on a malformed request back to the intended 400; no endpoint,
  authorization, self-approval or wire-shape change. Rollback: revert to the eager
  `SecurityIdentity` parameter.

- **2026-10-04** — **AML outbound mTLS boundary (#12106).** The production AML REST client in `fx-service` now selects
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
