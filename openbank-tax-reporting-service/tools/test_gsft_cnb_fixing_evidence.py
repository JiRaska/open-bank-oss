# SPDX-License-Identifier: Apache-2.0

import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from datetime import date
from pathlib import Path

from gsft_cnb_fixing_evidence import verify


EUR_ID = "11111111-1111-4111-8111-111111111111"
USD_ID = "22222222-2222-4222-8222-222222222222"
JPY_ID = "33333333-3333-4333-8333-333333333333"
FRIDAY = date(2026, 5, 29)
SATURDAY = date(2026, 5, 30)
HOLIDAY = date(2026, 10, 28)
ARCHIVE_ENDPOINT = (
    "https://www.cnb.cz/cs/financni-trhy/devizovy-trh/kurzy-devizoveho-trhu/"
    "kurzy-devizoveho-trhu/denni_kurz.txt?date="
)


def publication(currency="EUR", rate=25.155, rate_id=EUR_ID, **overrides):
    data = {
        "sourceService": "fx-service",
        "source": "CNB",
        "fixingDate": "2026-05-29",
        "sequence": 103,
        "quoteCurrency": "CZK",
        "validFrom": "2026-05-28T22:00:00Z",
        "validTo": "2026-05-31T22:00:00Z",
        "occurredAt": "2026-05-29T12:42:00Z",
        "rates": [{"rateId": rate_id, "currency": currency, "ratePerUnit": rate}],
    }
    data.update(overrides)
    return json.dumps(data).encode()


def archive(reference=SATURDAY, fixing="29.05.2026 #102", rows="EMU|euro|1|EUR|24,285", **overrides):
    response = f"{fixing}\nzemě|měna|množství|kód|kurz\n{rows}\n"
    data = {
        "requestUrl": ARCHIVE_ENDPOINT + reference.strftime("%d.%m.%Y"),
        "httpStatus": 200,
        "capturedAt": "2026-11-11T12:00:00Z",
        "responseText": response,
        "responseSha256": hashlib.sha256(response.encode("utf-8")).hexdigest(),
    }
    data.update(overrides)
    return json.dumps(data).encode()


