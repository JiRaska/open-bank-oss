# Treasury parity fixtures (ADR-0344 D9)

Golden fixtures for the treasury cash-flow ledger. Each `*.json` file is one deal with the
flows an **independent** reference must produce for it. `TreasuryParityReplayTest`
(`src/test/kotlin/.../parity/`) builds every fixture's deal through the service's own draft path,
generates its ledger through `ScheduleService` with the fixture's reference data and observed
fixings, and compares every expected flow field by field — dates exactly, amounts within
`tolerance`, rates exactly. A difference is a named finding per flow. The
`treasury-parity-fixtures` gate (enforced) fails when that replay is missing or disabled.

## Where the numbers come from

`../../parity-oracle/oracle.py` is a pure-Python, `decimal`-only reference written from the deal
contract and published market conventions. It shares no code with `openbank-libs-finance`, the
treasury service or the risk engine — if it did, a fixture would only prove the code agrees with
itself. `generate_fixtures.py` holds the deal terms and writes these files; `test_oracle.py`
proves the oracle against hand-worked values.

```
python3 openbank-treasury-service/src/test/parity-oracle/generate_fixtures.py          # rewrite
python3 openbank-treasury-service/src/test/parity-oracle/generate_fixtures.py --check  # drift?
python3 -m unittest discover -s openbank-treasury-service/src/test/parity-oracle -p 'test_*.py'
```

`.github/scripts/check-treasury-parity-fixtures.py` (gate `treasury-parity-fixtures`) validates the
format, requires at least one fixture per DELIVERED product type, and re-runs the oracle on every
`source: independent-oracle` fixture, so `expected` cannot be hand-edited to match the code.

ADR-0344 delivers products in phases. Only the products whose cash-flow ledger is on main have
committed fixtures here (`DELIVERED_PRODUCTS` in `generate_fixtures.py`, `REQUIRED_PRODUCTS` in the
gate; phase 0 = MM placement and borrowing, CNB deposit facility, CNB lombard, FX spot). The
oracle and `generate_fixtures.py` already describe every ADR-0344 product, and `test_oracle.py`
checks them, so a later phase widens both lists and commits its fixtures in the same PR. The
format and conventions below cover all of them.

## Format

```json
{
  "id": "irs-czk-unadjusted-accrual-001",
  "product": "IRS",
  "source": "independent-oracle | hand-computed | published-example",
  "sourceNote": "what the case exercises and every convention choice it depends on",
  "deal": { "...": "DraftDealRequest v2 subset; legs[] exactly as the contract's LegSpec" },
  "fixings": [ { "index": "PRIBOR_6M", "date": "2026-08-12", "rate": "3.6100" } ],
  "referenceData": { "indices": { "PRIBOR_6M": { "fixingCalendar": "CERTIS", "fixingLagBusinessDays": 2 } } },
  "expected": {
    "flows": [ { "legIndex": 0, "periodIndex": 0, "kind": "INTEREST", "direction": "OUT",
                 "currency": "CZK", "accrualStart": "2026-08-14", "accrualEnd": "2027-08-14",
                 "fixingDate": null, "paymentDate": "2027-08-16", "notional": "100000000.00",
                 "rate": "3.7500", "rateSource": "FIXED", "amount": "3802083.33" } ]
  },
  "tolerance": { "amount": "0.01" }
}
```

- Dates are ISO `YYYY-MM-DD`; every number is a decimal **string** (never a JSON number).
- `amount` and `notional` are HALF_UP to 2 dp and unsigned; `direction` (`IN`/`OUT`) is from the
  booking entity's point of view. `rate` is in percent, exact (padded to 4 dp).
- `amount: null` with `rateSource: MISSING` is a floating period whose fixing is not observed.
- `legIndex: null` is a deal-level flow: the FRA net settlement, a bond's purchase consideration
  and accrued interest. The ledger stores these on pseudo-leg `-1` (a bond settlement also on
  period `-1`); the replay maps `null` to `-1` and nothing else.
- `periodIndex: -1` is a leg's initial notional exchange (it precedes period 0, so it never shares a
  slot with the final exchange of a single-period leg).
