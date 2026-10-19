"""Write the golden parity fixtures (ADR-0344 D9) from case definitions + the independent oracle.

    python3 openbank-treasury-service/src/test/parity-oracle/generate_fixtures.py [--check]

`--check` writes nothing and exits 1 if any committed fixture differs from what this script
would write — the same comparison `.github/scripts/check-treasury-parity-fixtures.py` makes.
Every number in `expected` comes from `oracle.compute`; nothing here is typed in by hand except
the deal terms, the observed fixings and the reference data.
"""

from __future__ import annotations

import json
import pathlib
import sys
from decimal import Decimal

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import oracle  # noqa: E402

OUT = pathlib.Path(__file__).resolve().parents[1] / "resources" / "parity"

INDICES = {
    "PRIBOR_3M": {"currency": "CZK", "tenor": "3M", "dayCount": "ACT/360", "fixingCalendar": "CERTIS", "fixingLagBusinessDays": 2},
    "PRIBOR_6M": {"currency": "CZK", "tenor": "6M", "dayCount": "ACT/360", "fixingCalendar": "CERTIS", "fixingLagBusinessDays": 2},
    "EURIBOR_3M": {"currency": "EUR", "tenor": "3M", "dayCount": "ACT/360", "fixingCalendar": "TARGET2", "fixingLagBusinessDays": 2},
    "EURIBOR_6M": {"currency": "EUR", "tenor": "6M", "dayCount": "ACT/360", "fixingCalendar": "TARGET2", "fixingLagBusinessDays": 2},
}


def ref(*codes):
    return {"indices": {c: INDICES[c] for c in codes}}


def leg(pay_receive, ccy, notional, start, end, freq, day_count, rate, *, stub="NONE",
        adjust_payment=True, adjust_accrual=False, lag=0, initial=False, final=False,
        amortisation="BULLET", eom=False):
    out = {
        "payReceive": pay_receive, "currency": ccy, "notional": notional,
        "amortisation": {"type": amortisation, "schedule": []},
        "accrualStart": start, "accrualEnd": end,
        "accrualFrequency": freq, "paymentFrequency": freq, "stub": stub,
        "adjustPaymentDate": adjust_payment, "adjustAccrualPeriod": adjust_accrual,
        "paymentLagBusinessDays": lag, "dayCount": day_count, "rate": rate,
        "initialNotionalExchange": initial, "finalNotionalExchange": final,
    }
    if eom:
        out["endOfMonth"] = True
    return out


def fixed(r):
    return {"type": "FIXED", "rate": r}


def floating(index, spread="0", *, cap=None, floor=None, applies="INDEX", until_first=None, lag=2,
             reset="6M"):
    return {"type": "FLOATING", "index": index, "spread": spread, "fixingLagBusinessDays": lag,
            "resetFrequency": reset, "cap": cap, "floor": floor, "capFloorAppliesTo": applies,
            "rateUntilFirstFixing": until_first}


def deal(product, trade, ccy, cals, legs=None, conv="MODIFIED_FOLLOWING", **extra):
    out = {"product": product, "kind": "ACTUAL", "bookId": "BOOK-PARITY", "tradeDate": trade,
           "currency": ccy, "calendars": cals, "businessDayConvention": conv,
           "rollDirection": "FROM_START"}
    if legs is not None:
        out["legs"] = legs
    out.update(extra)
    return out


def fx(product, trade, pair, side, base_amount, spot, **extra):
    return {"product": product, "kind": "ACTUAL", "bookId": "BOOK-PARITY", "tradeDate": trade,
            "currencyPair": pair, "side": side, "baseAmount": base_amount, "spotRate": spot,
            "calendars": ["TARGET2", "CERTIS"], **extra}


def mm(product, trade, ccy, cals, notional, start, end, rate, pay_receive, day_count="ACT/360",
       conv="MODIFIED_FOLLOWING"):
    return deal(product, trade, ccy, cals, conv=conv, legs=[
        leg(pay_receive, ccy, notional, start, end, "TERM", day_count, fixed(rate),
            adjust_accrual=True, initial=True, final=True)])


CZ = ["CERTIS"]
EU = ["TARGET2"]

