# SPDX-License-Identifier: Apache-2.0

import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from zipfile import ZipFile

import corep_c01_synthetic_fixture as fixture


class SyntheticCorepFixtureTest(unittest.TestCase):
    def setUp(self):
        scratch = tempfile.TemporaryDirectory()
        self.addCleanup(scratch.cleanup)
        self.folder = Path(scratch.name)
        self.report = self.folder / "synthetic-corep-c01.zip"

    def test_renders_deterministic_package_and_reconciles_only_the_supported_facts(self):
        rendered = fixture.render(self.report)
        replay = self.folder / "replay.zip"

        self.assertEqual(rendered, fixture.render(replay))
        self.assertEqual(self.report.read_bytes(), replay.read_bytes())
        self.assertFalse(rendered["filingReady"])
        self.assertEqual(rendered["facts"], 8)
        self.assertEqual({gap["datapoint"] for gap in rendered["omittedGaps"]}, {"dp32678", "dp33336"})
        with ZipFile(self.report) as archive:
            facts = fixture.csv_rows(archive, f"{fixture.ROOT}/reports/c_01.00.csv")
        self.assertEqual({row["datapoint"]: row["factValue"] for row in facts}, {
            "dp33413": "16250000", "dp84817": "14250000", "dp33411": "13250000", "dp33306": "12000000",
            "dp33309": "500000", "dp32677": "1000000", "dp33410": "1000000", "dp33412": "2000000",
        })
        reconciled = fixture.reconcile(self.report)
        self.assertEqual(reconciled["result"], "SYNTHETIC_COREP_FIXTURE_RECONCILED")
        self.assertEqual(reconciled["reportSha256"], rendered["reportSha256"])
        self.assertEqual(reconciled["sourceSha256"], rendered["sourceSha256"])

        with self.assertRaises(FileExistsError):
            fixture.render(self.report)
        self.assertEqual(self.report.read_bytes(), replay.read_bytes())

    def test_independent_readback_refuses_changed_amounts_added_gaps_and_duplicate_facts(self):
        fixture.render(self.report)
        member = f"{fixture.ROOT}/reports/c_01.00.csv"
        with ZipFile(self.report) as archive:
            original = {name: archive.read(name) for name in archive.namelist()}
        facts = original[member]
        cases = (
            (facts.replace(b"dp33413,16250000", b"dp33413,16250001"), "dp33413 differs"),
            (facts + b"dp33336,0\n", "missing or unexpected"),
            (facts.replace(b"dp33413,16250000\n", b""), "missing or unexpected"),
            (facts + b"dp33413,16250000\n", "Duplicate datapoint"),
        )
        for index, (changed, error) in enumerate(cases):
            with self.subTest(index=index):
                target = self.folder / f"changed-{index}.zip"
                with ZipFile(target, "w") as archive:
                    for name, content in (original | {member: changed}).items():
                        archive.writestr(name, content)
                with self.assertRaisesRegex(ValueError, error):
                    fixture.reconcile(target)

    def test_refuses_corrupted_source_before_creating_an_artifact(self):
        source = self.folder / "changed-source.json"
        cases = (
            (lambda data: data.update(source="LIVE_PREVIEW"), "Only the checked-in synthetic"),
            (lambda data: data["trialBalanceLines"][4].update(net="250001"), "differs from the synthetic GL"),
            (lambda data: data["previewFacts"][0].update(value="16250001"), "differs from the synthetic GL"),
            (lambda data: data["previewFacts"][0].update(value="NaN"), "finite whole CZK"),
            (lambda data: data["omittedGaps"].pop(), "omitted data gaps differ"),
        )
        for change, error in cases:
            with self.subTest(error=error):
                data = json.loads(fixture.FIXTURE.read_bytes())
                change(data)
                source.write_text(json.dumps(data), encoding="utf-8")
                with patch.object(fixture, "FIXTURE", source):
                    with self.assertRaisesRegex(ValueError, error):
                        fixture.render(self.report)
                self.assertFalse(self.report.exists())


if __name__ == "__main__":
    unittest.main()
