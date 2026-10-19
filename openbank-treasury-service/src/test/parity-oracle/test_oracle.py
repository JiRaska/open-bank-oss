"""Hand-checked tests that prove the ORACLE itself (stdlib unittest).

    python3 -m unittest discover -s openbank-treasury-service/src/test/parity-oracle -p 'test_*.py'

Every expected value below was worked out by hand from the convention's definition, not by
running the oracle — the working is in the comment beside it.
"""

from __future__ import annotations

import json
import pathlib
import sys
import unittest
from decimal import Decimal

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import generate_fixtures  # noqa: E402
import oracle as o  # noqa: E402
from oracle import d  # noqa: E402

CZ = ["CERTIS"]
EU = ["TARGET2"]
FIXTURES = HERE.parent / "resources" / "parity"


class Easter(unittest.TestCase):
    def test_known_easter_sundays(self):
        # Published Gregorian Easter dates.
        for year, day in [(2024, "2024-03-31"), (2025, "2025-04-20"), (2026, "2026-04-05"),
                          (2027, "2027-03-28"), (2028, "2028-04-16"), (2038, "2038-04-25"),
                          (2285, "2285-03-22")]:
            self.assertEqual(o.easter_sunday(year), d(day), year)


class Calendars(unittest.TestCase):
    def test_christmas_block_cz(self):
        for day in ["2026-12-24", "2026-12-25", "2026-12-26"]:
            self.assertFalse(o.is_business_day(d(day), CZ), day)
        # 24th is open on TARGET2 (Thursday), 25/26 closed.
        self.assertTrue(o.is_business_day(d("2026-12-24"), EU))
        self.assertFalse(o.is_business_day(d("2026-12-25"), EU))

    def test_easter_monday_and_good_friday(self):
        # 2027: Good Friday 26 Mar, Easter Monday 29 Mar — closed on BOTH calendars
        # (Good Friday has been a Czech public holiday since 2016).
        for cals in (CZ, EU):
            self.assertFalse(o.is_business_day(d("2027-03-26"), cals))
            self.assertFalse(o.is_business_day(d("2027-03-29"), cals))
            self.assertTrue(o.is_business_day(d("2027-03-30"), cals))

    def test_cz_only_holiday_is_open_on_target2(self):
        # 6 July (Jan Hus Day) 2027 is a Tuesday: CERTIS closed, TARGET2 open, joint closed.
        self.assertFalse(o.is_business_day(d("2027-07-06"), CZ))
        self.assertTrue(o.is_business_day(d("2027-07-06"), EU))
        self.assertFalse(o.is_business_day(d("2027-07-06"), CZ + EU))

    def test_target2_full_year_count(self):
        # 2027 TARGET2 closing days: 1 Jan (Fri), 26 Mar, 29 Mar, 1 May (Sat), 25 Dec (Sat),
        # 26 Dec (Sun) -> weekday closures 3; 261 weekdays in 2027 -> 258 business days.
        days = sum(1 for n in range(365) if o.is_business_day(o.d("2027-01-01") + o._dt.timedelta(n), EU))
        self.assertEqual(days, 258)

    def test_certis_full_year_count(self):
        # 2026 Czech holidays on weekdays: 1 Jan Thu, 3 Apr GF, 6 Apr EM, 1 May Fri, 8 May Fri,
        # 6 Jul Mon, 28 Sep Mon, 28 Oct Wed, 17 Nov Tue, 24 Thu, 25 Fri Dec -> 11
        # (5 Jul Sun, 26 Dec Sat). 2026 has 261 weekdays -> 250 business days.
        days = sum(1 for n in range(365) if o.is_business_day(o.d("2026-01-01") + o._dt.timedelta(n), CZ))
        self.assertEqual(days, 250)


class Conventions(unittest.TestCase):
    def test_modified_following_at_month_end(self):
        # Sat 31 Jul 2027: F -> Mon 2 Aug (new month) so MF -> Fri 30 Jul.
        self.assertEqual(o.adjust(d("2027-07-31"), "FOLLOWING", CZ), d("2027-08-02"))
        self.assertEqual(o.adjust(d("2027-07-31"), "MODIFIED_FOLLOWING", CZ), d("2027-07-30"))

    def test_modified_preceding_at_month_start(self):
        # Sat 1 May 2027 (also a holiday): P -> Fri 30 Apr (prev month) so MP -> Mon 3 May.
        self.assertEqual(o.adjust(d("2027-05-01"), "PRECEDING", CZ), d("2027-04-30"))
        self.assertEqual(o.adjust(d("2027-05-01"), "MODIFIED_PRECEDING", CZ), d("2027-05-03"))

    def test_christmas_roll(self):
        # Thu 24 Dec 2026 -> F skips 24/25 holidays + weekend -> Mon 28; P -> Wed 23.
        self.assertEqual(o.adjust(d("2026-12-24"), "FOLLOWING", CZ), d("2026-12-28"))
        self.assertEqual(o.adjust(d("2026-12-24"), "PRECEDING", CZ), d("2026-12-23"))
        self.assertEqual(o.adjust(d("2026-12-26"), "UNADJUSTED", CZ), d("2026-12-26"))

    def test_easter_monday_roll(self):
        self.assertEqual(o.adjust(d("2027-03-29"), "MODIFIED_FOLLOWING", EU), d("2027-03-30"))
        self.assertEqual(o.adjust(d("2027-03-29"), "PRECEDING", EU), d("2027-03-25"))

    def test_business_day_arithmetic(self):
        # Fixing 2 days before Tue 10 Nov 2026 -> Fri 6 Nov.
        self.assertEqual(o.add_business_days(d("2026-11-10"), -2, EU), d("2026-11-06"))
        # FX spot from Fri 13 Nov 2026 on joint calendar skips CZ-only Tue 17 Nov -> Wed 18.
        self.assertEqual(o.add_business_days(d("2026-11-13"), 2, CZ + EU), d("2026-11-18"))
        self.assertEqual(o.add_business_days(d("2026-11-13"), 2, EU), d("2026-11-17"))


