---
date: 2026-09-26
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [libs, interest, fx, fees-billing]
summary: "libs Money gains a largest-remainder allocate/split and a named rounding-policy registry (interest accrual, FX, fees, tax, display); services stop choosing RoundingMode and scale inline, enforced by a ratchet gate."
---

# ADR-0318 — Money allocation and rounding policy registry

## Context

ADR-0025 decided per-currency ledger balancing and ADR-0046 FX revaluation mechanics; neither
decides *how an amount is rounded or split* before it is posted. The shared value type
`openbank-libs-domain/.../money/Money.kt` rounds exactly once, in its constructor, with
`amount.setScale(currency.defaultFractionDigits, RoundingMode.HALF_EVEN)`, and offers no
`allocate`/`split` operation. Every context that needs a different scale or mode (sub-cent
accrual, FX inversion, fee percentage, withholding tax) therefore does its own arithmetic on raw
`BigDecimal`/`Long`. Bitemporal effective-dating of rules is already planned elsewhere
(ADR-0305, ADR-0308) and is out of scope here.

Measured on `origin/main` 2026-09-26, over `openbank-*/src/main/**/*.kt` excluding `openbank-libs*`:

| Probe | Result |
|---|---|
| `setScale(` call sites | 55, across 15 modules (risk-engine 9 files, interest/statement/delegation 3 each) |
| `.divide(` call sites | 40, in 19 files |
| `RoundingMode.HALF_UP` | 30 uses, 11 modules (incl. money-path fx, interest, ledger, transaction, sdd, domestic-payment) |
| `RoundingMode.HALF_EVEN` | 21 uses, 4 modules (ledger, transaction, risk-engine, delegation) |
| `RoundingMode.DOWN` | 6 uses (interest withholding tax, customer-edge, simulation) |
| Money-amount allocation/split helper | none (the only `allocate` is loyalty's lot allocator for points) |

Two concrete consequences of that spread:
- **The libs default and the services disagree.** `Money` rounds HALF_EVEN; interest-service
  rounds gross and net interest to the currency scale with HALF_UP
  (`InterestService.kt`), and fx-service rounds converted amounts and its fee with HALF_UP
  (`FxRate.kt`). Ledger and transaction-service use both modes. So the same amount can round
  differently depending on which service touched it last.
- **Nothing splits an amount so the parts sum to the whole.** Any fan-out (a fee across pockets,
  interest across co-owners, a tax remittance across periods) that divides and rounds each part
  independently can lose or create a minor unit; the ledger's per-currency balancing (ADR-0025)
  then rejects or, worse, absorbs it into a suspense line.

## Decision

We will:

1. Add to `openbank-libs-domain` a **`Money.allocate(ratios)`** and **`Money.split(n)`** using the
   largest-remainder method: compute each share at the currency scale rounded DOWN, then hand the
   leftover minor units one each to the shares with the largest remainders (ties broken by
   position, deterministically). Invariant, property-tested: the parts always sum exactly to the
   input and no part differs from its exact share by one minor unit or more.
2. Add a **`RoundingPolicy` registry**: a closed, named set of policies, each a (scale, mode)
   pair plus a short rationale — initially `LEDGER_POSTING` (currency scale, HALF_EVEN, the
   current `Money` behaviour), `INTEREST_ACCRUAL` (intermediate scale, mode fixed per product
   terms), `FX_RATE` and `FX_AMOUNT`, `FEE`, `TAX_WITHHOLDING` (DOWN to the authority's unit, as
   `WithholdingTaxPolicy` does today) and `DISPLAY`. Services call `money.round(RoundingPolicy.X)`
   instead of spelling `setScale(n, RoundingMode.Y)`.
3. The initial values of each policy are set to **what the code does today**, measured per call
   site — this ADR changes where the rule lives, not any posted amount. Any later change of a
   policy's mode is a customer-visible change and goes through its own PR with a money-path
   review.
4. Enforce with a **ratchet gate** (`money-rounding-inline-ratchet`, advisory first): new
   `RoundingMode.`/`setScale(` in a money-path service's `src/main` outside the registry fails;
   today's 55 sites are baselined and the baseline may only shrink.

## Alternatives considered

- **Keep per-service rounding, document the modes in each service's CLAUDE.md.** Zero migration
  cost. Rejected: the HALF_UP/HALF_EVEN disagreement already exists across money-path services,
  and prose is not a control here (see root CLAUDE.md on `@ConfigProperty`).
- **Change `Money`'s single mode fleet-wide (all HALF_EVEN).** One line. Rejected: it silently
  changes posted amounts in fx and interest, and tax withholding legitimately needs DOWN; one
  mode cannot serve every context.
- **Adopt JSR 354 (Moneta) `MonetaryRounding` and its allocation.** Mature. Rejected for now: a
  second money type beside `Money` across ~50 modules, and it does not by itself give the named,
  auditable per-context policy the gate needs. Remains an option for the registry's internals.

## Consequences

**Positive**
- One place answers "how is interest / FX / a fee rounded", which audit and product teams ask.
- Fan-outs balance to the minor unit by construction instead of by ledger rejection.

**Negative**
- Migration touches money-path services (fx, interest, ledger, transaction, sdd,
  domestic-payment): two approvals and a threat-model check per ADR-0030 for each.
- A registry change becomes a fleet-wide libs release.

**Neutral**
- Enforcement: new gate `money-rounding-inline-ratchet` (advisory, then enforced once the
  money-path baseline is empty). No existing gate covers rounding.

### Delivery check

- `git grep -nE 'RoundingMode\.|setScale\(' -- 'openbank-*/src/main/**/*.kt' ':!openbank-libs*'`
  restricted to `rules.yaml: money_path_services` prints nothing (today: non-empty).
- `git grep -n 'fun allocate' openbank-libs-domain/src/main/kotlin/com/openbank/libs/domain/money/`
  prints one line, with a property test beside it in `MoneyPropertyTest.kt`.
- `gates.yaml` id `money-rounding-inline-ratchet` exists.

## Compliance impact

- PCI DSS: not applicable — no cardholder data is touched.
- DORA:    not applicable — no ICT-risk control changes.
- GDPR:    not applicable — no personal data is processed differently.
- PSD2:    not applicable — no payment-service interface changes; amounts are unchanged by design.
- CNB:     not applicable — the ADR cites no specific CNB requirement; withholding-tax rounding keeps today's behaviour.

## References

- ADR-0025 per-currency ledger balancing; ADR-0046 FX revaluation; ADR-0143 fee posting.
- ADR-0305, ADR-0308 — bitemporal effective-dating (referenced, not redone).
- `openbank-libs-domain/src/main/kotlin/com/openbank/libs/domain/money/Money.kt`
