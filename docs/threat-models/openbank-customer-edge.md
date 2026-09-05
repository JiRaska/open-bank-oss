# Threat Model — openbank-customer-edge

Status: Draft
Date: 2026-06-07
Author(s): OpenBank platform
ADR references: ADR-0065, ADR-0069
Review cadence: before GA, then annually or on material change

---

## 1. Scope

`openbank-customer-edge` is the **sole internet-facing path** for the retail customer app
(`openbank-app`, ADR-0064). It sits between the public internet and the OpenBank cluster's
private service mesh. Every customer API call — onboarding, account reads, balance checks,
and SCA device enrollment / payment decisions — transits this service.

**In scope:** customer-edge Quarkus service, its ingress (nginx + Let's Encrypt), the
Keycloak `openbank-customers` realm JWT validation, upstream calls to party-service,
account-service, balance-service, and sca-service.

**Out of scope:** Keycloak internals, upstream service threat models (covered separately
for sca-service and account-service), mobile app attestation (ADR-0064 appendix).

---

## 2. Data Flow Diagram (DFD Level 1)

```
[Internet / Mobile App]
         │  HTTPS (TLS 1.3, HSTS)
         ▼
  [nginx Ingress]  ──── rate-limit (20 req/s/IP), HSTS, X-Robots-Tag
         │
         ▼
  [customer-edge pod]
    ┌────────────────────────────────────────────────────────┐
    │  1. Quarkus OIDC: validate Bearer JWT (openbank-       │
    │     customers realm, PKCE-issued token)                │
    │  2. Extract party_id claim → CustomerIdentity          │
    │  3. Proxy allow-listed routes with M2M operator token  │
    │     + X-Customer-Party-Id header                       │
    └──────┬─────────────┬──────────────┬──────────────┬─────┘
           │             │              │              │
    party-service  account-service  balance-svc  sca-service
    (identity ns)  (accounts ns)   (balances)   (sca ns)
         │              │              │              │
       [Kafka / PostgreSQL — inside cluster, not reachable from internet]
```

Trust boundaries:
- **TB-1**: internet ↔ nginx ingress (TLS, rate-limit)
- **TB-2**: nginx ↔ customer-edge (cluster-internal mTLS via Kubernetes Service)
- **TB-3**: customer-edge ↔ upstream services (cluster-internal, M2M bearer token)

---

## 3. STRIDE Analysis

### 3.1 Spoofing

| ID | Threat | Mitigation | Residual risk |
|----|--------|-----------|---------------|
| S-1 | Attacker forges a customer JWT to impersonate another party | Keycloak RS256 JWT validation (OIDC discovery, key rotation). Token short-lived (5 min). `party_id` bound to Keycloak user attribute at user creation time. | Low — requires Keycloak private key compromise |
| S-2 | Attacker replays a captured customer token | Short `accessTokenLifespan: 300s`. Keycloak session binding. Token revocation via `revokeRefreshToken: true`. | Low |
| S-3 | Attacker presents operator-realm token to customer edge | Quarkus OIDC validates `iss` = `openbank-customers` realm only. Cross-realm token rejected with 401. | Negligible |
| S-4 | Attacker spoofs `X-Customer-Party-Id` header to upstream | Header is set by the edge from the validated JWT — never from the request. Upstream services never read this header from untrusted clients (only from the M2M token's ROLE_OPERATOR caller). | Low |

### 3.2 Tampering

| ID | Threat | Mitigation | Residual risk |
|----|--------|-----------|---------------|
| T-1 | Attacker tampers with request body between app and edge | TLS 1.3 (HSTS with preload). Certificate pinning (Phase 2, ADR-0064). | Low |
| T-2 | Attacker injects `partyId` in `POST /onboarding/account` body to open account for another party | `partyId` is taken from JWT `party_id` claim, not from request body. Request-body `partyId` field is ignored and replaced. | Negligible — by design |
| T-3 | Attacker modifies upstream response body | In-cluster mTLS. Response is passed through unmodified; no integrity checks on response body (acceptable — response content is the service's responsibility). | Medium — no response signing |
| T-4 | Attacker injects `partyId` in `POST /sca/challenges` body to raise a challenge for another party | `partyId` is injected from the JWT `party_id` claim (edge appends it to the body); any client-supplied value is overridden (last-key-wins). Same pattern as T-2 / device enrolment. | Negligible — by design |

### 3.3 Repudiation

| ID | Threat | Mitigation | Residual risk |
|----|--------|-----------|---------------|
| R-1 | Customer denies making a payment / SCA decision | `POST /sca/challenges/{id}/decision` is signed by the device's SCA private key (ADR-0021 dynamic-linking). Signature + party ID logged in sca-service. | Low |
| R-2 | Edge log tampering | Logs ship to OpenTelemetry collector (in-cluster). Edge pod has `readOnlyRootFilesystem: true`. | Medium — log storage tampering not covered here |

### 3.4 Information Disclosure

| ID | Threat | Mitigation | Residual risk |
|----|--------|-----------|---------------|
| I-1 | Customer reads another customer's account/balance via a guessed `accountId` (account-service / balance-service scope by id only) | **Edge enforces ownership before proxying** on `/accounts/{id}` and `/balances/{id}`: the account is resolved via account-service and the read is rejected (403, not 404 — no existence oracle) unless the account's `partyId` equals the JWT party. Same explicit re-check as the transactions/statements reads (I-5), not upstream-trust. | Low — IDOR closed at the edge |
| I-2 | Unauthenticated attacker reads customer data | All data routes require `ROLE_CUSTOMER`. `/onboarding/start` creates a party (no PII read). | Low |
| I-3 | Edge logs contain PII (legalName, email) | Quarkus access log includes URL path and status, not request body. Avoid logging request body in onboarding routes. | Medium — application-level log discipline required |
| I-4 | JWT party_id claim visible to mobile app (native) | JWT is in app memory only; not logged or stored in plaintext. Platform (iOS Keychain) stores tokens. | Low |
| I-5 | Customer reads another customer's **transaction history** via a guessed `accountId` (transaction-service lists by `accountId` only, no party scope) | **Edge enforces ownership before proxying**: `/customer/v1/transactions` resolves the account via account-service and rejects (403) unless the account's `partyId` equals the JWT party. Stronger than the balance route (I-1) — an explicit re-check, not upstream-trust. | Low — IDOR closed at the edge for the transactions read |
| I-6 | FX rate sheet (`GET /customer/v1/fx/rates`, `/fx/rates/{base}/{quote}`) leaks customer data | **Intentionally not party-scoped** — the published rate sheet is the same public reference data for every customer, carries no PII and no account/party identifiers. Still `ROLE_CUSTOMER`-gated (no anonymous access, feeds the per-IP rate-limit) and read-only; the edge projects fx-service's record to {base,quote,rate,bid,ask,timestamp} only, never conversion/party history. | Low — no party data on this surface by construction |

### 3.5 Denial of Service

| ID | Threat | Mitigation | Residual risk |
|----|--------|-----------|---------------|
| D-1 | Flood of requests to `/onboarding/start` (unauthenticated) | nginx rate-limit 20 req/s/IP (burst × 3). `/onboarding/start` creates a party per request — M2M token acquisition adds latency but not a bottleneck. | Medium — IP rate-limit alone insufficient against distributed attack |
| D-2 | Slow-loris / large body attack | Quarkus HTTP body size limit (default 10 KB). nginx `client_max_body_size`. | Low |
| D-3 | SCA challenge enumeration (guessing challenge UUIDs) | Challenge IDs are cryptographically random UUIDs (128-bit entropy). 403 on mismatch (sca-service checks device-party binding). | Low |

### 3.6 Elevation of Privilege

| ID | Threat | Mitigation | Residual risk |
|----|--------|-----------|---------------|
| E-1 | ROLE_CUSTOMER reaches operator-only endpoints | Class-level `@RolesAllowed("ROLE_CUSTOMER")` on all authenticated routes. Operator realm (`openbank`) is entirely separate from `openbank-customers`. | Low |
| E-2 | Edge service account (ROLE_OPERATOR M2M) abused | `openbank-edge` client in `openbank` realm has a client secret (Kubernetes Secret, not in git). Rotation via Vault ExternalSecret. Compromise of this secret → attacker could call any ROLE_OPERATOR endpoint. | High — secret rotation SLA needed before GA |
| E-3 | Container escape → access cluster APIs | `readOnlyRootFilesystem: true`, `allowPrivilegeEscalation: false`, `capabilities: drop ALL`, `seccompProfile: RuntimeDefault`. No cluster API access in ServiceAccount. | Low |
| E-4 | Party with PENDING_KYC opens account | `POST /onboarding/account` KYC gate checks party-service for `status == ACTIVE` before forwarding to account-service. | Low — enforced by edge |
| E-5 | Customer initiates a payment debiting another party's account without a current, attributable delegation, or races concurrent payments past a cumulative ceiling | Owner debits stay local. A non-owner must receive `authorized=true`, `outcome=DELEGATED`, UUID grant and grantor evidence from account-service; every other shape is the same 403. The reserve sender is off by default and delegated initiation is 503 while disabled — never the old unreserved bypass. Once enabled after downstream health proof, the edge reserves one trim+uppercase canonical amount/currency tuple after enrichment and SCA, then forwards grant/reservation ids only in trusted M2M headers. Reservation 4xx/5xx/malformed evidence fails closed. The same required, ≤128-character idempotency key binds reserve and payment; replay evidence must match delegation, amount and currency. A 201 is only accepted instruction, so the edge never confirms/releases it. Every remote error, including 400, is ambiguous because the key may have persisted on an earlier attempt; a timeout or elapsed TTL is never evidence that no payment exists. Only an authoritative terminal event or an atomic receiver-side `FINALIZED_ABSENT` tombstone may settle the reservation. Downstream auth/routing bodies are never forwarded; only the contracted idempotency problem remains customer-visible. | Medium until receiver-side atomic reconciliation is deployed and proven; the rollout gate must remain disabled. Afterwards, residual denial-of-headroom is bounded by reconciliation latency. |
| E-6 | Customer is served ANOTHER party's data because the edge follows an ADR-0179 `merged_into` pointer | `PartyMergeResolver` rewrites the JWT `party_id` to the surviving party when party-service reports the claimed party as `MERGED`. This is the intended meaning of a merge (the two rows are one human), so the whole gate is on WRITING the pointer, not on reading it: `POST /api/v1/parties/{id}/merge` is `@Authorize(action = "party.merge")` and `party.merge` is listed in `rules.yaml: four_eyes.actions`, so a merge needs a maker plus a different checker. The resolver reads only party-service (in-cluster mTLS, M2M token) — never a client-supplied id — follows at most 5 hops with a visited-set so a corrupted or cyclic pointer cannot redirect indefinitely, and fails OPEN to the claimed id so a party-service outage degrades to today's behaviour rather than to a wrong identity. | **High until the merge gate is enforced** — a wrongly-approved merge is an account-takeover primitive, and today the four-eyes gate that is supposed to prevent one is WIRED BUT INERT: party-service ships `AUTHZ_FOUR_EYES_ENFORCE` at its `false` default and `gitops/components/party/party-service.yaml` sets `AUTHZ_ENFORCE="false"`, so a single `ROLE_OPERATOR` can merge unilaterally and this resolver will follow that merge. Flipping both flags is the mitigation; the resolver's own kill switch (`openbank.edge.party-merge-follow-enabled`) is the containment lever meanwhile |

---

## 4. Open Risks (pre-GA gate)

| Risk | Severity | Owner | Target |
|------|----------|-------|--------|
| Defense-in-depth: account/balance-service do not self-enforce party ownership (read scoped by id; rely on edge + `ROLE_*` gating). A direct caller holding a `ROLE_SERVICE`/`OPERATOR` token bypasses the edge guard. | Medium | Platform | Phase 2 OPA (ADR-0034) + NetworkPolicy so customers reach these services only via the edge |
| M2M client secret rotation SLA (E-2): no defined rotation schedule | High | SecOps | Before GA — define in runbook |
| `/onboarding/start` CAPTCHA / proof-of-work (D-1): IP rate-limit alone insufficient for distributed flood | Medium | Platform | Phase 2 — behind feature flag |
| Request body PII logging (I-3): no automated PII scan of log payloads | Medium | Platform | Structured logging policy before GA |
| Mobile certificate pinning (T-1): Phase 2 only | Low | Mobile | ADR-0064 Phase F2 |

---

## 5. Security Controls Summary

| Control | Status |
|---------|--------|
| TLS 1.3 + HSTS preload | ✅ Deployed |
| Rate-limiting (20 req/s/IP) | ✅ Deployed |
| JWT validation (PKCE / RS256, `openbank-customers` realm only) | ✅ Deployed |
| `party_id` claim binding (user attribute mapper) | ✅ This PR |
| KYC gate before account opening | ✅ This PR |
| readOnlyRootFilesystem + drop ALL capabilities | ✅ Deployed |
| Denial-by-default route allow-list | ✅ Deployed |
| Device signing (SCA, ADR-0021) | ✅ Deployed |
| Edge-side ownership re-check on transaction-history read (IDOR guard, I-5) | ✅ 2026-06-08 (transactions route) |
| Edge-side debtor-account ownership check on payment initiation (IDOR guard, E-5) | ✅ 2026-06-08 (domestic-payments route; instruction only, no settlement yet) |
| SCA-challenge `partyId` injected from JWT (T-4) | ✅ 2026-06-08 (sca/challenges initiate) |
| Edge-side ownership check on statement-list read (IDOR guard) | ✅ 2026-06-08 (statements route) |
| Edge-side ownership check on account & balance reads (IDOR guard, I-1) | ✅ 2026-06-09 (`/accounts/{id}`, `/balances/{id}`) |
| OPA per-resource ownership policies | 🔲 Phase 2 |
| M2M secret rotation SLA | 🔲 Pre-GA |
| Mobile certificate pinning | 🔲 ADR-0064 Phase F2 |

## 6. Change log

- **2026-09-01** — **Delegated domestic spend is reserved before the rail.** The edge now accepts
  delegated debit authority only as the complete `DELEGATED` + grant UUID + grantor UUID tuple,
  consumes SCA, then reserves the exact amount and currency under the caller's stable
  Idempotency-Key before domestic-payment. The trusted grant and reservation headers are produced
  only inside the edge. The sender binding gate defaults off: owner payments stay unchanged and
  delegated initiation returns 503, preventing deployment order from restoring an unreserved
  bypass. A reservation refusal, outage, or invalid evidence never reaches the rail;
  a key replay with different money is 409. Domestic 201 and ambiguous failures retain headroom
  because neither proves settlement nor absence. Remote 400 is ambiguous too: the same key may have
  persisted on an earlier attempt, so without explicit `NOT_PERSISTED` evidence the edge never
  releases synchronously and leaves settlement to an authoritative terminal outcome or atomic
  receiver-side absent-payment tombstone; time alone never releases headroom. Rollback:
  revert the integration as one unit; otherwise removing only the reserve would re-open the
  concurrency gap while leaving downstream evidence headers optional.

- **2026-08-01** — ADR-0211 customer intake. `POST /customer/v1/loan-applications` forwards a loan
  application to lending-service. The party travels as `X-Customer-Party-Id`, derived from the
  customer JWT here — the app supplies only amount and term, so a customer can only apply for
  themselves. Deliberately NOT fail-soft (unlike the `/loans` read, which degrades to `[]`): for a
  write, a synthesised success would tell the customer an application was filed when none was, so
  the upstream status and reason pass through. The endpoint is inert until lending-service's own
  `lending.intake.enabled` is turned on, and lending refuses any caller that is not this service's
  configured principal — see §9 of the `openbank-lending-service` threat model for why the role gate
  cannot be that control.

- **2026-09-03** — **A delegated payment reserves against the grant's cumulative ceilings before it
  moves** (ADR-0249 D3). `POST /customer/v1/domestic-payments` now calls
  `POST /api/v1/delegations/{id}/reservations` on delegation-service before initiating, confirming
  on acceptance and releasing on every failure branch. Risk class = **elevation of privilege**: the
  per-transaction ceiling account-service already checked cannot see a second concurrent payment, so
  before this two requests could each pass a check that neither passes together. Properties this
  rests on: (a) reserve-then-confirm, never count-after — a RESERVED row consumes headroom while the
  payment is in flight; (b) a reservation that cannot be ESTABLISHED at all is a refusal, so an
  unreachable delegation-service stops the payment rather than waving it through; (c) the
  reservation carries the PAYMENT's idempotency key, so a rail replay takes the headroom once, and
  when the caller supplied no key each attempt reserves separately — over-counting a retry rather
  than under-counting a ceiling; (d) release runs on the SCA-refused and rail-refused branches, a
  leaked reservation being a ceiling that silently shrinks; (e) an owner paying from their own
  account reserves nothing, having no grant to count against. Named limit, stated rather than
  implied: "confirmed" means the rail ACCEPTED the instruction, not that it settled in clearing, so
  a payment accepted and later failed in clearing leaves the headroom consumed — over-counting, the
  safe direction for a ceiling. The refusal answers `code: DELEGATED_SPEND_LIMIT_EXCEEDED` and does
  not echo remaining headroom; the classified 409 stays in the audit trail.
  In the same change `POST /customer/v1/delegations` stopped refusing `dailyLimit`/`monthlyLimit`.
  That refusal existed because nothing counted cumulative spend, and once something did it became a
  deadlock rather than a control: delegation-service REQUIRES a cumulative ceiling on any grant
  carrying `ACCOUNT_INITIATE_PAYMENT` (ADR-0249 D5), so a payment-capable grant could not be created
  through the customer channel at all — and `POST /customer/v1/cards/delegated`, which requires such
  a grant to exist, was unreachable as a result. Rollback: revert the `reserveDelegatedSpend` call
  site and restore `rejectUnenforcedCeilings`; the route returns to per-transaction ceilings only,
  and payment-capable grants become unconstructible again.

- **2026-08-03** — **A delegate can pay from a shared account** (ADR-0232 D3/D5, issue #2990
  AC9/AC10). `POST /customer/v1/domestic-payments` previously 403'd any account the JWT party did
  not own; it now falls back to account-service's
  `/api/v1/accounts/{id}/delegation/payment-authorization` and proceeds when that answers
  `authorized`. Risk class = **elevation of privilege / spoofing**. Properties this rests on:
  (a) the edge does not decide — the decision is account-service's, and the only input the edge
  contributes is WHO is asking, resolved from the validated `party_id` claim and never from the
  body; (b) the authorization question carries the AMOUNT, without which the grant's
  per-transaction ceiling is not evaluated at all; (c) a non-200, unparseable, or
  `authorized`-without-a-grantor answer is a refusal — this path fails CLOSED; (d) every refusal
  reason collapses to one identical 403, so the route is not an oracle for other parties' accounts
  or grants; (e) SCA is unchanged and belongs to the INITIATOR — a grant is not a substitute for a
  device-signed, amount-and-payee-bound challenge.
  Two consequences worth naming. The delegated path re-fetches the debtor account **as the
  grantor**, because account-service's `X-Customer-Party-Id` guard is an ownership guard and 404s a
  delegate by design; that is not the edge self-authorizing, since the grantor's identity came from
  the authoritative decision one call earlier — but it does mean a bug in that decision widens into
  an account read, so the edge re-checks that the fetched account's owner IS the named grantor
  before proceeding. And the instruction now carries the ACCOUNT HOLDER's legal name as
  `debtorName`, not the initiator's: sending the delegate's would misattribute the transfer on the
  counterparty's statement and in every downstream AML party resolution.
  `GET /customer/v1/delegations/activity` is the grantor-side transparency view over audit-service's
  chain; the grantor is the token party, and the optional filters can only narrow a set already
  scoped to the caller. Rollback: revert the `resolveDebitAuthority` call site — the route returns
  to owner-only.
