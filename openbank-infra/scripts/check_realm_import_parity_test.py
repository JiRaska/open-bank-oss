#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Falsification suite for .github/scripts/check-realm-import-parity.py (issue #3246).

Two jobs, and the second is the one a `--self-test` cannot do.

The script's own `--self-test` proves the COMPARISON is falsifiable: it feeds synthetic
documents that must be flagged and one pair that must not. That is run here so a change
to the script cannot land without it.

What synthetic fixtures cannot check is whether IMPORT_BASELINE still describes the REAL
committed templates. The baseline records what the deployed import artifact CARRIES, and
that artifact is a strict ancestor of the template, so every baselined name must also be
declared by a committed template. A name that is not is either a mis-transcribed baseline
or an artifact entry git never reviewed — and in the second case the checker's own
`importedNotDeclared` leg should have raised it, so the entry would be masking exactly the
finding it exists to preserve. That check has to read the actual files in the repo, so it
lives here.
"""

import json
import pathlib
import subprocess
import sys
import unittest

REPO = pathlib.Path(__file__).resolve().parents[2]
SCRIPT = REPO / ".github" / "scripts" / "check-realm-import-parity.py"


def _load_module():
    import importlib.util

    spec = importlib.util.spec_from_file_location("realm_import_parity", SCRIPT)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


class SelfTest(unittest.TestCase):
    def test_script_self_test_passes(self):
        r = subprocess.run(
            [sys.executable, str(SCRIPT), "--self-test"],
            capture_output=True, text=True, cwd=REPO,
        )
        self.assertEqual(r.returncode, 0, f"--self-test failed:\n{r.stderr}")


class BaselineDescribesRealTemplates(unittest.TestCase):
    """Every IMPORT_BASELINE name must be declared by a committed template for that realm."""

    def setUp(self):
        self.mod = _load_module()
        self.declared = self.mod.template_names(REPO)

    def test_every_baselined_realm_has_a_template(self):
        for realm in self.mod.IMPORT_BASELINE:
            self.assertIn(
                realm, self.declared,
                f"IMPORT_BASELINE names realm {realm!r} but no *realm-template*.json declares it",
            )

    def test_every_baselined_name_is_declared(self):
        for realm, dims in self.mod.IMPORT_BASELINE.items():
            for dim, names in dims.items():
                unknown = sorted(set(names) - self.declared[realm][dim])
                self.assertEqual(
                    unknown, [],
                    f"IMPORT_BASELINE[{realm!r}][{dim!r}] names {unknown}, which the committed "
                    f"template does not declare. The import artifact is a strict ancestor of "
                    f"the template, so either the baseline was mis-transcribed, or the artifact "
                    f"holds a name nobody reviewed — and that second case is one this checker's "
                    f"importedNotDeclared leg must raise, not one the baseline may absorb.",
                )

    def test_dimensions_are_the_ones_the_checker_compares(self):
        for realm, dims in self.mod.IMPORT_BASELINE.items():
            self.assertEqual(
                sorted(dims), sorted(self.mod.DIMENSIONS),
                f"IMPORT_BASELINE[{realm!r}] must declare every dimension explicitly (an omitted "
                f"one reads as 'the artifact carries nothing in this dimension', which is a much "
                f"stronger claim than the silence it looks like)",
            )


class TemplatesCarryNoLiteralSecrets(unittest.TestCase):
    """The committed templates must keep `__PLACEHOLDER__` tokens, never real values.

    This repo is public and the import artifact this script compares against DOES hold real
    client secrets. The reconcile procedure substitutes them at write time; nothing may ever
    move in the other direction, and the cheapest place to notice is here.
    """

    def test_placeholders_only(self):
        for p in sorted(REPO.glob(self.mod_glob())):
            doc = json.loads(p.read_text())
            for c in doc.get("clients") or []:
                s = c.get("secret")
                if s is not None:
                    self.assertTrue(
                        s.startswith("__") and s.endswith("__"),
                        f"{p.name}: client {c['clientId']} carries a literal secret",
                    )
            for u in doc.get("users") or []:
                for cred in u.get("credentials") or []:
                    v = cred.get("value")
                    if v is not None:
                        self.assertTrue(
                            v.startswith("__") and v.endswith("__"),
                            f"{p.name}: user {u.get('username')} carries a literal credential",
                        )

    def mod_glob(self):
        return _load_module().REALM_GLOB


if __name__ == "__main__":
    unittest.main()
