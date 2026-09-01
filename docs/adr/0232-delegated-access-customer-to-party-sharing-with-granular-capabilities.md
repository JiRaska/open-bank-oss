---
date: 2026-07-31
decision-status: proposed
delivery-status: partial
followup: "none — the guards and the service are live; no payment service calls them, which is the tail"
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [authz, accounts, sca, compliance]
summary: "Delegation service owns granular, SCA-bound grants over products (accounts, cards, savings) and single objects (payment, statement, document) — incl. propose-only flows and external disclosure; event-fed enforcement; SME-ready."
---

# ADR-0232 — Delegated access: customer-to-party sharing with granular capabilities

**Delivery note (2026-08-19).** `openbank-delegation-service` is live at `version.txt` 0.7.0,
deployed via `auto-deploy.yml`, carries `DelegationGrant`/`SpendCeilings`/`SpendReservation` and a
threat model, and is listed money-path in `rules.yaml`. The amount-aware guards are now backed by
the **enforced** `enforcement-reachability` gate (#3615). What is still unbuilt is the half that
issue named: no payment service integrates delegation — a grep for `delegation` across
`openbank-domestic-payment/src/main` and `openbank-sepa-payment/src/main` returns nothing, so the
delegated money path is reachable in the service and not yet on the payment path.

Relates: ADR-0034 (unified OPA), ADR-0072 (party identity), ADR-0094
(EUDI hub), ADR-0118 (GDPR lifecycle), ADR-0126 (unified consent),
ADR-0133 (tamper-evident audit), ADR-0155 (four-eyes), ADR-0162
(document e-signature), ADR-0206 (M2M consent), ADR-0223
(channel-agnostic authz), ADR-0227 (unified approval inbox),
ADR-0229 (roles SSOT), ADR-0152 (single-tenancy).

## Context

Today a bank customer can share a product with another person in exactly
zero ways — and cannot share a single *object* (one payment, one
statement, one document) either. Everyday life needs both: a spouse with
standing access, an accountant who gets a redacted statement, a
Marketplace buyer who wants proof one specific payment left, a child who
may propose but not send.

The platform already carries three *adjacent* models, none of which is
customer-to-customer delegation:

1. **`AccountAuthorization` (account-service)** — roles
   FULL_ACCESS/PAYMENT_ONLY/READ_ONLY/CARD_HOLDER, per-tx/daily limits,
   validity window, `SigningRule` (SINGLE/JOINT_ALL/JOINT_ANY_TWO/
   OWNER_PLUS_ONE). A good seed, but it is account-local: cards, savings
   goals and future products cannot reuse it without violating
   database-per-service (ADR-0009), and nothing enforces it outside
   account-service.
2. **consent-service** — grants to *third parties* (TPP with eIDAS cert,
   bank/customer AI agents; ADR-0126, ADR-0206). Legally a different
   instrument: PSD2 RTS caps AISP validity at 90 days and frequencies at
   4 reads/day — rules that must NOT leak onto a spouse or an employee of
   an SME customer, and vice versa.
3. **OPA sidecar + roles (ADR-0034/0229)** — decides *staff and agent*
   authorization. A retail delegate is not a realm role; putting
   "Pavel may pay up to 5 000 CZK from Jana's account" into Keycloak
   groups would explode the role vocabulary and carry no per-resource
   constraints.

Meanwhile the SME segment is coming: a legal entity whose employees need
scoped, revocable, limit-bearing, auditable access — treasurer vs
accountant vs viewer, dual authorization above thresholds. Building
retail sharing now and SME entitlements later as two systems guarantees
the #2404 drift pattern one level up. Legal forces: a delegate acts as
the owner's agent under the payment framework contract (NOT as a TPP —
no eIDAS, no XS2A), must be a KYC'd party (AML), must authenticate with
their OWN identity and SCA (credential sharing is a contractual and
fraud nightmare), and the owner must retain transparency + instant
revocation (GDPR, consumer-protection).

## Decision

We will build customer-to-party **delegated access** as a first-class
bounded context, designed once for retail and SME, covering both
standing product grants and single-object disclosure.