class DayCounts(unittest.TestCase):
    def test_act_act_isda_across_leap_year(self):
        # 2027-10-01 -> 2028-04-01: 92 days in 2027 /365 + 91 days in 2028 /366.
        expected = Decimal(92) / Decimal(365) + Decimal(91) / Decimal(366)
        self.assertEqual(o.year_fraction(d("2027-10-01"), d("2028-04-01"), "ACT/ACT_ISDA"), expected)
        # Whole leap year is exactly 1.
        self.assertEqual(o.year_fraction(d("2028-01-01"), d("2029-01-01"), "ACT/ACT_ISDA"), Decimal(1))

    def test_act_360_and_365f(self):
        self.assertEqual(o.year_fraction(d("2026-01-01"), d("2026-07-01"), "ACT/360"), Decimal(181) / Decimal(360))
        self.assertEqual(o.year_fraction(d("2028-01-01"), d("2029-01-01"), "ACT/365F"), Decimal(366) / Decimal(365))

    def test_30_360_bond_basis_vs_30e(self):
        # 30 Jan -> 31 Mar: Bond Basis D1=30 so D2 31->30 => 60; 30E also 60.
        self.assertEqual(o.year_fraction(d("2027-01-30"), d("2027-03-31"), "30/360"), Decimal(60) / Decimal(360))
        # 15 Jan -> 31 Mar: Bond Basis keeps D2=31 (D1 < 30) => 76; 30E caps D2 => 75.
        self.assertEqual(o.year_fraction(d("2027-01-15"), d("2027-03-31"), "30/360"), Decimal(76) / Decimal(360))
        self.assertEqual(o.year_fraction(d("2027-01-15"), d("2027-03-31"), "30E/360"), Decimal(75) / Decimal(360))
        # Feb end is not adjusted by either (no EOM-Feb rule in these two): 28 Feb -> 31 Mar.
        self.assertEqual(o.year_fraction(d("2027-02-28"), d("2027-03-31"), "30/360"), Decimal(33) / Decimal(360))
        self.assertEqual(o.year_fraction(d("2027-02-28"), d("2027-03-31"), "30E/360"), Decimal(32) / Decimal(360))


class Schedules(unittest.TestCase):
    def test_backward_short_initial_stub(self):
        s = o.unadjusted_schedule(d("2026-11-10"), d("2028-03-15"), "6M", "SHORT_INITIAL")
        self.assertEqual(s, [d("2026-11-10"), d("2027-03-15"), d("2027-09-15"), d("2028-03-15")])

    def test_backward_long_initial_stub(self):
        s = o.unadjusted_schedule(d("2026-11-10"), d("2028-03-15"), "6M", "LONG_INITIAL")
        self.assertEqual(s, [d("2026-11-10"), d("2027-09-15"), d("2028-03-15")])

    def test_forward_short_and_long_final(self):
        s = o.unadjusted_schedule(d("2026-01-15"), d("2027-03-01"), "6M", "SHORT_FINAL")
        self.assertEqual(s, [d("2026-01-15"), d("2026-07-15"), d("2027-01-15"), d("2027-03-01")])
        s = o.unadjusted_schedule(d("2026-01-15"), d("2027-03-01"), "6M", "LONG_FINAL")
        self.assertEqual(s, [d("2026-01-15"), d("2026-07-15"), d("2027-03-01")])

    def test_stub_none_refuses_irregular(self):
        with self.assertRaises(o.OracleError):
            o.unadjusted_schedule(d("2026-01-15"), d("2027-03-01"), "6M", "NONE")

    def test_end_of_month_rule(self):
        # 31 Aug anchor with EOM: Feb 28, Aug 31, Feb 29 (leap). Without EOM: day 31 clamps to
        # 28 then RETURNS to 31 because shifts are taken from the anchor, not iterated.
        with_eom = o.unadjusted_schedule(d("2026-08-31"), d("2028-02-29"), "6M", "NONE", eom=True)
        self.assertEqual(with_eom, [d("2026-08-31"), d("2027-02-28"), d("2027-08-31"), d("2028-02-29")])
        self.assertEqual(o.add_months(d("2026-08-30"), 6, eom=True), d("2027-02-28"))
        self.assertEqual(o.add_months(d("2026-08-30"), 12, eom=True), d("2027-08-30"))


