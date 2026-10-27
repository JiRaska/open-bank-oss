# SPDX-License-Identifier: Apache-2.0
import copy
import importlib.util
import json
import os
import subprocess
import sys
import unittest
from unittest.mock import patch
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "dependency_license_choice.py"
SPEC = importlib.util.spec_from_file_location("dependency_license_choice", SCRIPT)
choice = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(choice)


class LicenseChoiceTests(unittest.TestCase):
    def row(self, **overrides):
        return {
            "change_type": "added", "ecosystem": "maven",
            "name": "org.rocksdb:rocksdbjni", "version": "10.1.3",
            "package_url": choice.PACKAGE, "license": choice.EXPRESSION,
            "vulnerabilities": [], **overrides,
        }

    def test_only_approved_version_and_exact_or_expression_are_selected(self):
        self.assertEqual(choice.select_packages([self.row()]), choice.PACKAGE)
        self.assertEqual(choice.select_packages([self.row(package_url=choice.QUALIFIED_PACKAGE)]), choice.PACKAGE)
        for expression in ["GPL-2.0", "Apache-2.0 AND GPL-2.0", "Apache-2.0 OR AGPL-3.0", None]:
            with self.subTest(expression=expression), self.assertRaises(ValueError):
                choice.select_packages([self.row(license=expression)])

    def test_other_package_receives_no_exemption(self):
        self.assertEqual(choice.select_packages([]), "")
        row = self.row(package_url="pkg:maven/other/library@1", name="other:library")
        self.assertEqual(choice.select_packages([row]), "")

    def test_every_rocksdb_version_is_checked_because_action_ignores_versions(self):
        newer = self.row(package_url="pkg:maven/org.rocksdb/rocksdbjni@10.1.4", version="10.1.4")
        for rows in [[newer], [self.row(), newer], [newer, self.row()]]:
            with self.subTest(rows=rows), self.assertRaises(ValueError):
                choice.select_packages(rows)

    def test_inconsistent_or_changed_metadata_fails_closed(self):
        changes = [{"version": "10.1.4"}, {"ecosystem": "npm"}, {"change_type": "removed"},
                   {"package_url": choice.PACKAGE + "?repository_url=https://other.example"},
                   {"package_url": "pkg:maven/org.rocksdb%2Frocksdbjni@10.1.3"},
                   {"package_url": "pkg:maven/ORG.ROCKSDB/ROCKSDBJNI@10.1.3"}]
        for overrides in changes:
            with self.subTest(overrides=overrides), self.assertRaises(ValueError):
                choice.select_packages([self.row(**overrides)])
        with self.assertRaises(ValueError):
            choice.select_packages([self.row(), self.row(license="GPL-2.0")])

    def test_malformed_comparison_fails_closed(self):
        for changes in [None, {}, "not an array", [None]]:
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                choice.select_packages(changes)

    def test_vulnerabilities_and_other_license_findings_are_not_modified(self):
        changes = [self.row(vulnerabilities=[{"severity": "critical"}]),
                   self.row(name="other:gpl-library", package_url="pkg:maven/other/gpl-library@1", license="GPL-2.0")]
        original = copy.deepcopy(changes)
        self.assertEqual(choice.select_packages(changes), choice.PACKAGE)
        self.assertEqual(changes, original)

    def test_actual_retry_output_is_rechecked_and_missing_or_changed_output_fails(self):
        for data, expected in [([self.row()], 0), ([], 1), ([self.row(license="GPL-2.0")], 1)]:
            env = {**os.environ, **{name: json.dumps([row.get(field) for row in data])
                                   for field, name in choice.RETRY_FIELDS.items()}}
            result = subprocess.run([sys.executable, str(SCRIPT), "--review-result"], env=env, capture_output=True)
            self.assertEqual(result.returncode, expected, result.stderr.decode())

    def test_retry_field_projection_cannot_drop_or_misalign_rows(self):
        env = {name: json.dumps([self.row().get(field)]) for field, name in choice.RETRY_FIELDS.items()}
        with patch.dict(os.environ, env):
            self.assertEqual(choice.select_packages(choice.retry_changes()), choice.PACKAGE)
        for value in ["[]", "null", "{}", "not json"]:
            with self.subTest(value=value), patch.dict(os.environ, {**env, "RETRY_NAMES": value}):
                with self.assertRaises(ValueError):
                    choice.retry_changes()

    def test_workflow_rechecks_full_policy_and_actual_retry_comparison(self):
        workflow = SCRIPT.parents[1] / "workflows" / "dependency-review.yml"
        text = workflow.read_text(encoding="utf-8")
        self.assertEqual(text.count("fail-on-severity: high"), 2)
        self.assertEqual(text.count("deny-licenses: GPL-2.0, GPL-3.0, AGPL-3.0"), 2)
        self.assertEqual(text.count("allow-dependencies-licenses: ${{ steps.license-choice.outputs.packages }}"), 1)
        self.assertIn("dependency_license_choice.py /tmp/dependency-diff.json", text)
        for field, name in choice.RETRY_FIELDS.items():
            self.assertIn(name + ": ${{ toJSON(fromJSON(steps.review-retry.outputs.dependency-changes || '[]').*." + field + ") }}", text)
        self.assertIn("dependency_license_choice.py --review-result", text)
        self.assertNotRegex(text, r"(?m)^\s+(?:vulnerability-check:\s*false|warn-only:\s*true|allow-ghsas:)")


if __name__ == "__main__":
    unittest.main()
