# 06 — Compliance

Decision of record: [ADR-0171](../../../../docs/adr/0171-verification-of-payee-for-outbound-credit-transfers.md). Threat model: [`openbank-vop-service.md`](../../../../docs/threat-models/openbank-vop-service.md) (ADR-0030 D2, required — money-path).

## Instant Payments Regulation (EU) 2024/886, Art. 5c — the driver

**In force for euro-area PSPs since 9 October 2025.** Not a future deadline.

| Obligation | Status |
|---|---|
| Check the payee name against the IBAN before the payer authorises | **Delivered** — responder side, real lookup |
| Tell the payer the outcome | **Delivered** — four outcomes on the wire; the admin UI renders them |
| Do not refuse on mismatch — warn and let the payer decide | **Delivered by design** — VoP never blocks; `no_match` is a warning |
| Answer for our own IBANs when another PSP asks | **Delivered** — this is the responder side |
| Ask the payee's PSP about external IBANs | **Seam only** — no EPC VoP scheme link exists here (as with the rails, which reach only `openbank-clearing-simulator`). External IBANs return `no_data` / `NO_SCHEME_CONNECTIVITY`. |

Under Art. 5c a truthful *"we could not verify"* discharges the duty to inform in a way a fabricated "match" would not. That is why the requester side answers honestly rather than guessing.

## Art. 5d — fraud reimbursement — **NOT addressed**

The IPR/PSD3 liability shift, where the PSP bears the loss if it failed to warn, is **explicitly out of scope** (ADR-0171 §8). `grep reimburs` returns 0 hits fleet-wide: there is no claims process and no dispute path.

VoP produces the *evidence* such a process would need (`vop_verification`). **It does not discharge Art. 5d, and must not be read as doing so.** This is the honest statement of a real remaining gap, not an oversight.

## GDPR

The live constraint is **Art. 5(1)(c) — data minimisation**, and it shapes the schema:

| Decision | Rationale |
|---|---|
| Evidence stores `sha256(iban)` + `sha256(name)`, never plaintext | Proving the control ran does not require retaining every name typed into a payment form. A fraud claimant supplies the inputs, so the hashes still answer the only question that gets asked. |
| Retention **13 months**, not the 7-year accounting default | A VoP record is evidence a control ran, **not an accounting record** (ADR-0118). |
| `POST`, not `GET` | The IBAN and name must never reach a URL, access log, or referer header. |
| `PartySummary` mirrors only `legalName` / `tradingName` | VoP compares names. It must not fetch identifiers, birth data, or contact details it has no use for. Inherits party-service's `V7__party_name_search_trgm.sql` scope note. |
| No account-holder name cache | No second copy of personal data to secure, or to go stale. |
| Classification `confidential` | `governance.yaml`. |

**Art. 6 lawful basis:** processing is necessary for compliance with a legal obligation (Art. 6(1)(c)) — the IPR Art. 5c duty itself.

**Open item:** the 13-month retention has **no scheduler yet**. Retention is currently a stated policy, not an enforced one. Follow the ADR-0118 `*RetentionScheduler` pattern.

### The disclosure asymmetry is a GDPR control, not just a security one

VoP is by construction an oracle over account-holder names — it identifies **who banks here**. Uncontrolled, that is both a personal-data breach and a precursor to targeted social engineering. Authorization cannot bound it (a payer must be able to check a payee they do not own). So:

- **`no_match` returns the outcome only** — a wrong guess reveals nothing but that it was wrong.
- **`close_match` returns the name** — but only to a caller who already *nearly* knew it, which is the correction case the scheme requires.
- **An unknown IBAN is a 200 + `no_data`, never a 404** — a status code that says "not our account" is itself an enumeration primitive.
- **The rate limit (60/min)** bounds how many attempts anyone gets.

The residual is real and documented: a `close_match` **is** a disclosure to a near-guesser. That is inherent to the scheme — the regulation requires the payer be able to correct a near-miss — which is why the rate limit is load-bearing rather than hygiene.

## DORA

Money-path (`rules.yaml: money_path_services`) ⇒ inherits ADR-0134 ICT-RM: RTO/RPO tiering, the threat-model requirement (ADR-0030 D2), and both Pyrra SLOs.

Note the unusual resilience posture for a money-path service: **VoP failing does not fail a payment.** CNPG HA (`instances: 2`) is here so the *evidence record* survives a node roll, not because the payment path stops without it. A VoP outage is a **compliance** incident (payers going unwarned), not a payment outage — and must not be remediated by holding payments.

## PSD2 / SCA

**Not directly applicable.** VoP is an IPR obligation, not an SCA one. It neither replaces nor weakens the SCA gate (ADR-0021): a payer who proceeds past a `no_match` warning still authenticates normally.

## AML / sanctions

**Not applicable here** — but note the deliberate contrast with the neighbouring control. `openbank-sanctions-service` (ADR-0032) fails **closed**, because a sanctions miss is a legal breach. VoP fails **open**, because refusing every payment during a VoP outage would itself breach the IPR execution-time obligation. Both gates sit in the same pre-execution flow with opposite semantics, on purpose.

## PCI DSS

**Not applicable** — no cardholder data.

## ČNB

No separate reporting obligation.

## Audit-relevant open items

Carried here rather than buried, per the repo's honesty convention:

1. **Rate limiting is application-layer only** — no WAF, no edge/volumetric protection anywhere in the platform ([audit](../../../../docs/audits/2026-07-16-platform-audit.md) §4.3).
2. **No enumeration detector** — the index supports the query; the detector does not exist.
3. **Retention sweep not implemented** (above).
4. **`close_match` thresholds are unvalidated guesses.** Too loose reassures fraud victims; too tight trains payers to click through warnings. Tune from outcome metrics.
5. **`max-edit-distance` has no four-eyes gate** — it is gitops config, so PR review covers it. If it ever becomes operator-tunable, add `vop.flip` to `four_eyes.verbs`: widening it silently turns genuine mismatches into reassuring amber warnings.
6. **Requester side is a seam** (above) — a delivery gap, not a security one.