- Legs use the service's `LegSpec` wire form (frequencies `ONCE`, `MONTHLY`, `QUARTERLY`,
  `SEMI_ANNUAL`, `ANNUAL`). For CASH, T-bills, bills of exchange, FX forwards and FX swaps the deal
  ticket sends exchange-only legs derived from the deal terms; the oracle derives them itself
  (`derived_legs`) and refuses a fixture whose legs differ, so a leg cannot be hand-edited. FX spot is
  a legacy product: the service derives its legs from the flat fields and its value date as T+2.
- `rateSource` is `FIXED` for every flow whose amount is known at trade (exchanges, principal,
  settlements), so it is never null on a flow with an amount.
- Product-specific extras in `expected`: `spotDate`/`farDate`/`days`/`forwardRate` (FX),
  `pricePer100`/`days` (discount securities), `accruedInterest` (bond), `collateral` (repo),
  `contractRate` (FRA).

## Conventions the oracle fixes

| Topic | Choice |
|---|---|
| Calendars | CERTIS = Czech public holidays (incl. Good Friday, 24–26 Dec); TARGET2 = 1 Jan, Good Friday, Easter Monday, 1 May, 25–26 Dec. A list of calendars is the JOINT calendar (good only if good in all). Easter by the Gregorian computus. |
| Business days | FOLLOWING, MODIFIED_FOLLOWING, PRECEDING, MODIFIED_PRECEDING, UNADJUSTED (ISDA 2006 §4.12). |
| Day counts | ACT/360, ACT/365F, ACT/ACT_ISDA, 30/360 (Bond Basis §4.16(f)), 30E/360 (§4.16(g)). |
| Schedules | `NONE`/`SHORT_FINAL`/`LONG_FINAL` roll forward from start; `SHORT_INITIAL`/`LONG_INITIAL` backward from end. Dates are shifted from the anchor (never iterated); `endOfMonth` pins month-end anchors to month end. A long stub merges the short remainder into its neighbour. `NONE` refuses an irregular schedule. |
| Adjustment flags | `adjustAccrualPeriod=false` accrues between unadjusted dates; `adjustPaymentDate` adjusts the period end and then adds `paymentLagBusinessDays`. |
| Fixing date | Adjusted accrual start minus `fixingLagBusinessDays` on the index's fixing calendar. A stub fixes on the regular index (no interpolation). |
| Cap/floor | `INDEX`: clamp(index) + spread. `INDEX_PLUS_SPREAD`: clamp(index + spread). |
| rateUntilFirstFixing | The contractually agreed **first-period** rate, known at trade: all-in (no spread, no clamp), it applies to period 0 even when an index value is published for period 0's fixing date. No other period uses it; an unobserved later period is `MISSING`. Resolved 2026-10-19 (an earlier oracle used it for every unobserved period before the first observed one; `openbank-libs-finance` already had the market meaning). |
| Reset date | The fixing date counts back from the period start as a business day of the leg's calendar (rolled), also when accrual is unadjusted (ISDA 2006 §6.1). |
| Notional exchanges | Initial exchange opposite to the leg's direction (period `-1`), final exchange in it. An interest-bearing leg repays as `PRINCIPAL`; an exchange-only leg moves its amount as `NOTIONAL_EXCHANGE`. Amortising legs repay through `PRINCIPAL` flows. |
| Amortisation | LINEAR = round(N/n) per period; ANNUITY installment = N·r/(1−(1+r)^−n), r = rate / periods-per-year (the nominal periodic rate a loan contract states, independent of holidays), rounded 2 dp, interest by day count, principal = installment − interest. Residual on the last flow. |
| Single-period floating leg | One fixing for the period whatever the index's reset tenor (an FRA's 3M index on a 3×6 period). |
| FRA | N·(L−K)·t / (1+L·t) paid at the adjusted period start. |
| T-bill / bill of exchange | T-bill price from a discount yield N·(1−d·days/360); bill of exchange from a simple yield N/(1+y·days/360). |
| FX | Spot = T+2 on the joint calendar; far date = spot + tenor (end-end rule), MF; outright by covered interest parity on simple ACT/360 rates, HALF_UP 6 dp. |
| Repo | Cash = nominal · dirty/100 · (1 − haircut); interest ACT/360 on the cash; repo receives cash, reverse repo pays it. |
| Out of scope | Compounding (accrual ≠ payment frequency), CUSTOM amortisation, ACT/ACT ICMA. The oracle raises rather than guesses. |