**D1 — New `openbank-delegation-service` owns the `DelegationGrant`
aggregate.** Fields: `grantorPartyId`, `granteePartyId`,
`resource: {type: ACCOUNT | SAVINGS_GOAL | CARD | LOAN | PAYMENT |
STATEMENT | DOCUMENT | …, id}`,
`capabilities: Set<Capability>`, `constraints` (per-transaction / daily /
monthly limit, currency, validity window), `approvalPolicy`
(SOLO | ANY_ONE | ALL | N_OF_M — the SME-ready generalization of
`SigningRule`), lifecycle `OFFERED → ACTIVE → SUSPENDED | REVOKED |
EXPIRED | DECLINED`, SCA ceremony refs for both grant and acceptance,
and full audit fields. consent-service is NOT extended (D-alternative
below); `AccountAuthorization` is migrated: delegation-service becomes
the system of record, account-service keeps only an enforcement
projection.

**D2 — Capabilities are a closed, resource-scoped vocabulary in
`rules.yaml`, same single-source discipline as roles (ADR-0229).** e.g.
`account.read-balances`, `account.read-transactions`,
`account.initiate-payment`, `account.propose-payment`, `card.view`,
`card.manage-limits`, `savings.deposit`, `savings.withdraw`,
`object.read`. Named presets ("Rodinný rozpočet", "Karta pro dítě",
"Účetní", "Pokladník", "Kapskové", "Trusted contact") are data over that
vocabulary — UX sugar, never a parallel permission system. Numeric
constraints and validity travel with the grant, not the capability.

**D3 — Enforcement is decentralized and fail-closed, never synchronous
on the money path.** Each product service consumes
`delegation.granted|suspended|revoked|expired` events (transactional
outbox, ADR-0003/0050) into a local projection and its domain guard
authorizes `owner OR active-grant(caller, resource, capability, amount)`
— the same shape `AuthorizationService.isAuthorized` already has, now
fed cross-product. OPA keeps deciding channel/role policy (ADR-0034);
the grant check is a domain invariant of the resource owner, so a
delegation-service outage blocks *changes* to sharing, never existing
reads/payments. Money-path actions executed BY a delegate carry
`onBehalfOf: grantorPartyId` through the audit chain (ADR-0086/0133) —
identity threading already exists, we extend the claim.

**D4 — Lifecycle is a dual-consent ceremony, revocation unilateral.**
Grantor offers (SCA-bound, ADR-0021) → grantee accepts in-app with own
SCA → ACTIVE. Grantor revokes instantly, unilaterally, 24/7; grantee
may renounce; the bank may suspend (fraud/AML signal). Every transition
emits an outbox event and a notification to both parties
(notification-service). Grant of a payment capability above a
bank-defined threshold is four-eyes flagged (ADR-0155) and lands in the
unified approval inbox (ADR-0227).

**D5 — Eligibility gate: delegates are real, screened parties.**
Grantee must be an existing party (ADR-0072) with `KycLevel.FULL` for
any payment capability, `BASIC` for read-only; sanctions/PEP screening
at grant time and on grantee status change (same gate pattern as
ADR-0032). Delegates always authenticate with their OWN passkey/SCA
(ADR-0066) — the model structurally cannot express "log in as the
owner". Grantor-side actor on a LEGAL_ENTITY grantor must itself hold
`delegation.manage` on that entity or be its statutory representative —
this one rule is the entire retail→SME bridge.