CASES = [
    # ---------------- CASH ----------------
    ("cash-czk-in-001", "Incoming CZK cash on a business day; no roll.",
     {"product": "CASH", "kind": "ACTUAL", "bookId": "BOOK-PARITY", "tradeDate": "2026-11-02",
      "currency": "CZK", "amount": "2500000.00", "direction": "IN", "valueDate": "2026-11-02",
      "calendars": CZ, "businessDayConvention": "FOLLOWING"}, [], {}),
    ("cash-czk-out-holiday-002", "Value date 2026-11-17 is a CZ holiday (Tuesday); FOLLOWING rolls to 2026-11-18.",
     {"product": "CASH", "kind": "ACTUAL", "bookId": "BOOK-PARITY", "tradeDate": "2026-11-16",
      "currency": "CZK", "amount": "1000000.00", "direction": "OUT", "valueDate": "2026-11-17",
      "calendars": CZ, "businessDayConvention": "FOLLOWING"}, [], {}),
    # ---------------- MM ----------------
    ("mm-placement-czk-holiday-001",
     "Money-market convention: accrual follows the ADJUSTED maturity. 2026-10-28 is a CZ holiday; MF rolls to 2026-10-29, so 29 days ACT/360.",
     mm("MM_PLACEMENT", "2026-09-28", "CZK", CZ, "50000000.00", "2026-09-30", "2026-10-28", "3.5000", "RECEIVE"), [], {}),
    ("mm-placement-czk-christmas-002",
     "Maturity 2026-12-24 (CZ holiday); 25/26 holidays and the weekend push MF to Monday 2026-12-28.",
     mm("MM_PLACEMENT", "2026-11-20", "CZK", CZ, "20000000.00", "2026-11-24", "2026-12-24", "3.4500", "RECEIVE"), [], {}),
    ("mm-placement-eur-easter-003",
     "EUR placement maturing on Easter Monday 2027-03-29 (TARGET2 closed); MF rolls to 2027-03-30.",
     mm("MM_PLACEMENT", "2027-02-25", "EUR", EU, "10000000.00", "2027-03-01", "2027-03-29", "2.1500", "RECEIVE"), [], {}),
    ("mm-borrowing-czk-001", "Plain 3M CZK borrowing; no holidays in the way.",
     mm("MM_BORROWING", "2026-11-02", "CZK", CZ, "30000000.00", "2026-11-04", "2027-02-04", "3.6000", "PAY"), [], {}),
    ("mm-borrowing-czk-month-end-mf-002",
     "Maturity 2027-07-31 is a Saturday; MODIFIED_FOLLOWING would cross into August, so it goes back to Friday 2027-07-30.",
     mm("MM_BORROWING", "2027-06-28", "CZK", CZ, "15000000.00", "2027-06-30", "2027-07-31", "3.4000", "PAY"), [], {}),
    # ---------------- CNB standing facilities ----------------
    ("cnb-deposit-facility-weekend-001",
     "Overnight deposit Friday to Monday: 3 calendar days at ACT/360 (overnight standing facility).",
     mm("CNB_DEPOSIT_FACILITY", "2026-11-06", "CZK", CZ, "500000000.00", "2026-11-06", "2026-11-09", "2.5000", "RECEIVE", conv="FOLLOWING"), [], {}),
    ("cnb-deposit-facility-holiday-002",
     "Overnight deposit on 2026-10-27 whose next day is a CZ holiday: it matures 2026-10-29 (FOLLOWING), 2 days.",
     mm("CNB_DEPOSIT_FACILITY", "2026-10-27", "CZK", CZ, "250000000.00", "2026-10-27", "2026-10-28", "2.5000", "RECEIVE", conv="FOLLOWING"), [], {}),
    ("cnb-lombard-overnight-001", "Overnight lombard borrowing, 1 day ACT/360.",
     mm("CNB_LOMBARD", "2026-11-10", "CZK", CZ, "100000000.00", "2026-11-10", "2026-11-11", "4.5000", "PAY", conv="FOLLOWING"), [], {}),
    # ---------------- FX ----------------
    ("fx-spot-eurczk-001", "EUR/CZK spot T+2 on the joint TARGET2+CERTIS calendar; CZK amount rounded HALF_UP to 2 dp.",
     fx("FX_SPOT", "2026-11-02", "EUR/CZK", "BUY", "1000000.00", "24.3850"), [], {}),
    ("fx-spot-eurczk-joint-calendar-002",
     "Trade on Friday 2026-11-13; Tuesday 2026-11-17 is a CZ-only holiday (TARGET2 open), so T+2 on the JOINT calendar is Wednesday 2026-11-18.",
     fx("FX_SPOT", "2026-11-13", "EUR/CZK", "SELL", "250000.00", "24.4125"), [], {}),
    ("fx-spot-eurczk-easter-003",
     "Trade on Thursday 2027-03-25: Good Friday and Easter Monday are closed on both calendars, so spot is Wednesday 2027-03-31.",
     fx("FX_SPOT", "2027-03-25", "EUR/CZK", "BUY", "500000.00", "24.2000"), [], {}),
    ("fx-forward-eurczk-3m-001",
     "Outright = spot * (1 + r_CZK*d/360) / (1 + r_EUR*d/360), both simple ACT/360, rounded HALF_UP to 6 dp; far date = spot + 3M, MF on joint calendar.",
     fx("FX_FORWARD", "2026-11-02", "EUR/CZK", "BUY", "2000000.00", "24.3850", tenor="3M", baseRate="2.0500", quoteRate="3.5000"), [], {}),
    ("fx-forward-eurczk-12m-002", "12M outright, SELL EUR; same covered-interest-parity convention.",
     fx("FX_FORWARD", "2026-11-02", "EUR/CZK", "SELL", "750000.00", "24.3850", tenor="12M", baseRate="2.1000", quoteRate="3.3000"), [], {}),
    ("fx-swap-eurczk-1m-001",
     "Buy/sell EUR swap: near leg at spot on the spot date, far leg at the CIP outright 1M later (MF on joint calendar).",
     fx("FX_SWAP", "2026-11-02", "EUR/CZK", "BUY", "5000000.00", "24.3850", tenor="1M", baseRate="2.0000", quoteRate="3.5500"), [], {}),
    ("fx-swap-eurczk-3m-month-end-002",
     "Spot date is a month end (2026-11-30); end-end rule puts the far date on the month end 2027-02-26 after MF.",
     fx("FX_SWAP", "2026-11-26", "EUR/CZK", "SELL", "1000000.00", "24.4000", tenor="3M", baseRate="2.0000", quoteRate="3.5000"), [], {}),
    # ---------------- Discount securities ----------------
    ("tbill-czk-3m-001",
     "T-bill price from a DISCOUNT yield: price = N * (1 - d * days/360).",
     {"product": "TREASURY_BILL", "kind": "ACTUAL", "bookId": "BOOK-PARITY", "tradeDate": "2026-11-02",
      "securityIsin": "TEST0000TB01", "currency": "CZK", "nominal": "10000000.00", "yield": "3.2000",
      "valueDate": "2026-11-04", "maturityDate": "2027-02-04", "basis": 360, "calendars": CZ,
      "businessDayConvention": "FOLLOWING", "side": "BUY"}, [], {}),
    ("tbill-czk-6m-holiday-002",
     "Maturity 2027-05-08 is a Saturday and a CZ holiday; FOLLOWING pays Monday 2027-05-10.",
     {"product": "TREASURY_BILL", "kind": "ACTUAL", "bookId": "BOOK-PARITY", "tradeDate": "2026-11-04",
      "securityIsin": "TEST0000TB02", "currency": "CZK", "nominal": "5000000.00", "yield": "3.1500",
      "valueDate": "2026-11-06", "maturityDate": "2027-05-08", "basis": 360, "calendars": CZ,
      "businessDayConvention": "FOLLOWING", "side": "BUY"}, [], {}),
    ("bill-of-exchange-czk-001",
     "Bill of exchange priced on a simple money-market YIELD (not a discount): price = N / (1 + y*days/360).",
     {"product": "BILL_OF_EXCHANGE", "kind": "ACTUAL", "bookId": "BOOK-PARITY", "tradeDate": "2026-11-02",
      "securityIsin": "TEST0000BE01", "currency": "CZK", "nominal": "3000000.00", "yield": "4.2500",
      "valueDate": "2026-11-02", "maturityDate": "2027-05-03", "basis": 360, "calendars": CZ,
      "businessDayConvention": "FOLLOWING", "side": "BUY"}, [], {}),
    # ---------------- Bonds ----------------
    ("bond-czk-short-first-coupon-001",
     "Annual 30E/360 bond generated BACKWARD from maturity with a short first coupon (2026-11-16 -> 2027-03-15); bought at issue, so no accrued interest; coupon payments adjusted F on CERTIS.",
     deal("BOND", "2026-11-12", "CZK", CZ, conv="FOLLOWING", securityIsin="TEST0000BD01",
          nominal="10000000.00", cleanPrice="99.8500", valueDate="2026-11-16",
          legs=[leg("RECEIVE", "CZK", "10000000.00", "2026-11-16", "2030-03-15", "12M", "30E/360",
                    fixed("4.0000"), stub="SHORT_INITIAL", final=True)]), [], {}),
    ("bond-eur-secondary-accrued-002",
     "Secondary purchase mid-coupon: accrued = N * c * ACT/ACT ISDA(last coupon, value date); only flows after the value date are kept. ACT/ACT ISDA (not ICMA) is chosen deliberately, so coupons across 2028 are uneven; ACT/ACT ICMA is outside the oracle's scope.",
     deal("BOND", "2026-11-18", "EUR", EU, conv="FOLLOWING", securityIsin="TEST0000BD02",
          nominal="2000000.00", cleanPrice="101.2500", valueDate="2026-11-20",
          legs=[leg("RECEIVE", "EUR", "2000000.00", "2025-06-15", "2029-06-15", "12M", "ACT/ACT_ISDA",
                    fixed("3.2500"), final=True)]), [], {}),
    # ---------------- Loans ----------------
    ("loan-czk-linear-001",
     "LINEAR amortisation: principal = round(N/n) each quarter, residual on the last flow; interest on the opening balance ACT/360.",
     deal("LOAN", "2026-11-02", "CZK", CZ,
          legs=[leg("RECEIVE", "CZK", "10000000.00", "2026-11-05", "2027-11-05", "3M", "ACT/360",
                    fixed("5.2500"), adjust_accrual=True, initial=True, amortisation="LINEAR")]), [], {}),
    ("loan-czk-annuity-002",
     "ANNUITY: installment A = N*r/(1-(1+r)^-n) with r = rate/periods-per-year, rounded 2 dp; interest by day count on the balance; principal = A - interest; residual on the last flow.",
     deal("LOAN", "2026-11-02", "CZK", CZ,
          legs=[leg("RECEIVE", "CZK", "1000000.00", "2026-11-05", "2027-11-05", "1M", "30E/360",
                    fixed("6.0000"), adjust_accrual=True, initial=True, amortisation="ANNUITY")]), [], {}),
    ("loan-eur-actact-leap-003",
     "Bullet loan accruing across the 2028 leap year at ACT/ACT ISDA; semi-annual.",
     deal("LOAN", "2027-06-28", "EUR", EU,
          legs=[leg("RECEIVE", "EUR", "4000000.00", "2027-06-30", "2028-12-30", "6M", "ACT/ACT_ISDA",
                    fixed("3.0000"), adjust_accrual=True, initial=True, final=True)]), [], {}),
    ("loan-czk-act365f-004", "Bullet loan, ACT/365F, quarterly.",
     deal("LOAN", "2026-11-02", "CZK", CZ,
          legs=[leg("RECEIVE", "CZK", "7500000.00", "2026-11-04", "2027-11-04", "3M", "ACT/365F",
                    fixed("4.7500"), adjust_accrual=True, initial=True, final=True)]), [], {}),
    ("loan-czk-float-cap-floor-index-005",
     "Floating PRIBOR 3M + 150bp with floor 3.00 and cap 4.00 applied to the INDEX (spread added after clamping). Period 0 has no observed fixing and uses rateUntilFirstFixing (an all-in rate, no spread, no cap/floor). Last period has no fixing yet -> MISSING, amount null.",
     deal("LOAN", "2026-11-02", "CZK", CZ,
          legs=[leg("RECEIVE", "CZK", "8000000.00", "2026-11-05", "2027-11-05", "3M", "ACT/360",
                    floating("PRIBOR_3M", "1.5000", cap="4.0000", floor="3.0000", applies="INDEX",
                             until_first="5.1000", reset="3M"),
                    adjust_accrual=True, initial=True, final=True)]),
     [{"index": "PRIBOR_3M", "date": "2027-02-03", "rate": "2.8000"},
      {"index": "PRIBOR_3M", "date": "2027-05-03", "rate": "4.3500"}],
     ref("PRIBOR_3M")),
    ("loan-czk-float-cap-floor-all-in-006",
     "Same deal, same cap 4.00 / floor 3.00, but applied to INDEX_PLUS_SPREAD (clamp the all-in rate): 2.80+1.50=4.30 caps to 4.00 and 4.35+1.50 caps to 4.00, where fixture 005 gives 4.50 and 5.50. rateUntilFirstFixing is all-in and is not clamped.",
     deal("LOAN", "2026-11-02", "CZK", CZ,
          legs=[leg("RECEIVE", "CZK", "8000000.00", "2026-11-05", "2027-11-05", "3M", "ACT/360",
                    floating("PRIBOR_3M", "1.5000", cap="4.0000", floor="3.0000", applies="INDEX_PLUS_SPREAD",
                             until_first="5.1000", reset="3M"),
                    adjust_accrual=True, initial=True, final=True)]),
     [{"index": "PRIBOR_3M", "date": "2027-02-03", "rate": "2.8000"},
      {"index": "PRIBOR_3M", "date": "2027-05-03", "rate": "4.3500"}],
     ref("PRIBOR_3M")),
    # ---------------- FRA ----------------
    ("fra-czk-3x6-receive-001",
     "3x6 FRA, bought (receive floating). Settlement at period start = N*(L-K)*t/(1+L*t), t ACT/360, fixing 2 CERTIS days before start.",
     deal("FRA", "2026-11-02", "CZK", CZ,
          legs=[leg("RECEIVE", "CZK", "100000000.00", "2027-02-04", "2027-05-04", "TERM", "ACT/360",
                    floating("PRIBOR_3M", reset="3M"), adjust_accrual=True),
                leg("PAY", "CZK", "100000000.00", "2027-02-04", "2027-05-04", "TERM", "ACT/360",
                    fixed("3.4000"), adjust_accrual=True)]),
     [{"index": "PRIBOR_3M", "date": "2027-02-02", "rate": "3.6200"}], ref("PRIBOR_3M")),
    ("fra-eur-3x6-pay-002", "3x6 EUR FRA where L < K: the buyer pays the discounted difference.",
     deal("FRA", "2026-11-02", "EUR", EU,
          legs=[leg("RECEIVE", "EUR", "25000000.00", "2027-02-04", "2027-05-04", "TERM", "ACT/360",
                    floating("EURIBOR_3M", reset="3M"), adjust_accrual=True),
                leg("PAY", "EUR", "25000000.00", "2027-02-04", "2027-05-04", "TERM", "ACT/360",
                    fixed("2.2500"), adjust_accrual=True)]),
     [{"index": "EURIBOR_3M", "date": "2027-02-02", "rate": "2.0100"}], ref("EURIBOR_3M")),
    # ---------------- IRS ----------------
    ("irs-czk-unadjusted-accrual-001",
     "Payer CZK IRS, fixed annual ACT/360 vs PRIBOR 6M ACT/360. Accrual UNADJUSTED, payment ADJUSTED (MF, CERTIS): the 2027-02-14 boundary is a Sunday (and 2027-08-14 a Saturday), so interest accrues to the weekend date and is paid on the Monday.",
     deal("IRS", "2026-08-12", "CZK", CZ,
          legs=[leg("PAY", "CZK", "100000000.00", "2026-08-14", "2028-08-14", "12M", "ACT/360", fixed("3.7500")),
                leg("RECEIVE", "CZK", "100000000.00", "2026-08-14", "2028-08-14", "6M", "ACT/360",
                    floating("PRIBOR_6M"))]),
     [{"index": "PRIBOR_6M", "date": "2026-08-12", "rate": "3.6100"},
      {"index": "PRIBOR_6M", "date": "2027-02-11", "rate": "3.5400"}],
     ref("PRIBOR_6M")),
    ("irs-czk-adjusted-accrual-002",
     "Same deal with adjustAccrualPeriod = true: the weekend boundaries move to Monday, so periods lengthen/shorten by one day and the amounts differ from fixture 001.",
     deal("IRS", "2026-08-12", "CZK", CZ,
          legs=[leg("PAY", "CZK", "100000000.00", "2026-08-14", "2028-08-14", "12M", "ACT/360", fixed("3.7500"),
                    adjust_accrual=True),
                leg("RECEIVE", "CZK", "100000000.00", "2026-08-14", "2028-08-14", "6M", "ACT/360",
                    floating("PRIBOR_6M"), adjust_accrual=True)]),
     [{"index": "PRIBOR_6M", "date": "2026-08-12", "rate": "3.6100"},
      {"index": "PRIBOR_6M", "date": "2027-02-11", "rate": "3.5400"}],
     ref("PRIBOR_6M")),
    ("irs-eur-backward-short-stub-003",
     "Receiver EUR IRS generated BACKWARD from maturity, short INITIAL stub 2026-11-10 -> 2027-03-15; fixed annual 30/360 Bond Basis vs EURIBOR 6M; the stub fixes on the regular 6M index (no interpolation); payment lag 2 TARGET2 days.",
     deal("IRS", "2026-11-06", "EUR", EU, rollDirection="FROM_END",
          legs=[leg("RECEIVE", "EUR", "50000000.00", "2026-11-10", "2029-03-15", "12M", "30/360", fixed("2.4000"),
                    stub="SHORT_INITIAL", lag=2),
                leg("PAY", "EUR", "50000000.00", "2026-11-10", "2029-03-15", "6M", "ACT/360",
                    floating("EURIBOR_6M", "0.1000"), stub="SHORT_INITIAL", lag=2)]),
     [{"index": "EURIBOR_6M", "date": "2026-11-06", "rate": "2.1500"}],
     ref("EURIBOR_6M")),
    ("irs-czk-long-final-stub-eom-004",
     "Forward generation from a month-end start with the end-of-month rule and a LONG final stub (the short remainder merges into the last period); 30E/360 fixed semi-annual vs PRIBOR 6M.",
     deal("IRS", "2026-08-27", "CZK", CZ,
          legs=[leg("PAY", "CZK", "20000000.00", "2026-08-31", "2028-10-15", "6M", "30E/360", fixed("3.6000"),
                    stub="LONG_FINAL", eom=True),
                leg("RECEIVE", "CZK", "20000000.00", "2026-08-31", "2028-10-15", "6M", "ACT/360",
                    floating("PRIBOR_6M"), stub="LONG_FINAL", eom=True)]),
     [{"index": "PRIBOR_6M", "date": "2026-08-27", "rate": "3.6300"}],
     ref("PRIBOR_6M")),
    # ---------------- Repo ----------------
    ("repo-czk-1w-001",
     "Repo: we deliver collateral and receive cash = nominal * dirty/100 * (1 - haircut); repurchase = cash + cash*r*days/360.",
     None, [], {}),
    ("reverse-repo-czk-2w-001",
     "Reverse repo: we receive collateral and pay out the haircut-adjusted cash; interest ACT/360 on the cash amount.",
     None, [], {}),
]