class Products(unittest.TestCase):
    def fixture(self, fid):
        return json.loads((FIXTURES / f"{fid}.json").read_text(encoding="utf-8"))

    def test_mm_deposit_across_cz_holiday(self):
        # 50,000,000 * 3.5% * 29/360 = 140,972.2222 -> 140,972.22, paid 29 Oct.
        flows = self.fixture("mm-placement-czk-holiday-001")["expected"]["flows"]
        interest = next(f for f in flows if f["kind"] == "INTEREST")
        self.assertEqual((interest["paymentDate"], interest["amount"]), ("2026-10-29", "140972.22"))

    def test_tbill_price(self):
        # 10,000,000 * (1 - 0.032 * 92/360) = 10,000,000 - 81,777.78 = 9,918,222.22.
        flows = self.fixture("tbill-czk-3m-001")["expected"]["flows"]
        self.assertEqual(flows[0]["amount"], "9918222.22")

    def test_fra_settlement(self):
        # 1e8 * 0.0022 * 89/360 / (1 + 0.0362 * 89/360) = 54,388.89 / 1.0089494 = 53,906.46.
        flow = self.fixture("fra-czk-3x6-receive-001")["expected"]["flows"][0]
        self.assertEqual((flow["amount"], flow["direction"], flow["paymentDate"]), ("53906.46", "IN", "2027-02-04"))

    def test_fx_forward_points(self):
        # 24.385 * (1 + 0.035*92/360) / (1 + 0.0205*92/360) = 24.385 * 1.0089444/1.0052389 = 24.474889.
        self.assertEqual(o.fx_forward_rate("24.3850", "2.0500", "3.5000", 92), Decimal("24.474889"))

    def test_unadjusted_accrual_adjusted_payment(self):
        flows = self.fixture("irs-czk-unadjusted-accrual-001")["expected"]["flows"]
        p0 = next(f for f in flows if f["legIndex"] == 1 and f["periodIndex"] == 0)
        # Accrue to Sunday 14 Feb, pay Monday 15 Feb: 184 days * 3.61% / 360 * 1e8 = 1,845,111.11.
        self.assertEqual((p0["accrualEnd"], p0["paymentDate"], p0["amount"]), ("2027-02-14", "2027-02-15", "1845111.11"))
        adj = self.fixture("irs-czk-adjusted-accrual-002")["expected"]["flows"]
        q0 = next(f for f in adj if f["legIndex"] == 1 and f["periodIndex"] == 0)
        # Adjusted: 185 days -> 1,855,138.89.
        self.assertEqual((q0["accrualEnd"], q0["amount"]), ("2027-02-15", "1855138.89"))

    def test_cap_floor_modes(self):
        idx = self.fixture("loan-czk-float-cap-floor-index-005")["expected"]["flows"]
        allin = self.fixture("loan-czk-float-cap-floor-all-in-006")["expected"]["flows"]
        rates = [f["rate"] for f in idx if f["kind"] == "INTEREST"]
        self.assertEqual(rates, ["5.1000", "4.5000", "5.5000", None])  # floor 3 +1.5 ; cap 4 +1.5
        rates = [f["rate"] for f in allin if f["kind"] == "INTEREST"]
        self.assertEqual(rates, ["5.1000", "4.0000", "4.0000", None])
        self.assertEqual(idx[1]["rateSource"], "RATE_UNTIL_FIRST_FIXING")

    def test_linear_residual(self):
        # 10,000,000 / 3 = 3,333,333.33 twice, residual 3,333,333.34 last.
        flows = o.leg_flows({"calendars": CZ}, 0, {
            "payReceive": "RECEIVE", "currency": "CZK", "notional": "10000000.00",
            "amortisation": {"type": "LINEAR"}, "accrualStart": "2027-01-04", "accrualEnd": "2027-04-04",
            "accrualFrequency": "1M", "paymentFrequency": "1M", "stub": "NONE", "dayCount": "ACT/360",
            "rate": {"type": "FIXED", "rate": "0"}}, [], {})
        principal = [f["amount"] for f in flows if f["kind"] == "PRINCIPAL"]
        self.assertEqual(principal, ["3333333.33", "3333333.33", "3333333.34"])

    def test_rounding_half_up(self):
        self.assertEqual(o.fmt_amount(Decimal("0.005")), "0.01")
        self.assertEqual(o.fmt_amount(Decimal("2.675")), "2.68")  # would be 2.67 in binary float

    def test_floats_rejected(self):
        with self.assertRaises(o.OracleError):
            o.dec(0.1)


class FixtureSet(unittest.TestCase):
    def test_committed_fixtures_match_generator(self):
        for fx in generate_fixtures.build():
            path = FIXTURES / f"{fx['id']}.json"
            self.assertEqual(path.read_text(encoding="utf-8"), generate_fixtures.render(fx), fx["id"])

    def test_every_product_has_a_fixture(self):
        products = {fx["deal"]["product"] for fx in generate_fixtures.build()}
        self.assertEqual(products, set(o.PRODUCTS))


if __name__ == "__main__":
    unittest.main()