**D6 — UX is invitation-based and radically transparent.** Grantor
invites by verified contact (phone/email resolved to a party, never by
typing an IBAN); grantee sees a plain-language summary ("Jana ti umožní
platit z účtu ••••1234, max 5 000 Kč/den, do 31. 12. 2026") before
accepting. Both sides get a "Shared by me / Shared with me" dashboard
via customer-edge, the grantor sees a per-delegate activity feed, and
every screen meets WCAG 2.2 AA (ADR-0149) with cs/en from day one
(ADR-0150).

**D7 — Object-level grants and external disclosure.** A grant may target
a single object instance (`PAYMENT`, `STATEMENT`, `DOCUMENT`) with
capability `object.read` and an `exposure` constraint: redaction rules
(selective disclosure — "only credits above 20 000 CZK", "hide
counterparties"), `maxViews` (incl. view-once), watermarking with the
recipient's identity, download permission, and a short validity window
(days, not months). Rendered output is PAdES-signed by document-service
(ADR-0162) so the recipient holds a verifiable artifact. Two recipient
modes: (a) **party recipient** — an ordinary grant to a KYC'd party,
enforced via the D3 projection; (b) **external recipient** — the
recipient is not a customer: delivery via a single-use, OTP-gated,
expiring link (secure disclosure, no account needed), with datová
schránka as the Czech high-assurance channel for authorities and
businesses. External disclosure is not a delegation of access — nothing
external touches a live API; it is a sealed, auditable, revocable
*document emission* from the same aggregate, so grantor transparency
("kdo si to stáhl, kdy") and the audit chain (ADR-0133) apply
identically. EUDI-wallet verifiable credentials (bank as issuer of
proof-of-payment / proof-of-balance credentials, incl. predicate
proofs) are deliberately OUT of scope here and promoted to a follow-up
ADR on top of ADR-0094.

**D8 — Propose-only flows and group sharing.** Capability
`account.propose-payment` (and `savings.propose-withdraw`) gives the
delegate a maker role with NO execution right: the proposal lands in the
owner's approval inbox (ADR-0227) and executes only after the owner's
SCA — retail maker-checker on the ADR-0155 four-eyes machinery, no new
approvals infrastructure. `approvalPolicy` applies per-resource, not
just per-grant: a shared savings goal can require N_OF_M co-signers to
withdraw ("oba rodiče musí schválit výběr"), which is the same
mechanism SME dual control uses above thresholds. Presets encode the
social patterns: "Kapskové" (propose-only + merchant-category rules +
weekly cap), "Společný cíl" (shared vault, N_OF_M withdrawal), "Senior
trusted contact" (read-only + fraud alerts, structurally incapable of
transacting).

## Alternatives considered

- **Extend consent-service with a `CUSTOMER` grantee type** — rejected:
  the PSD2 instrument and the contractual mandate differ in validity
  caps, SCA ceremony, liability and revocation semantics; the
  AISP/GDPR-only scope disjointness (ADR-0205) is already fragile, and
  one aggregate encoding opposite regulatory regimes repeats that
  defect class at domain level.
- **Replicate the `AccountAuthorization` table per product service** —
  rejected: N divergent sharing models, no single "who has access to
  what" answer for the customer or for compliance, and every new
  product re-implements lifecycle, SCA and notifications.
- **Central ReBAC engine (OpenFGA/SpiceDB, Zanzibar-style)** — rejected
  for now: a synchronous graph lookup on the money path is a new
  availability SPOF and a new PII store; the event-fed local projection
  (D3) delivers the same semantics on the existing outbox/Kafka
  substrate. The delegation read port is designed so a ReBAC engine can
  replace the projection later without touching product services.
- **Keycloak groups/attributes per grant** — rejected: no per-resource
  constraints, realm becomes the authorization database (against
  ADR-0229's single-source direction), and grants would bypass the
  audit chain and approval inbox.
- **EUDI verifiable credentials in this ADR** — deferred, not rejected:
  bank-issued proof-of-payment / proof-of-balance / predicate proofs
  ("balance ≥ X" without revealing X) are the strongest privacy
  disclosure mechanism, but they belong to the EUDI identity hub's
  issuance lifecycle (ADR-0094) and deserve their own ADR covering
  wallet UX, revocation registries and eIDAS 2.0 conformance. D7's
  external-disclosure port is shaped so VC issuance becomes a third
  delivery channel without reworking the aggregate.

## Delivery status, measured

As of 2026-08-03, against `origin/main`, on the **numeric constraints** of D1
only — the rest of the ADR is not assessed here.

| Element of the decision | State |
| --- | --- |
| `perTransactionLimit` on the aggregate | **Done** — `DelegationGrant.withinLimits`, and mirrored into account-service's projection |
| `dailyLimit` / `monthlyLimit` accepted by the API | **Was done, now refused** — see below |
| Cumulative daily/monthly ceiling ENFORCED anywhere | **None.** No spend counter exists in any service |
| `DelegationOffered` carrying the two ceilings | **No.** The event has `perTransactionLimit` only, so no projection can learn them |

D1 lists "per-transaction / daily / monthly limit" among the grant's
constraints and D6 renders the promise to the customer verbatim — *"max
5 000 Kč/den"*. The per-transaction half shipped. The cumulative half never
did, and the gap was invisible because the fields existed at every layer
except the one that matters: `OfferDelegationRequest` accepted them,
`DelegationGrant` held them, the entity persisted them, and
`DelegationResponse` echoed them back — while nothing anywhere counted spend
against either. The API therefore answered 201 to a request that capped
nobody. The customer app declines to render a ceiling chip for this reason
(openbank-app#360), which contained the damage at one client and not at the
contract.

**Both fields are now rejected on offer** (400
`CUMULATIVE_LIMIT_UNSUPPORTED`, at customer-edge and at delegation-service),
and are marked unsupported in both `openapi.yaml`s. This does not deliver
D1/D6 — it stops the platform claiming them. A correct implementation needs a
spend counter that is incremented in the same transaction that debits the
account, and today there is no such transaction to join: no money-moving
service reads a delegation grant at all, so an accumulator built now would
have no writer. Delivering the ceilings means first wiring a delegated
money path, then counting on it — in that order, or the counter is a second
number nobody applies.
## Delivery status: is the enforcement path reachable, measured

As of 2026-08-03, against `origin/main`, on **D3's enforcement half only** —
whether a guard exists is a different question from whether anything asks it.
Tracked as issue #3615.

| Guard | Takes an amount | Production caller |
| --- | --- | --- |
| `AuthorizationService.isAuthorizedForAmount` (account) | yes | **None** — every call site is in `AuthorizationServiceDelegationTest` |
| `GET /api/v1/accounts/{id}/authorizations/check` | no | None in-repo; calls the amount-free `isAuthorized` |
| `GET /api/v1/accounts/{id}/savings-goal/delegation/check` | no | None in-repo |
| `GET /api/v1/cards/{id}/delegation/check` | no | None in-repo |
| `POST /api/v1/delegations/check` (delegation-service) | yes | The read-only admin-ui probe only |
| `SavingsWithdrawalApproved` | — | **No consumer** in any service or in `openbank-contracts` |

D3 calls the projection check "the last line before the money path". There is no
money path behind it. No money-moving service — domestic-payment, sepa-payment,
sepa-instant, swift, standing-order, settlement, clearing, transaction, ledger —
reads a delegation grant or projection at all, and customer-edge payment
initiation requires debtor-owner == the authenticated party, so a delegate is
refused with 403 before any guard is consulted. `SavingsWithdrawalApproved` is
documented in `SavingsProposalService` as "the executable instruction the
payments path consumes"; nothing consumes it.

The guards are correct code and are **kept**, unchanged. What is recorded here is
that they are not invoked, because nothing in the repo could otherwise say so:
each one compiles, is a CDI bean method or a registered route, has a port
declaring it and a green test class — and coverage inverts the signal, since a
well-tested unreachable guard scores higher than a lightly-tested reachable one.
`isAuthorizedForAmount` is now declared in
`rules.yaml: enforcement_reachability` and checked by the `enforcement-reachability`
gate, which fails both when a `reachable` entry loses its last caller and when
this `unreachable` entry gains one — so closing the gap requires updating this
table rather than allowing it to go quietly stale.

This is the same defect class as the `dailyLimit`/`monthlyLimit` refusal: there a
declared number had no counter, here a declared check has no caller. The order of
repair is the same and is stated in #3615 — a delegated payment path first, the
guard consulted on it second, the cumulative counter last. A limit is only worth
computing once there is a debit to refuse.

## Consequences

**Positive**
- One delegation model serves retail sharing today and SME entitlements
  tomorrow — an SME employee is just a grantee party of a LEGAL_ENTITY
  grantor; approvalPolicy + four-eyes already cover dual control.
- Owner transparency and instant revocation are structural, not policy
  promises — a differentiator vs incumbent banks' paper mandates.
- Single-object, redacted, verifiable disclosure (D7) replaces today's
  grey practice of emailing full statements — a privacy win the
  competition charges accountants for, delivered as a preset.
- Propose-only + group sharing (D8) opens demographics incumbents serve
  with separate products (junior accounts, senior care) using one
  mechanism already governed by ADR-0155/0227.
- Product services keep money-path autonomy (no runtime dependency on
  delegation-service); the aggregate is small, auditable, and
  contract-testable (Pact) like every other service.
- Capability vocabulary in rules.yaml gives governance one reviewed
  diff per new capability, generated into backend, events and UI.

**Negative**
- A 31st service with its own DB, outbox, SLO and threat model
  (ADR-0030 — it is money-path-adjacent: it mints payment rights).
- Event-fed projection is eventually consistent: a revocation takes
  seconds, not milliseconds, to reach enforcement points — accepted,
  and **not** mitigated by suspend-now fraud hooks: no such hook exists.
  Fraud scoring is shadow-only on every rail and its verdict is discarded at
  the activity boundary (#4403), so the revocation lag is currently accepted
  unmitigated rather than covered.
- `AccountAuthorization` migration needs a dual-run period and a
  backfill; until it completes, two grant sources exist for accounts.
- External disclosure links are an exfiltration surface: OTP + expiry +
  maxViews + watermark mitigate, but the threat model must treat a
  leaked link as a leaked document (it is) and prove revocation kills
  the link.

**Neutral**
- PSD2 consent flows are untouched; TPP and agent grants keep their
  90-day caps and stay in consent-service.
- EUDI verifiable-credential disclosure is a follow-up ADR; this
  decision neither builds nor blocks it.

## Compliance impact

- PCI DSS: not applicable — no cardholder-data environment change; card
  PAN remains behind the synthetic-PAN vault (ADR-0194), delegates get
  card *management*, never PAN disclosure, without explicit scope.
- DORA: new service enters the ICT risk register and BCP (ADR-0134);
  threat model required before first deploy (money-path-adjacent).
- GDPR: legal basis is contract (the mandate), not consent-service
  consent; D7 redaction IS data-minimization made mechanical; grantor
  transparency feed and recipient access-log visibility are built in
  (D6/D7); delegation records are contract evidence under the ADR-0118
  retention schedule — erasure anonymizes, does not delete. External
  disclosure requires the plain-language summary to state exactly what
  leaves the bank, to whom, and until when (Art. 13/14 transparency).
- PSD2: the delegate is the owner's agent under the framework contract,
  not a TPP — no eIDAS/XS2A; SCA on the delegate's own credentials per
  RTS; propose-only (D8) never moves money without the owner's SCA, so
  no new exemption surface; owner liability up to the grant constraints
  must be reflected in the product terms.
- CNB: delegates are KYC'd and screened parties (AML Act 253/2008 Sb.
  identification duty extends to persons authorized to dispose);
  grant/revoke, every delegated money-path action and every external
  disclosure land in the tamper-evident audit chain (ADR-0133).

## References

- Seed model: `openbank-account-service/.../domain/model/AccountAuthorization.kt`,
  `AuthorizationService.kt`, `AuthorizationResource.kt`
- Party model: `openbank-pid-service/.../domain/model/Party.kt`
  (LEGAL_ENTITY, AUTHORIZED_PERSON)
- Consent boundary: `openbank-consent-service/.../domain/model/Consent.kt`
- Signed artifacts: `openbank-document-service/.../render/PadesSigning.kt`
- ADR-0003, ADR-0021, ADR-0032, ADR-0034, ADR-0066, ADR-0072, ADR-0086,
  ADR-0094, ADR-0118, ADR-0126, ADR-0133, ADR-0149, ADR-0150, ADR-0155,
  ADR-0162, ADR-0205, ADR-0206, ADR-0223, ADR-0227, ADR-0229
