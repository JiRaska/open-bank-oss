# 01 — Overview

## What this service does

`openbank-vop-service` answers one question:

> Is the payee name the payer typed the name actually held on the payee's IBAN?

It answers with one of four outcomes — `match`, `close_match`, `no_match`, `no_data` — and the payer is told before they authorise the transfer. That is Verification of Payee (VoP), and it has been mandatory for euro-area PSPs since **9 October 2025** under Regulation (EU) 2024/886 (the Instant Payments Regulation, Art. 5c).

The purpose is narrow and worth stating precisely: VoP protects a payer from **their own misdirection** — a mistyped IBAN, a stale account number, an invoice whose bank details were tampered with. It does not detect fraud, screen sanctions, or judge whether the payment is a good idea. It tells the payer *who actually holds the account they are about to pay*.

## Why it exists (the honest version)

Before this service, the admin UI's payments page rendered a Verification-of-Payee panel whose result came from `setTimeout` + `Math.random()`, and the SCT Inst submit path already gated on that mocked value. The platform *appeared* to enforce a control that did not exist — a green light with nothing behind it, which is worse than no control at all. The [platform audit](../../../../docs/audits/2026-07-16-platform-audit.md) recorded it as the single most urgent regulatory gap. [ADR-0171](../../../../docs/adr/0171-verification-of-payee-for-outbound-credit-transfers.md) is the decision; this service is the delivery.

## Where it sits

VoP has two sides, and this service is honest about which one it really implements:

| Side | Meaning | Status here |
|---|---|---|
| **Responder** | Another PSP asks *us* about an IBAN we hold | **Real.** IBAN → account-service → `partyId` → party-service → `legalName`. This is the side other PSPs call, and the side we can implement truthfully. |
| **Requester** | *We* ask the payee's PSP about someone else's IBAN | **A seam, not a capability.** There is no EPC VoP scheme routing link in this platform. An external IBAN returns `no_data` / `NO_SCHEME_CONNECTIVITY` via `VopSchemeRoutingPort`, where a real adapter would plug in. |

That mirrors the rails themselves: `openbank-sepa-instant` reaches only `openbank-clearing-simulator`, not a live scheme. Faking a verdict for an IBAN we cannot ask anyone about would be worse than admitting we cannot check — and under Art. 5c, a truthful "unverified" **discharges** the duty to inform in a way a fabricated "match" would not.

## Who calls it

- **`openbank-admin-ui`** — the payments console's Verify button, through the BFF proxy (`/api/svc/vop-service`), with the signed-in operator's own bearer token.
- **The payment rails** (sepa-instant, sepa-payment, domestic-payment, psd2) — as M2M callers, before execution. *This wiring is the natural next step; the shared port exists so all four plug into one set of thresholds rather than four divergent copies.*

## What it deliberately does not do

- **It does not block a payment.** Art. 5c requires the PSP to *notify* the payer of a mismatch and let them decide. `no_match` produces a warning the payer must acknowledge, not a rejection.
- **It does not fail closed.** A lookup outage yields `no_data` + a warning. Refusing every payment during a VoP outage would breach the execution-time obligation the same regulation imposes. See [02 — Architecture](./02-architecture.md).
- **It does not implement fraud reimbursement.** The IPR/PSD3 liability shift (Art. 5d) — where the PSP bears the loss if it failed to warn — needs a claims process and a dispute path. VoP produces the *evidence* such a process would need, but **VoP alone must not be read as discharging Art. 5d**. See [06 — Compliance](./06-compliance.md).
- **It does not cache the account-holder name.** The authoritative name lives in party-service and is resolved live. A local copy would be a second place for it to go stale.

## The tension at the heart of this service

VoP is, by construction, an **oracle over account-holder names**. Anyone who can call it can ask "does name X hold IBAN Y?" and get a truthful answer. That is precisely the function the regulation mandates — and precisely what someone enumerating a bank's customers would want.

Authorization cannot resolve this: a payer must be able to check a payee they do not own, so any read-role holder may call it. The controls are therefore:

1. **The disclosure asymmetry** — `no_match` returns the outcome *only*, so a wrong guess teaches an attacker nothing but that they were wrong. Only `close_match` returns the real name, and only to someone who already *nearly* knew it (which is the case the scheme requires us to let the payer correct).
2. **The rate limit** — 60/min per requester. It does not remove the `close_match` disclosure; it bounds how many times anyone can attempt it.

Both are load-bearing, not hygiene. The [threat model](../../../../docs/threat-models/openbank-vop-service.md) treats information disclosure as the primary threat — the inverse of most money-path services, where the danger is approving something that should be refused.
