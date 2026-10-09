<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->
# Threat model — openbank-psd2-service

- **Date:** 2026-06-15
- **Status:** Lightweight STRIDE/DFD (ADR-0030 D2). **Money-path-adjacent** TPP boundary.
- **Service ADR:** ADR-0090 (Berlin Group XS2A base + ČOBS profile); platform controls per ADR-0029/0030/0034.

## 1. Scope & purpose

The PSD2/XS2A facade exposed to **external Third Party Providers** (TPPs): AIS (account information,
P1) and PIS (payment initiation, P2). It is the bank's *only* internet-facing API for third parties,
so it is the primary external attack surface. It does **not** hold the ledger or move money itself —
PIS delegates value transfer to `transaction-service` (a gated money-path service) and consent checks
to `consent-service`. This service is therefore *money-path-adjacent*: it authorises and shapes
requests, but the irreversible action lives downstream.

## 2. Data flow (DFD)

```
[External TPP] --(eIDAS QWAC mTLS + X-TPP-ID)--> (REST /v1/consents, /v1/accounts, /v1/payments) --> [openbank-psd2-service]
                                                                                                          |
                                            EidasMtlsFilter (QWAC + AISP/PISP role via tpp-registry)      |
                                                                                                          +--> [consent-service]   (validateConsent)
                                                                                                          +--> [transaction-service] (initiatePayment — money path)
                                                                                                          +--> [account/balance] (AIS reads)
```

- **External entities:** TPPs (AISP/PISP), the eIDAS trust chain, downstream consent/transaction/account services.
- **Trust boundaries:** Internet↔service (eIDAS QWAC mTLS + TPP-registry authorisation); service↔internal services (cluster mTLS+OIDC+OPA).
- **Assets:** consents, account/transaction data, **payment instructions** (debtor/creditor/amount), TPP identity.

## 3. Authn/Authz

- **TPP authentication:** eIDAS **QWAC** (mTLS) terminated at ingress; `EidasMtlsFilter` requires
  `X-TPP-ID`/`SSL-CLIENT-S-DN` and calls `tpp-registry` to confirm the TPP is authorised for the role.
- **Role gate:** path `/payments` ⇒ **PISP**, everything else ⇒ **AISP** (deny-by-default; registry
  circuit-open ⇒ 503, never fail-open).
- **Consent gate:** AIS data calls require a valid `Consent-ID`; PIS requires a consent that authorises
  payment initiation (`consent-service.validateConsent`, fail-closed) before any downstream call.
- The sandbox (`/open-banking/sandbox/`) is intentionally open for conformance testing; it never
  reaches real money or customer data.

## 4. STRIDE