def _repo_deal(product, trade, start, end, rate, nominal, dirty, haircut, isin):
    col = {"securityIsin": isin, "nominal": nominal, "haircutPercent": haircut, "dirtyPricePercent": dirty}
    cash = str(oracle.repo_cash_amount(col))
    side = "PAY" if product == "REPO" else "RECEIVE"
    assert Decimal(cash) > 0
    return deal(product, trade, "CZK", CZ, collateral=col,
                legs=[leg(side, "CZK", cash, start, end, "TERM", "ACT/360", fixed(rate),
                          adjust_accrual=True, initial=True, final=True)])


REPOS = {
    "repo-czk-1w-001": _repo_deal("REPO", "2026-11-02", "2026-11-03", "2026-11-10", "3.5000",
                                  "100000000.00", "98.7500", "2.0000", "TEST0000GB01"),
    "reverse-repo-czk-2w-001": _repo_deal("REVERSE_REPO", "2026-11-02", "2026-11-04", "2026-11-18",
                                          "3.4500", "50000000.00", "101.1250", "3.0000", "TEST0000GB02"),
}


def product_of(fixture):
    return fixture["deal"]["product"]


def build():
    fixtures = []
    for fid, note, dl, fixings, refdata in CASES:
        dl = dl if dl is not None else REPOS[fid]
        fx_ = {"id": fid, "product": dl["product"], "source": "independent-oracle", "sourceNote": note,
               "deal": dl, "fixings": fixings, "referenceData": refdata}
        fx_["expected"] = oracle.compute(fx_)
        fx_["tolerance"] = {"amount": "0.01"}
        fixtures.append(fx_)
    return fixtures


def render(fixture) -> str:
    return json.dumps(fixture, indent=2, ensure_ascii=False) + "\n"


def main(argv):
    check = "--check" in argv
    drift = 0
    for fx_ in build():
        path = OUT / f"{fx_['id']}.json"
        text = render(fx_)
        if check:
            if not path.exists() or path.read_text(encoding="utf-8") != text:
                print(f"DRIFT {path}")
                drift += 1
        else:
            OUT.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
    if check:
        print(f"{drift} fixture(s) differ")
        return 1 if drift else 0
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
