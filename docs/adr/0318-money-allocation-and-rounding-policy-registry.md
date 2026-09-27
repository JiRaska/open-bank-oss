---
date: 2026-09-26
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [libs, interest, fx, fees-billing]
summary: "libs Money gains a largest-remainder allocate/split and a named rounding-policy registry (MONEY_SCALE, LEDGER_POSTING HALF_UP, interest, FX, fees, tax, DISPLAY); services stop choosing RoundingMode/scale inline, ratchet-gated."
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

## Amendment (2026-09-27, per #11011)

Phase-1 implementation (`openbank-libs-domain`, PR #11011) measured the real call sites
fleet-wide and found decision item 2 below wrong on two points, corrected in place here rather
than superseding the ADR (still `proposed`/`planned`, so amending is allowed per
`docs/adr/SCHEMA.md`):

- **`LEDGER_POSTING` does not round HALF_EVEN.** No posting site on `origin/main` does; every
  booking-normalisation call site (`TransactionService.kt:127/344/353`,
  `SddCollectionDebitConsumer.kt:127`, `SettlementAdapter.kt:84`,
  `FxRevaluationPosting.kt:119-120`) rounds HALF_UP. The HALF_EVEN behaviour the original text
  attributed to `LEDGER_POSTING` is `Money.scale()`'s own normalisation and the entity rehydration
  mappers (`PanacheJournalRepository.kt:377/379`, `PanacheTransactionRepository.kt:211/214`,
  `DelegationGrantEntity.kt:191`, `SpendReservationEntity.kt:87`) — a different call-site class,
  now its own policy, `MONEY_SCALE`.
- **`INTEREST_ACCRUAL` is not a single (scale, mode) pair.** `InterestService.kt` rounds twice —
  a scale-10 HALF_UP daily rate (`:137`), then a scale-6 HALF_UP accrued amount (`:138`) — so the
  registry needs two policies applied in order: `INTEREST_DAILY_RATE` then `INTEREST_ACCRUAL`.

`DISPLAY` is also corrected: the statement renderers (`PdfRenderer.kt:82`, `Camt053Renderer.kt:90`,
`Mt940Renderer.kt:60`) round every currency to a **fixed scale 2** with HALF_UP, not to the
currency's own scale with HALF_EVEN as originally written. The registry records this as it is —
it means JPY (0 decimals) and KWD/BHD (3 decimals) statement amounts render at the wrong scale
today; that defect is tracked separately, not fixed by this ADR.

The `PdfRenderer.kt`/`Camt053Renderer.kt`/`Mt940Renderer.kt`/`TransactionService.kt`/etc. line
numbers above and the full ~30-row measured call-site table are in PR #11011's description
(section "Rounding sites in money-path services + statement renderers", `origin/main` @
2026-09-26T22:59Z); this ADR does not duplicate the whole table.

Two sites remain **out of the registry**, treasury's ACT/360 work, discovered by the same probe
and deliberately not folded into an existing policy:
- `Deal.kt:374` — scale-12 HALF_UP ACT/360 intermediate.
- `Deal.kt:375` and `Postings.kt:147` — fixed scale 2, HALF_UP (same mode as `LEDGER_POSTING`, but
  a fixed scale rather than the currency's own — the same shape as the `DISPLAY` scale-2 issue
  above). These should get their own policy when treasury migrates onto the registry, not be
  merged into `LEDGER_POSTING` or `DISPLAY`.

Item 2 and item 3 below are amended to reflect the policy set as corrected; items 1 and 4 are
unaffected. This amendment changes only what the ADR says the registry contains — it changes no
posted amount and no code (phase 1 is libs-only per PR #11011).

## Decision

We will:

1. Add to `openbank-libs-domain` a **`Money.allocate(ratios)`** and **`Money.split(n)`** using the
   largest-remainder method: compute each share at the currency scale rounded DOWN, then hand the
   leftover minor units one each to the shares with the largest remainders (ties broken by
   position, deterministically). Invariant, property-tested: the parts always sum exactly to the
   input and no part differs from its exact share by one minor unit or more.
2. Add a **`RoundingPolicy` registry**: a closed, named set of policies, each a (scale, mode)
   pair plus a short rationale — `MONEY_SCALE` (currency scale, HALF_EVEN, `Money.scale()` and the
   entity-to-`Money` rehydration mappers), `LEDGER_POSTING` (currency scale, **HALF_UP**, every
   site that normalises an amount for booking), `INTEREST_DAILY_RATE` (scale 10, HALF_UP) followed
   by `INTEREST_ACCRUAL` (scale 6, HALF_UP — accrual rounds twice, so it is two policies applied in
   order), `FX_RATE` and `FX_AMOUNT`, `FEE`, `TAX_WITHHOLDING` (DOWN to the authority's unit, as
   `WithholdingTaxPolicy` does today) and `DISPLAY` (**fixed scale 2**, HALF_UP — what the statement
   renderers do for every currency today, which means JPY and KWD statements render at the wrong
   scale; the registry records this as it is and does not fix it). Services call
   `money.round(RoundingPolicy.X)` instead of spelling `setScale(n, RoundingMode.Y)`.
3. The initial values of each policy are set to **what the code does today**, measured per call
   site (corrected against the measurement in PR #11011 — see Amendment above) — this ADR changes
   where the rule lives, not any posted amount. Any later change of a policy's mode is a
   customer-visible change and goes through its own PR with a money-path review.
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
- PR #11011 — phase-1 implementation and the measured call-site table this amendment is based on.