| Threat | Vector | Mitigation |
|---|---|---|
| **S**poofing | Forged/lifted TPP identity | eIDAS QWAC mTLS + tpp-registry authorisation per request; role-scoped |
| **T**ampering | Alter amount/creditor in a payment in flight | TLS in transit; server-validated instruction; idempotency key (`X-Request-ID` / bespoke `Idempotency-Key`) bound to a request fingerprint — same key + different payment/consent is 409, not a replay (#10916); downstream transaction-service is authoritative; **QSEAL `Digest`+`Signature` verification** (`QsealSignatureFilter` / `QsealVerifier`, P4) binds the body and signing string per message |
| **R**epudiation | TPP denies initiating a payment | AuditEvent + `X-Request-ID` correlation + SCA evidence (sca-service, ADR-0021); **QSEAL signature** over the request gives per-message non-repudiation (P4, advisory→enforce) |
| **I**nfo disclosure | Account/transaction harvesting across consents | Per-`Consent-ID` scoping; AISP role; reads are owner/consent-bounded; amounts rendered without added precision |
| **I**nfo disclosure | Error bodies / metrics leak PII | Berlin `tppMessages` carry codes not PII; `/q/metrics` cluster-internal, low-cardinality (ADR-0077/0079) — no IBAN/amount/payment-id labels |
| **D**oS | Initiation/AIS flooding from a TPP | Idempotency replay; consent `frequencyPerDay` cap; ingress rate limit; registry circuit breaker |
| **E**oP | AISP initiates a payment, or consent reused beyond scope | Distinct PISP role on `/payments`; consent validated for the specific action+debtor before initiation |

## 5. Residual risks / assumptions

- **`X-Request-ID` idempotency required** — replays must not double-initiate; enforced via
  `IdempotencyStore.reserve` (atomic, fingerprint-bound) in `Psd2Idempotency`. The only dedupe layer
  in this service is Redis: psd2 persists no initiation, so a key evicted from Redis (TTL
  `idempotency-ttl-seconds`, 24 h, or data loss) is forwarded again and dedupe falls to
  transaction-service, which receives the TPP's raw identifier (not namespaced by TPP) and whose own
  fingerprinting is outside this model. Records written before #10916 carry no fingerprint and replay
  by key alone until they expire (≤ 24 h after deploy).
- **SCA** (sca-service, ADR-0021) must gate customer authorisation of the payment (redirect/decoupled).
- **QSEAL is advisory by default** (`openbank.psd2.qseal.enforce=false`): sandboxes have no real
  QSEAL chain, so a missing/invalid signature is logged but allowed. Production flips `enforce=true`
  to reject unsigned/forged writes. The verifier is real JCA (digest + RSA/EC signature over the
  canonical signing string), unit-tested in `QsealVerifierTest`.
- **Payment-information `GET` + full QSEAL trust validation (cert-chain to an eIDAS root, revocation)
  remain residual** — current verification trusts the presented `TPP-Signature-Certificate` and checks
  the signature, not the chain/OCSP. Chain validation is future work.
- The bespoke `/open-banking/v2` surface is **deprecated** (RFC 8594 `Deprecation`/`Sunset` headers via
  `BespokeDeprecationFilter`) and kept until the sunset date — admin-ui health probes still target it;
  it shares the same `EidasMtlsFilter` gate. Hard removal is gated on the sunset (tracked in #1118).

## 6. Change log

- **2026-10-08** — **CZK-only `domestic-cz` initiation (#12185).** Both the bespoke and Berlin PIS entry points reject another currency with `400 FORMAT_ERROR` before an idempotency marker, consent lookup or downstream payment call; the use case repeats the guard for non-HTTP callers. Risk class: payment-instruction integrity at the external TPP boundary. A refused request leaves its key available for a corrected CZK retry. The Berlin v1 body schema remains broad and the transaction client remains a stub (#1500), so the runtime guard and its HTTP tests are the evidence for the currency rule. Rollback would reaccept unsupported currencies and must be coordinated with the CZK-only domestic rail.

- **2026-10-08** — **OPA authorization ENFORCED (`AUTHZ_ENFORCE=true`, #12302).** The 22 `@Authorize` methods now block on a deny (403) and fail closed when the PDP is unreachable (503); before this the decisions were advisory and only counted. Checked with `opa eval` against the deployed `psd2-opa-bundle` ConfigMap: an eIDAS TPP (no bearer, so `ANONYMOUS`) is allowed all five `psd2.*` actions via `psd2-tpp-eidas-qwac`; staff and `ROLE_OPERATOR` service accounts get `psd2.list`/`psd2.read` only; nobody but the TPP gets `psd2.create`/`psd2.initiate`/`psd2.delete`; a customer bearer, a `ROLE_API` service account and an AI agent are denied everything. With `psd2_rest_ext.rego` removed, the TPP row turns to DENY. Residual: the TPP grant is keyed only on `ANONYMOUS`, so it is only as strong as the `EidasMtlsFilter` path gating (see the invariant in `psd2_rest_ext.rego`), and a TPP that sends an OIDC bearer is classified `HUMAN` and denied the write surface. Rollback: set `AUTHZ_ENFORCE` back to `"false"` and restore the allowlist entry in `check-authz-enforce-money-path.py`.
- **2026-09-28** — **`EidasMtlsFilter` and `QsealSignatureFilter` converted to non-blocking `@ServerRequestFilter`s so the eIDAS/QSEAL controls #10997 restored can actually execute on `/v1` POSTs (issue TBD).** Once #10997 made both filters actually run, they ran on the Vert.x IO thread — the guarded `BerlinConsentResource`/`BerlinPisResource` methods are Kotlin `suspend fun`s, which RESTEasy Reactive treats as non-blocking, and a request filter inherits the thread of the method it guards. Both filters do blocking work: `EidasMtlsFilter` makes a synchronous `tpp-registry` REST-client call, `QsealSignatureFilter` reads `ContainerRequestContext.entityStream` to verify the QSEAL digest/signature over the raw body. Every gated `POST v1/payments`/`v1/consents` therefore threw `BlockingOperationNotAllowedException` ("Attempting a blocking read on io thread") in `QsealSignatureFilter` before any resource code ran, and that exception type is a Vert.x/SmallRye runtime class thrown by the framework itself and is present in no tracked backend source of this repo — a fail-closed *denial* (bare 500), not an authorization bypass, but it broke every signed Berlin write call including legitimate ones. Fixed by converting both `ContainerRequestFilter` implementations to `@ServerRequestFilter` methods returning `Uni<Response?>`, with the blocking work (registry call / entity-stream read + signature verification) offloaded onto `Infrastructure.getDefaultWorkerPool()` inside the `Uni` pipeline (same pattern as `CopilotChatResource.chatStream`'s `runSubscriptionOn`). Verification semantics are byte-for-byte unchanged: same digest-over-raw-bytes check, same fail-closed responses (`CERTIFICATE_MISSING`/`CERTIFICATE_INVALID`/`SERVICE_UNAVAILABLE`/`SIGNATURE_INVALID`) for the same conditions, same advisory-vs-enforce QSEAL behaviour. New regression test `Psd2QsealBlockingReadIT` drives a real `POST /v1/consents` through both filters and fails on the pre-fix code with the blocking-thread crash; all pre-existing filter unit tests (missing cert, bad signature, unknown TPP, circuit-open, path-normalisation) were ported to the new `Uni`-returning signature and still pass unchanged in substance. The conversion must also carry the ordering: the old classes were `@Priority(AUTHENTICATION)` (eIDAS) and `@Priority(AUTHORIZATION)` (QSEAL), while a bare `@ServerRequestFilter` defaults both to `USER`, leaving transport auth-before-message-signature undefined. Both now declare `@ServerRequestFilter(priority = …)` with the original values, pinned by `FilterPriorityTest` (fails 3/3 without the priorities; an end-to-end ordering test was measured to pass either way, so it cannot guard this). `Psd2QsealEnforceIT` and `Psd2BerlinWriteFilterThreadIT` add enforce-mode and `/v1/payments` coverage.

- **2026-09-26** — **Idempotency key bound to the request (#10916 on the PSD2 path).** `PisResource`,
  `BerlinPisResource`, `BerlinConsentResource` and the bespoke consent resource replayed by key alone,
  so a TPP (or anyone replaying its identifier) reusing `Idempotency-Key` / `X-Request-ID` with a
  different amount, creditor or `Consent-ID` got the FIRST payment's 201 back and believed the second
  was initiated (**T**ampering / integrity). All four now go through `Psd2Idempotency.execute`:
  atomic `reserve` with a `RequestFingerprints` hash of method + concrete path + `Consent-ID` + the
  canonical body (consents: body + `TPP-Redirect-URI` [+ `PSU-IP-Address`]); the use case runs only on
  `Reserved`; the response is stored with the hash; a failed use case releases the marker (under
  `NonCancellable`, never masking the original error); nothing is released after success or replay.
  Different request → **409 `IDEMPOTENCY_KEY_REUSED`**, same request still running → **409
  `IDEMPOTENCY_REQUEST_IN_PROGRESS`**, both in the `tppMessages` envelope (the libs ApiError mappers
  are deliberately not used on this surface, #526). The key namespaces are unchanged, so in-flight
  TPP retries keep working. If the stored marker was claimed by another request after the payment
  already executed, the real 201 is returned (answering 409 would invite a retry under a fresh key).
  No DB-level check was added: this service has no initiation table (see §5). Also closes the
  get-then-save race in which two concurrent first requests both executed. Rollback = revert; legacy
  records remain readable either way.

- **2026-09-26** — **Spoofing/Tampering control restored: the eIDAS TPP gate now actually runs (#10997).** `EidasMtlsFilter`, `QsealSignatureFilter` and `BespokeDeprecationFilter` matched `UriInfo.path` against prefixes with no leading slash (`v1/`, `open-banking/`), while RESTEasy Reactive supplies `/v1/...`, so none of them ever fired. Measured over real HTTP before the fix: a request with no TPP identification got the resource's own `CERTIFICATE_MISSING` (fail-closed, no filter text), an unauthorised `X-TPP-ID` was never checked against tpp-registry, an authorised one was still refused 401 because `tppId` was never set, QSEAL verification (advisory by default) never evaluated, and no bespoke response carried `Deprecation`/`Sunset`. The effect was denial of every TPP call rather than a bypass, since each resource fails closed on a missing `tppId`. Each filter now normalises the path once (`removePrefix("/")`) and accepts both forms; `Psd2FilterPathGatingIT` drives the filters over real HTTP (filter rejection, registry rejection, authorised pass-through, deprecation headers) and fails with the normalisation reverted.

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
- **2026-09-25** — **Transport control tightened: OIDC TLS verification is `required` outside `%dev` (#10865).** `quarkus.oidc(-client).tls.verification: none` sat at the top level of `application.yaml`, so it applied to `%prod` too; inert while the in-cluster Keycloak leg is plain http, it would have skipped certificate and hostname validation of the token issuer / JWKS the moment that leg moved to https (Spoofing of the IdP). It now lives under `"%dev":` only, and gate `oidc-tls-verification-profile-scoped` keeps it there.

- **2026-08-24** — Synthetic-journey taint now propagates over this service's existing internal REST clients through `SyntheticTaintClientFilter` (ADR-0252, #4348). This adds no caller, endpoint, network-policy edge, privilege or PSD2-control bypass. It preserves the marker before a downstream persistence/event boundary; a fleet gate requires every new client to choose propagation or a reasoned external boundary.

- **2026-08-07 (#3658, recorded retroactively 2026-08-14)** — The required Berlin Group headers on
  `AisResource` and `PisResource` changed from non-nullable `String` to `String?` plus an explicit
  guard, so a **missing** `Consent-ID` / `Idempotency-Key` answers **400** instead of **500**.
  Previously the non-nullable Kotlin signature made JAX-RS inject `null` and the request died in
  `GenericExceptionMapper`; the exact case those headers exist to gate was the case the API answered
  worst.

  **Security posture is unchanged or improved, and this was verified rather than assumed:**
  - Every REQUIRED header still rejects. `AisResource` guards inline
    (`if (consentId.isNullOrBlank()) throw Psd2RequestFormatException(CONSENT_ID_REQUIRED)`);
    all four `PisResource` initiation endpoints delegate to the shared `initiatePayment`, which
    guards both `Consent-ID` and `Idempotency-Key` before any other work. Checked per endpoint, not
    per file.
  - The rejection is a `Psd2RequestFormatException`, i.e. the Berlin Group error shape, not a bare
    `IllegalArgumentException` — correct for this surface.
  - The other newly-nullable parameters (`dateFrom`, `dateTo`, `bookingStatus`, `limit`,
    `afterCursor`) are genuinely optional query parameters and correctly carry no guard.
  - No consent check is weakened, no new data flow, no new store, no endpoint added or removed.
    The **I**nformation-disclosure and **S**poofing rows are untouched; what improves is the error
    contract on the request-format boundary.

  **Why this entry is late.** ADR-0030 D2's `threat-model-updated-on-trust-boundary-change` gate
  fired on the PR and named this file. The PR was merged past it — the ruleset recorded a
  `required_status_checks` bypass — so the gate's demand was never met and, unlike a red build,
  nothing would ever ask again: the gate evaluates a diff, and that diff is long merged. Surfaced by
  the `merged-past-red-check` watch (#4828) once it could read the bypass log for the first time
  (#4791).

  Rollback: none applicable — this records a change already on `main`.

- **2026-06-15 (ADR-0090 P4)** — Added **QSEAL** message-signature verification
  (`QsealSignatureFilter` after the QWAC gate; `QsealVerifier` pure-JCA: `Digest` body binding +
  `Signature` verification over the canonical signing string via the `TPP-Signature-Certificate`
  public key). **Advisory by default**; `openbank.psd2.qseal.enforce` flips to reject. Strengthens
  the **T**ampering + **R**epudiation rows. Marked the bespoke `/open-banking/v2` surface deprecated
  via RFC 8594 headers (`BespokeDeprecationFilter`). No new data store/flow; residual = no
  cert-chain/revocation validation yet. Rollback = revert.
- **2026-06-15 (ADR-0090 P2)** — Added the Berlin **PIS** surface (`POST /v1/payments/{product}`,
  `GET …/status`) for the pan-EU SEPA products. New external money-path-adjacent flow: PIS request →
  consent validation → transaction-service. Mitigations: PISP role gate, fail-closed consent check,
  `X-Request-ID` idempotency, audit correlation. No new persistent store in this service (delegates
  downstream). Risk class = **integrity/EoP**; residual = no per-message QSEAL yet (P4). Rollback =
  revert; the bespoke `/open-banking/v2/payments` path is unaffected.
- **2026-06-15 (ADR-0090 P1)** — Berlin **AIS + consent** surface (`/v1/consents`, `/v1/accounts`).
  Read-only + consent reuse, no money path. Extended `EidasMtlsFilter` to gate `v1/`. Risk class =
  **confidentiality**, mitigated by per-consent scoping.

- **2026-09-26** — **`sanitizeForLog` de-duplication (#10937), no behavior change.** `StubClients.kt`
  (outbound client edge), `EidasMtlsFilter.kt` and `QsealSignatureFilter.kt` (inbound REST surface)
  drop their locally-copied `String?.sanitizeForLog()` helper for the single shared implementation in
  `openbank-libs-domain`. The function only replaces CR/LF with `_` (log-forging / CWE-117
  mitigation) — there is no length cap in either the shared function or the copies it replaces; an
  earlier version of this entry claimed one that does not exist. Behavior is otherwise
  byte-for-byte identical to the 14 copies it replaces fleet-wide — verified by the shared helper's
  own unit tests plus this service's existing filter tests. **Risk class:** none — this
  changes which class DEFINES the log-sanitization function, not what it does; the eIDAS mTLS
  certificate check and QSeal signature verification logic in both filters are untouched. Rollback:
  restore the service-local `sanitizeForLog` copy.
