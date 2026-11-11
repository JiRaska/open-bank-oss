# SPDX-License-Identifier: Apache-2.0

import json
import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile

from reconcile_finrep_facts import reconcile
from check_finrep_datapoints import FIXTURE
from validate_xbrl_csv_structure import ENTRYPOINTS


class ReconcileFinrepFactsTest(unittest.TestCase):
    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory()
        self.addCleanup(self.scratch.cleanup)
        self.folder = Path(self.scratch.name)
        self.report = self.folder / "synthetic-finrep.zip"
        self.facts = json.loads(FIXTURE.read_bytes())["supportedFacts"]
        self.templates = sorted({fact["templateId"] for fact in self.facts})
        self.period = "2026-06-30"
        self.value = "123456789123456.789123"

    def sources(self, *, data_gaps=None):
        paths = []
        for template in self.templates:
            path = self.folder / f"{template}.json"
            payload = {
                "templateId": template, "period": self.period,
                "isBalanced": True, "balanceVerdict": "AGREED_BALANCED",
                "dataGaps": data_gaps or [],
                "cells": [
                    {"rowRef": fact["rowRef"], "colRef": fact["colRef"], "value": self.value, "currency": "CZK"}
                    for fact in self.facts if fact["templateId"] == template
                ],
            }
            path.write_text(json.dumps(payload), encoding="utf-8")
            paths.append(path)
        return paths

    def package(self, *, change=None, currency="CZK"):
        with ZipFile(self.report, "w") as archive:
            archive.writestr("sample/META-INF/reportPackage.json", json.dumps({
                "documentInfo": {"documentType": "https://xbrl.org/report-package/2023"},
            }))
            archive.writestr("sample/reports/report.json", json.dumps({
                "documentInfo": {"documentType": "https://xbrl.org/2021/xbrl-csv", "extends": [ENTRYPOINTS["FINREP9"]]},
            }))
            archive.writestr("sample/reports/parameters.csv", (
                f"name,value\nrefPeriod,{self.period}\nbaseCurrency,iso4217:{currency}\n"
            ))
            indicators = ["templateID,reported"]
            for template in self.templates:
                table = f"{template[0]}_{template[1:]}"
                indicators.append(f"{table},true")
                rows = ["datapoint,factValue"]
                for fact in self.facts:
                    if fact["templateId"] == template:
                        datapoint = f"dp{fact['datapointId']}"
                        value = change[1] if change and datapoint == change[0] else self.value
                        rows.append(f"{datapoint},{value}")
                archive.writestr(f"sample/reports/{table.lower()}.csv", "\n".join(rows) + "\n")
            archive.writestr("sample/reports/FilingIndicators.csv", "\n".join(indicators) + "\n")

    def test_preserves_full_decimal_precision_and_hashes_the_sources(self):
        self.package()
        result = reconcile(self.report, self.sources())
        self.assertEqual(result["result"], "SUPPORTED_FACTS_RECONCILED")
        self.assertEqual(result["facts"], 36)
        self.assertEqual(set(result["sourceSha256ByTemplate"]), set(self.templates))

    def test_refuses_one_modified_supported_fact(self):
        self.package(change=("dp32654", "123456789123456.789124"))
        with self.assertRaisesRegex(ValueError, "dp32654 differs"):
            reconcile(self.report, self.sources())

    def test_refuses_currency_mismatch_and_source_gaps(self):
        self.package(currency="EUR")
        with self.assertRaisesRegex(ValueError, "require CZK"):
            reconcile(self.report, self.sources())
        self.package()
        with self.assertRaisesRegex(ValueError, "complete mappings"):
            reconcile(self.report, self.sources(data_gaps=[{"code": "INCOMPLETE_OFFICIAL_MAPPING"}]))


if __name__ == "__main__":
    unittest.main()
