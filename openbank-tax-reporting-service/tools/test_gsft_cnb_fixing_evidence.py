# SPDX-License-Identifier: Apache-2.0

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
FRIDAY = date(2026, 5, 29)


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

            def check(day):
                return subprocess.run(
                    [sys.executable, str(script), "--reference-day", day, "--publication", str(source)],
                    capture_output=True, text=True, check=False,
                )

            source.write_bytes(publication())
            result = check("2026-05-29")
            self.assertEqual(result.returncode, 0)
            self.assertEqual(json.loads(result.stdout)["state"], "EXACT_FIXING_EVIDENCE")
            result = check("2026-05-30")
            self.assertEqual(result.returncode, 1)
            self.assertEqual(json.loads(result.stdout)["state"], "FX_RATES")
            source.write_bytes(publication(source="INTERNAL"))
            result = check("2026-05-29")
            self.assertEqual(result.returncode, 2)
            self.assertEqual(result.stdout, "")


if __name__ == "__main__":
    unittest.main()
