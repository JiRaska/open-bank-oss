# ADR-0171 — Verification of Payee for outbound credit transfers

Date: 2026-07-16
Decision-Status: Accepted
Delivery-Status: Partial
Author(s): jiri.raska

## Context

Regulation (EU) 2024/886 (the Instant Payments Regulation, amending SEPA Regulation 260/2012)
obliges every PSP offering euro credit transfers to provide **Verification of Payee** (VoP):
before the payer authorises a transfer, the payer's PSP must check the payee name it was given
against the name actually held on the payee's IBAN, and tell the payer the outcome. The
obligation has applied to euro-area PSPs since **9 October 2025** — it is not a future
deadline, it is already in force.

The platform audit (`docs/audits/2026-07-16-platform-audit.md` §6.1) recorded VoP as the single
most urgent regulatory gap. The admin-UI payments page renders a `VopSection` whose result comes
from `setTimeout` + `Math.random()`, and there is no backend at all — no endpoint, no service,
nothing in the sepa-instant / sepa-payment / domestic-payment / psd2 Kotlin. The UI already
gates SCT Inst submission on that mocked value, so the platform today *appears* to enforce a
control that does not exist. That is worse than an absent control: it is a green light with
nothing behind it.

Three forces shape the design:

1. **VoP has two sides.** As the *requesting* PSP we ask the payee's PSP about someone else's
   IBAN; as the *responding* PSP we answer other PSPs about our own accounts. The IPR obliges
   both. The EPC VoP scheme is the interoperability layer between them.
2. **We have no scheme connectivity.** Exactly as with the payment rails themselves
   (`docs/ROADMAP.md`: interbank rails connect only to `openbank-clearing-simulator`), there is
   no live EPC VoP routing service here, and there will not be one in a reference
   implementation. An honest design must make the *absence* of an answer a first-class, visible
   outcome rather than fake one.
3. **The name is not where you would expect it.** `openbank-account-service` holds no holder
   name at all — its `accounts` table has no name column, and `V10__account_search_trgm.sql`
   indexes `account_number` only. The authoritative name is `parties.legal_name` /
   `parties.trading_name` in `openbank-party-service`, reachable from an account only via
   `party_id`. Resolving IBAN → name is therefore a two-hop lookup.

## Decision

We will introduce **`openbank-vop-service`**, an Apache-2.0 money-path service in the `payments`
domain, owning name-vs-IBAN verification for both VoP sides.

**1. Four outcomes, matching the EPC scheme and the existing UI contract.**
`MATCH` / `CLOSE_MATCH` / `NO_MATCH` / `NO_DATA`, serialised as `match` / `close_match` /
`no_match` / `no_data` — the wire values the admin UI's `VopStatus` union already expects.