class GsftCnbFixingEvidenceTest(unittest.TestCase):
    def test_complete_exact_day_reconciles_partial_publications_and_replay(self):
        euro = publication()
        dollar = publication(currency="USD", rate=22.0123, rate_id=USD_ID)
        evidence = verify([euro, dollar, euro], FRIDAY, ["CZK", "USD", "USD"])
        self.assertEqual(evidence, verify([dollar, euro], FRIDAY, ["USD", "CZK"]))
        self.assertEqual(evidence["state"], "EXACT_FIXING_EVIDENCE")
        self.assertFalse(evidence["filingReady"])
        self.assertEqual(evidence["referenceDay"], "2026-05-29")
        self.assertEqual(evidence["fixingDate"], "2026-05-29")
        self.assertEqual(evidence["czkPerUnit"], {"EUR": "25.15500000", "USD": "22.01230000"})
        self.assertEqual(evidence["rateIds"], {"EUR": EUR_ID, "USD": USD_ID})
        self.assertEqual(len(evidence["publications"]), 2)

    def test_friday_validity_window_does_not_supply_another_reference_day(self):
        for reference_day in (date(2026, 5, 30), date(2026, 5, 31), date(2026, 6, 1)):
            with self.subTest(reference_day=reference_day):
                evidence = verify([publication()], reference_day, ["CZK"])
                self.assertEqual(evidence["state"], "FX_RATES")
                self.assertEqual(evidence["observedFixingDates"], ["2026-05-29"])
                self.assertEqual(evidence["missingCurrencies"], ["EUR"])
                self.assertNotIn("fixingDate", evidence)
                self.assertNotIn("czkPerUnit", evidence)

    def test_saturday_retains_its_day_and_reconciles_each_currency_with_archive_units(self):
        rates = [
            publication(rate=24.285, sequence=102, validTo="2026-05-29T22:00:00Z"),
            publication("JPY", 0.13092, JPY_ID, sequence=102),
        ]
        exported = archive(rows="EMU|euro|1|EUR|24,285\nJaponsko|jen|100|JPY|13,092")
        evidence = verify(rates, SATURDAY, ["JPY"], exported)
        self.assertEqual(evidence["state"], "ARCHIVE_MATCHED_FIXING_EVIDENCE")
        self.assertEqual(evidence["referenceDay"], "2026-05-30")
        self.assertEqual(evidence["fixingDate"], "2026-05-29")
        self.assertEqual(evidence["czkPerUnit"], {"EUR": "24.28500000", "JPY": "0.13092000"})
        self.assertEqual(evidence["rateIds"], {"EUR": EUR_ID, "JPY": JPY_ID})
        self.assertEqual(evidence["archiveQuotes"]["JPY"], {"units": 100, "czk": "13.092"})
        self.assertEqual(evidence["archiveResponse"]["sequence"], 102)
        self.assertEqual(evidence["archiveResponse"]["responseSha256"], json.loads(exported)["responseSha256"])
        self.assertEqual(evidence["archiveResponse"]["provenanceStatus"], "OPERATOR_SUPPLIED_UNVERIFIED")
        self.assertFalse(evidence["filingReady"])

    def test_holiday_prior_fixing_needs_a_completed_prague_reference_day(self):
        previous_day = publication(
            rate=24.34, fixingDate="2026-10-27", sequence=207,
            validFrom="2026-10-26T23:00:00Z", validTo="2026-10-27T23:00:00Z",
            occurredAt="2026-10-27T13:45:00Z",
        )

        def holiday_export(captured_at):
            return archive(HOLIDAY, "27.10.2026 #207", "EMU|euro|1|EUR|24,340", capturedAt=captured_at)

        after_midnight = holiday_export("2026-10-28T23:00:00Z")
        evidence = verify([previous_day], HOLIDAY, [], after_midnight)
        self.assertEqual(evidence["state"], "ARCHIVE_MATCHED_FIXING_EVIDENCE")
        self.assertEqual(evidence["referenceDay"], "2026-10-28")
        self.assertEqual(evidence["fixingDate"], "2026-10-27")
        self.assertEqual(evidence["czkPerUnit"], {"EUR": "24.34000000"})
        with self.assertRaisesRegex(ValueError, "after the Prague reference day"):
            verify([previous_day], HOLIDAY, [], holiday_export("2026-10-28T22:59:59Z"))

    def test_workday_prepublication_archive_cannot_select_the_previous_fixing(self):
        monday = date(2026, 6, 1)
        exported = archive(monday, capturedAt="2026-06-01T07:00:00Z")
        with self.assertRaisesRegex(ValueError, "after the Prague reference day"):
            verify([publication(rate=24.285, sequence=102)], monday, [], exported)

    def test_same_day_capture_can_corroborate_an_exact_date_fixing(self):
        exported = archive(reference=FRIDAY, capturedAt="2026-05-29T12:45:00Z")
        evidence = verify([publication(rate=24.285, sequence=102)], FRIDAY, [], exported)
        self.assertEqual(evidence["state"], "ARCHIVE_MATCHED_FIXING_EVIDENCE")
        self.assertEqual(evidence["referenceDay"], evidence["fixingDate"])

    def test_missing_archive_currency_or_publication_sequence_withholds_all_usable_rates(self):
        euro = publication(rate=24.285, sequence=102)
        dollar = publication("USD", 22.0123, USD_ID, sequence=102)
        cases = (
            ([euro, dollar], ["USD"], ["USD"]),
            ([publication(rate=24.285, sequence=None)], [], ["EUR"]),
            ([publication(fixingDate="2026-05-28", validFrom="2026-05-27T22:00:00Z")], [], ["EUR"]),
        )
        for rates, currencies, missing in cases:
            with self.subTest(missing=missing, rates=rates):
                evidence = verify(rates, SATURDAY, currencies, archive())
                self.assertEqual(evidence["state"], "FX_RATES")
                self.assertEqual(evidence["missingCurrencies"], missing)
                self.assertNotIn("archiveQuotes", evidence)
                self.assertNotIn("czkPerUnit", evidence)
                self.assertNotIn("rateIds", evidence)
                self.assertFalse(evidence["filingReady"])

    def test_rejects_archive_mismatch_and_ambiguous_currency(self):
        euro = publication(rate=24.285, sequence=102)
        cases = (
            ([euro], archive(reference=FRIDAY), "exact reference-day URL"),
            ([euro], archive(requestUrl="https://example.test/denni_kurz.txt?date=30.05.2026"), "exact reference-day URL"),
            ([euro], archive(httpStatus=404), "successful response"),
            ([euro], archive(responseSha256="0" * 64), "SHA-256 mismatch"),
            ([euro], archive(fixing="01.06.2026 #103"), "fixing date follows"),
            ([euro], archive(rows="EMU|euro|1|EUR|24,285\nEMU|euro|1|EUR|24,285"), "Ambiguous duplicate"),
            ([euro], archive(rows="EMU|euro|100|EUR|24,285"), "disagree for EUR"),
            ([euro], archive(rows="EMU|euro|1|EUR|24,286"), "disagree for EUR"),
            ([publication(rate=24.285, sequence=103)], archive(), "sequences disagree"),
            (
                [euro, publication(fixingDate="2026-05-30", validFrom="2026-05-29T22:00:00Z")],
                archive(),
                "conflicts with a later FX fixing",
            ),
            ([euro], archive(provenanceStatus="AUTHENTICATED"), "export fields"),
        )
        for rates, exported, error in cases:
            with self.subTest(error=error), self.assertRaisesRegex(ValueError, error):
                verify(rates, SATURDAY, [], exported)

    def test_missing_coverage_withholds_all_usable_rates_and_eur_is_required_for_the_cap(self):
        evidence = verify([publication("USD", 22.0123, USD_ID)], FRIDAY, ["USD"])
        self.assertEqual(evidence["requiredCurrencies"], ["EUR", "USD"])
        self.assertEqual(evidence["missingCurrencies"], ["EUR"])
        self.assertNotIn("czkPerUnit", evidence)
        evidence = verify([publication()], FRIDAY, ["USD"])
        self.assertEqual(evidence["missingCurrencies"], ["USD"])
        self.assertNotIn("rateIds", evidence)

    def test_rejects_invalid_provenance_dates_and_precision(self):
        cases = (
            (publication(source="ECB"), "native CNB"),
            (publication(quoteCurrency="EUR"), "native CNB"),
            (publication(sourceService="manual"), "native CNB"),
            (publication(validFrom="2026-05-29T00:00:00Z"), "Prague midnight"),
            (publication(validTo="2026-05-28T22:00:00Z"), "validity interval is empty"),
            (publication(rate=25.000000001), "numeric"),
            (publication(rate=0), "numeric"),
            (publication(rate="25.155"), "JSON number"),
            (publication().replace(b'"sequence": 103', b'"sequence": 103, "sequence": 104'), "Duplicate field"),
        )
        for payload, error in cases:
            with self.subTest(error=error):
                with self.assertRaisesRegex(ValueError, error):
                    verify([payload], FRIDAY, [])

    def test_rejects_conflicting_rows_ids_or_publication_sequences(self):
        cases = (
            (publication(rate=26), "Conflicting rate"),
            (publication(rate_id=USD_ID), "Conflicting rate"),
            (publication(currency="USD"), "Conflicting rate"),
            (publication(currency="USD", rate_id=USD_ID, sequence=104), "Conflicting publication"),
        )
        for other, error in cases:
            with self.subTest(error=error):
                with self.assertRaisesRegex(ValueError, error):
                    verify([publication(), other], FRIDAY, [])

    def test_cli_returns_distinct_evidence_gap_and_rejected_input_results(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "publication.json"
            script = Path(__file__).with_name("gsft_cnb_fixing_evidence.py")

            def check(day, *extra):
                return subprocess.run(
                    [sys.executable, str(script), "--reference-day", day, "--publication", str(source), *extra],
                    capture_output=True, text=True, check=False,
                )

            source.write_bytes(publication())
            result = check("2026-05-29")
            self.assertEqual(result.returncode, 0)
            self.assertEqual(json.loads(result.stdout)["state"], "EXACT_FIXING_EVIDENCE")
            result = check("2026-05-30")
            self.assertEqual(result.returncode, 1)
            self.assertEqual(json.loads(result.stdout)["state"], "FX_RATES")
            source.write_bytes(publication(rate=24.285, sequence=102))
            archive_file = Path(directory) / "archive.json"
            archive_file.write_bytes(archive())
            result = check("2026-05-30", "--cnb-archive-response", str(archive_file))
            self.assertEqual(result.returncode, 0)
            self.assertEqual(json.loads(result.stdout)["state"], "ARCHIVE_MATCHED_FIXING_EVIDENCE")
            source.write_bytes(publication(source="INTERNAL"))
            result = check("2026-05-29")
            self.assertEqual(result.returncode, 2)
            self.assertEqual(result.stdout, "")


if __name__ == "__main__":
    unittest.main()
