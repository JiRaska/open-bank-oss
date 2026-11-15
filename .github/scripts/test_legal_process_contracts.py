#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Falsify legal design coverage and refusal of unsupported readiness claims."""
import contextlib
import copy
import datetime as dt
import importlib.util
import io
import json
import pathlib
import shutil
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).with_name("check-legal-process-contracts.py")
SPEC = importlib.util.spec_from_file_location("legal_process_contracts", SCRIPT)
CHECK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECK)
TODAY = dt.date(2026, 11, 15)


class LegalProcessContractsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = pathlib.Path(self.temporary.name)
        shutil.copytree(CHECK.ROOT / CHECK.DIRECTORY, self.root / CHECK.DIRECTORY)
        for path in (self.root / CHECK.DIRECTORY).glob("*.json"):
            for source in json.loads(path.read_text())["sources"]:
                if source["kind"] == "architecture":
                    dest = self.root / source["ref"]
                    dest.parent.mkdir(parents=True, exist_ok=True)
                    dest.write_text("Synthetic architecture fixture\n")

    def path(self, country="DE"):
        return self.root / CHECK.DIRECTORY / f"{country}.json"

    def mutate(self, change, country="DE"):
        pack = json.loads(self.path(country).read_text())
        change(pack)
        self.path(country).write_text(json.dumps(pack))

    def rejects(self, fragment):
        with self.assertRaisesRegex(CHECK.ContractError, fragment):
            CHECK.inspect(self.root, TODAY)

    def cli(self, *args):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = CHECK.main(["--root", str(self.root), *args])
        return code, out.getvalue(), err.getvalue()

    def test_real_contracts_cover_all_63_requirements(self):
        self.assertEqual(63, CHECK.inspect(CHECK.ROOT, TODAY))

    def test_default_success_is_design_only(self):
        code, out, err = self.cli()
        self.assertEqual(0, code, err)
        self.assertIn("SUBJECTS=63", out)
        self.assertIn("DESIGN_CONTRACTS_OK", out)
        self.assertNotIn("LEGAL_PROCESS_CONTRACTS_OK", out)

    def test_requested_readiness_never_passes_on_design_files(self):
        for countries in ("CZ", "SK", "DE", "CZ,SK,DE"):
            with self.subTest(countries=countries):
                code, out, err = self.cli("--require-jurisdictions", countries, "--require-evidence")
                self.assertEqual(1, code)
                self.assertIn("LEGAL_PROCESS_NOT_READY", err)
                self.assertNotIn("LEGAL_PROCESS_CONTRACTS_OK", out + err)

    def test_unknown_empty_and_duplicate_requested_countries_fail(self):
        for value in ("", "CZ,", "US", "cz", "CZ,CZ", "CZ, SK"):
            with self.subTest(value=value):
                self.assertEqual(1, self.cli("--require-jurisdictions", value)[0])

    def test_missing_root_and_missing_country_fail(self):
        self.path("SK").unlink()
        self.rejects("exactly CZ.json")
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(1, CHECK.main(["--root", str(self.root / "missing")]))

    def test_unknown_country_is_not_silently_ignored(self):
        shutil.copy(self.path(), self.path("US"))
        self.rejects("exactly CZ.json")

    def test_duplicate_json_keys_fail_before_parsing(self):
        source = self.path().read_text().replace('"schema_version": 1', '"schema_version": 1, "schema_version": 1')
        self.path().write_text(source)
        self.rejects("duplicate JSON key")

    def test_boolean_version_is_not_an_integer_version(self):
        self.mutate(lambda p: p.update(schema_version=True))
        self.rejects("schema_version")

    def test_unknown_fields_cannot_smuggle_ready_flag(self):
        self.mutate(lambda p: p.update(ready=True))
        self.rejects("expected fields")

    def test_runtime_status_or_date_cannot_claim_activation(self):
        original = self.path().read_text()
        for change, fragment in (({"state": "active"}, "only design"),
                                 ({"effective_from": "2026-11-15"}, "activation effective_from")):
            with self.subTest(change=change):
                self.path().write_text(original)
                self.mutate(lambda p: p.update(change))
                self.rejects(fragment)

    def test_requirement_omission_and_duplicate_are_rejected(self):
        original = self.path().read_text()
        self.mutate(lambda p: p["requirements"].pop())
        self.rejects("requirement coverage mismatch")
        self.path().write_text(original)
        self.mutate(lambda p: p["requirements"].append(copy.deepcopy(p["requirements"][0])))
        self.rejects("duplicate id")

    def test_mandatory_capability_cannot_be_declared_conditional(self):
        self.mutate(lambda p: p["requirements"][0].update(applies_when="joint-accounts-offered"))
        self.rejects("cannot be made conditional")

    def test_delivery_flag_does_not_substitute_for_execution_evidence(self):
        self.mutate(lambda p: p["requirements"][0].update(delivery="implemented"))
        self.rejects("delivery claims require")

    def test_missing_source_or_unresolved_reference_is_rejected(self):
        self.mutate(lambda p: p["requirements"][0].update(source_ids=["missing"]))
        self.rejects("unresolved source")

    def test_repository_reference_cannot_escape_checkout(self):
        self.mutate(lambda p: p["sources"][0].update(ref="../outside.md"))
        self.rejects("repository architecture reference")

    def test_source_credentials_are_not_accepted(self):
        self.mutate(lambda p: p["sources"][1].update(ref="https://synthetic:fixture@example.invalid/law"))
        self.rejects("without credentials")

    def test_missing_source_document_is_rejected(self):
        self.mutate(lambda p: p["sources"][0].update(ref="docs/adr/not-present.md"))
        self.rejects("repository architecture reference")

    def test_symlinked_contract_is_rejected(self):
        external = self.root / "external.json"
        self.path().rename(external)
        self.path().symlink_to(external)
        self.rejects("regular repository file")

    def test_future_research_date_is_not_approval(self):
        self.mutate(lambda p: p.update(reviewed_on="2027-01-01"))
        self.rejects("future")

    def test_invalid_dates_and_nonfinite_json_are_rejected(self):
        original = self.path().read_text()
        self.mutate(lambda p: p.update(reviewed_on="2026-02-30"))
        self.rejects("invalid date")
        self.path().write_text(original.replace('"revision": 1', '"revision": NaN'))
        self.rejects("invalid JSON")

    def test_wrong_types_fail_as_contract_errors_not_tracebacks(self):
        original = json.loads(self.path().read_text())
        for field in original:
            for invalid in (None, [], {}, True, 1):
                if field == "effective_from" and invalid is None:
                    continue
                if field in ("schema_version", "revision") and type(invalid) is int:
                    continue
                with self.subTest(field=field, invalid=invalid):
                    pack = copy.deepcopy(original)
                    pack[field] = invalid
                    self.path().write_text(json.dumps(pack))
                    self.rejects(".")

    def test_requirement_owner_wrong_type_fails_cleanly(self):
        self.mutate(lambda p: p["requirements"][0].update(owner=[]))
        self.rejects("unknown operational owner")


if __name__ == "__main__":
    unittest.main(verbosity=2)
