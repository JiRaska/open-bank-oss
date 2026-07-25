#!/usr/bin/env python3
"""Unit tests for check-api-contract.py's `correction` reclassification (#2313).

The gate demands a MAJOR bump for a breaking OpenAPI diff, and the ADR-0048 D2 invariant demands
that major(info.version) equal the served URL major. For a spec that never described the running
service those two are jointly unsatisfiable — measured on aml-service, red at 1.0.0 and red at
2.0.0 — so a spec-only PR reclassifies to `correction` and needs only MINOR.

The tests that matter here are the NEGATIVE ones: a reclassification that fires too eagerly turns
the gate off for real breaking changes. `service_touched_beyond_spec` is the whole discriminator,
so it is tested against a diff that must disqualify (a Kotlin change), one that must not (only
release-please's derived files) and the cross-service cases.

The module under test has a hyphenated filename and lives in .github/scripts, so it is loaded via
importlib — the same technique the sibling tests use.

Run:  python3 -m unittest openbank-infra/scripts/check_api_contract_test.py -v
"""

from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path

_SCRIPT_PATH = Path(__file__).resolve().parents[2] / ".github/scripts/check-api-contract.py"
_spec = importlib.util.spec_from_file_location("check_api_contract", _SCRIPT_PATH)
gate = importlib.util.module_from_spec(_spec)
assert _spec.loader is not None
_spec.loader.exec_module(gate)

SPEC = "openbank-aml-service/src/main/resources/openapi.yaml"
SVC = "openbank-aml-service"


class ServiceTouchedBeyondSpecTest(unittest.TestCase):
    """The discriminator. Empty result => the PR is spec-only => reclassify."""

    def test_spec_only_diff_is_spec_only(self):
        self.assertEqual(gate.service_touched_beyond_spec(SVC, SPEC, [SPEC]), [])

    def test_kotlin_change_disqualifies(self):
        """The case that MUST keep the breaking rule — code changed, so the contract may have."""
        changed = [SPEC, f"{SVC}/src/main/kotlin/com/openbank/aml/infrastructure/rest/AmlCaseResource.kt"]
        self.assertEqual(len(gate.service_touched_beyond_spec(SVC, SPEC, changed)), 1)

    def test_application_yaml_change_disqualifies(self):
        """Config can change served behaviour (ports, flags, openbank.api.version) — not derived."""
        changed = [SPEC, f"{SVC}/src/main/resources/application.yaml"]
        self.assertEqual(len(gate.service_touched_beyond_spec(SVC, SPEC, changed)), 1)

    def test_release_please_derived_files_do_not_disqualify(self):
        """CHANGELOG.md and version.txt cannot change what the service serves."""
        changed = [SPEC, f"{SVC}/CHANGELOG.md", f"{SVC}/version.txt"]
        self.assertEqual(gate.service_touched_beyond_spec(SVC, SPEC, changed), [])

    def test_another_services_code_does_not_disqualify_this_one(self):
        """A fleet PR correcting several specs must not disqualify each other's services."""
        changed = [SPEC, "openbank-fx-service/src/main/kotlin/Foo.kt",
                   "openbank-fx-service/src/main/resources/openapi.yaml"]
        self.assertEqual(gate.service_touched_beyond_spec(SVC, SPEC, changed), [])

    def test_service_name_prefix_is_not_a_substring_match(self):
        """openbank-aml-service-extras/ must not count as openbank-aml-service/."""
        changed = [SPEC, "openbank-aml-service-extras/src/main/kotlin/Foo.kt"]
        self.assertEqual(gate.service_touched_beyond_spec(SVC, SPEC, changed), [])

    def test_test_sources_disqualify(self):
        """Conservative on purpose: only the two derived filenames are exempt, nothing else."""
        changed = [SPEC, f"{SVC}/src/test/kotlin/AmlCaseResourceTest.kt"]
        self.assertEqual(len(gate.service_touched_beyond_spec(SVC, SPEC, changed)), 1)


class BumpSatisfiedTest(unittest.TestCase):
    """`correction` must accept MINOR and still reject a standstill or a PATCH."""

    def test_correction_accepts_minor(self):
        self.assertTrue(gate.bump_satisfied("correction", (1, 0, 0), (1, 1, 0)))

    def test_correction_accepts_major(self):
        self.assertTrue(gate.bump_satisfied("correction", (1, 0, 0), (2, 0, 0)))

    def test_correction_rejects_no_bump(self):
        self.assertFalse(gate.bump_satisfied("correction", (1, 0, 0), (1, 0, 0)))

    def test_correction_rejects_patch_only(self):
        self.assertFalse(gate.bump_satisfied("correction", (1, 0, 0), (1, 0, 1)))

    def test_breaking_still_demands_major(self):
        """The reclassification must not have loosened the real breaking rule."""
        self.assertFalse(gate.bump_satisfied("breaking", (1, 0, 0), (1, 9, 0)))
        self.assertTrue(gate.bump_satisfied("breaking", (1, 0, 0), (2, 0, 0)))

    def test_required_bump_table_covers_correction(self):
        self.assertEqual(gate.REQUIRED_BUMP["correction"], "MINOR")
        self.assertEqual(gate.REQUIRED_BUMP["breaking"], "MAJOR")


if __name__ == "__main__":
    unittest.main()