**2. Matching is a pure domain policy.** `VopNameMatchPolicy` (framework-free, deterministic,
symmetric, clock-free) decides the outcome. It normalises through
`com.openbank.libs.identity.MatchKey.normalize` (NFD, strip diacritics, lowercase, collapse
whitespace) — we will **not** add a third copy of that normaliser; two already exist
(`MatchKey`, and `ProbabilisticMatcher`'s deliberately inlined duplicate), and a third would be
the drift. `CLOSE_MATCH` covers the cases the scheme intends: token reordering ("Jiří Raška" vs
"Raška Jiří"), a missing or initialised given name, a legal-form suffix ("Acme s.r.o." vs
"Acme"), and a single-character typo. Anything else present on both sides is `NO_MATCH`.

**3. VoP does not block a payment. It informs the payer.** IPR Art. 5c requires the PSP to
*notify* the payer of a mismatch and let them decide; it does not require refusal. So:
- The VoP result is advisory to the rails. `NO_MATCH` produces a warning the payer must
  acknowledge, not a rejection.
- A VoP outage, or a payee PSP that cannot answer, yields `NO_DATA` — **fail-open with an
  explicit warning**, never a silent `MATCH`.
- This is deliberately the *opposite* of the sanctions gate (ADR-0032), which fails **closed**
  (holds `PENDING`) because a sanctions miss is a legal breach. A VoP miss is not: refusing
  every payment during a VoP outage would itself breach the IPR's execution-time obligation.
  The two gates sit side by side in the same flow with opposite failure semantics **by design**
  — that contrast is the thing to remember when reading the code.

**4. We ship the responder side for real and the requester side honestly.**
- **Responder (our IBANs):** a real lookup — IBAN → account-service → `party_id` →
  party-service → `legal_name`/`trading_name` → `VopNameMatchPolicy`. This is the side we can
  implement truthfully, and the side other PSPs will call.
- **Requester (external IBANs):** we have no EPC scheme link, so a non-domestic IBAN returns
  `NO_DATA` with reason `NO_SCHEME_CONNECTIVITY`. The seam (`VopSchemeRoutingPort`) exists and
  is where a real EPC routing adapter plugs in; we do not pretend to have one. This mirrors the
  honesty the clearing simulator applies to the rails.

**5. Never echo a name we were not given.** On `NO_MATCH` the response carries the outcome only.
On `CLOSE_MATCH` it may return the actual name, because the scheme requires the payer to be able
to correct a near-miss, and the payer has already demonstrated near-knowledge of it. This
asymmetry is the whole defence against turning VoP into an account-holder-name disclosure
oracle — see the threat model.

**6. Every verification is recorded.** One `vop_verification` row per request (outcome, hashed
inputs, requester, timestamp) — evidence the control ran.

**7. Fraud reimbursement is explicitly out of scope.** The IPR/PSD3 liability shift — the PSP
bears the loss where it failed to warn — needs a claims process and a dispute path. The audit
flags it as absent (`grep reimburs` = 0 hits); this ADR does not close it, and VoP alone must not
be read as discharging it.

## Alternatives considered

- **Embed VoP in `openbank-sepa-instant`.** Fewest moving parts. Rejected: four rails
  (sepa-instant, sepa-payment, domestic-payment, psd2) plus the admin UI all need VoP, and the UI
  needs it *before* a payment exists. A payment service other payment services call into is a
  shape this codebase never uses; `openbank-sanctions-service` is the established precedent for a
  shared pre-execution gate, and VoP is structurally identical to it.
- **Put the holder name on `openbank-account-service` to collapse the two-hop lookup.** Faster.
  Rejected: it duplicates the authoritative name out of party-service and creates a second place
  for it to go stale — precisely the drift party-service exists to prevent. Latency is
  addressable with a cache in vop-service if measurement demands one.
- **Fail closed on VoP outage, mirroring the sanctions gate.** Superficially consistent with the
  neighbouring control. Rejected: it inverts the regulation. VoP protects the payer from their
  own misdirection and the payer may knowingly proceed; a hard block during an outage would
  breach the execution-time obligation the same regulation imposes.
- **Reuse `ProbabilisticMatcher` (pid-service) for the comparison.** Attractive: a real
  Fellegi–Sunter scorer with per-field explainability already exists. Rejected: it scores a
  structured `(givenName, familyName, birthdate, birthplace)` tuple, while VoP compares a single
  free-text name against a single account-holder name — there is no birthdate and no reliable
  given/family split on a SEPA name field. Its `compareName` *primitive* (normalise + bounded
  Levenshtein) is exactly right, and `VopNameMatchPolicy` follows it deliberately, but the scorer
  itself does not fit the input.
- **Ship VoP in SHADOW mode first, per ADR-0084's fraud rollout.** The established pattern for a
  new decisioning control. Rejected *for the control itself*: shadow mode means the payer is not
  told, which is the one thing the regulation requires, and the obligation is already in force.
  (Shadow remains right for *tuning the CLOSE_MATCH thresholds* — a metrics question, not a
  disclosure one.)

## Consequences

**Positive**
- The platform's most urgent in-force regulatory gap is closed on the responder side, and the
  requester side stops lying: `no_data` is a truthful answer where a random mock was.
- The admin UI's existing SCT Inst gate becomes real without a UI rewrite — the wire contract was
  designed to the enum the UI already has.
- One shared service means the other three rails plug in through one port with one set of
  thresholds, rather than four divergent copies.
- The name-disclosure asymmetry is decided once, in the domain, rather than per caller.

**Negative**
- A second synchronous hop (account-service → party-service) on the pre-payment path, on a
  platform the audit already flags as too synchronous (§2.3). VoP latency lands on the payer's
  critical path.
- vop-service is a name-disclosure surface that did not previously exist. Rate limiting and the
  no-echo-on-`NO_MATCH` rule are load-bearing, not hygiene.
- `CLOSE_MATCH` thresholds are judgement calls with no production data behind them. They will be
  wrong at first; they are constructor parameters so they can be tuned without touching the
  algorithm.
- Recording verifications creates a new personal-data store (see GDPR below).

**Neutral**
- The requester side is a stub-with-a-seam until an EPC VoP routing adapter exists — a
  deliberate, documented gap, consistent with the rails.

## Compliance impact

- **IPR (Reg. (EU) 2024/886) Art. 5c:** this ADR addresses the VoP obligation, in force since
  2025-10-09. Responder side delivered; requester side seam-only pending scheme connectivity.
  Fraud liability / reimbursement (Art. 5d) explicitly NOT addressed here.
- **PSD2:** not directly applicable — VoP is an IPR obligation, not an SCA one. VoP neither
  replaces nor weakens the SCA gate (ADR-0021).
- **GDPR:** Art. 5(1)(c) data minimisation is the live constraint. party-service's
  `V7__party_name_search_trgm.sql` already establishes the discipline (only name columns
  searchable; the birth number deliberately not) and vop-service inherits it. The
  `vop_verification` record stores the outcome and **hashes** of the supplied name/IBAN, not the
  plaintext payee name — evidence that the control ran does not require retaining every name
  anyone ever typed. Retention is 13 months (the fraud-claim window), **not** the 7-year
  accounting default, because these are not accounting records. Classification: `confidential`.
- **DORA:** vop-service is on the money path, inheriting the ICT-RM obligations of ADR-0134, and
  requires a threat model per ADR-0030 (`docs/threat-models/openbank-vop-service.md`).
- **CNB:** no separate reporting obligation.
- **PCI DSS:** not applicable — no card data.

## References

- Regulation (EU) 2024/886 (Instant Payments Regulation), Art. 5c — Verification of Payee
- EPC VoP scheme rulebook — the `MTCH` / `CMTC` / `NMTC` / `NOAP` outcome set
- [ADR-0032](0032-synchronous-sanctions-aml-screening-gate-in-payment-execution.md) — the
  neighbouring pre-execution gate, with deliberately opposite (fail-closed) semantics
- [ADR-0021](0021-sca-decoupled-device-approval-no-auto-approve.md) — SCA; VoP does not replace it
- [ADR-0084](0084-fraud-detection-bounded-context.md) — the SHADOW-mode rollout pattern, adopted
  for threshold tuning but rejected for the control itself
- [ADR-0094](0094-eudi-native-identity-hub.md) — `ProbabilisticMatcher`, the name-comparison
  primitive this policy follows
- [ADR-0118](0118-gdpr-data-lifecycle-and-retention.md) — PII classification and retention model
- [ADR-0030](0030-supply-chain-security-and-ssdlc-hardening.md) — money-path threat-model requirement
- `docs/audits/2026-07-16-platform-audit.md` §6.1 — the gap this ADR closes
- `docs/threat-models/openbank-vop-service.md` — name-disclosure oracle analysis
