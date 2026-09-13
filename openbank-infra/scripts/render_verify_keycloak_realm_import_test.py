#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Tests for render-verify-keycloak-realm-import.sh (issue #3246).

CI has no Vault credentials and no reason to boot a Keycloak container on every PR, so this
runs the script with SKIP_IMPORT_TEST=1 throughout — it checks the parts that do not need
Docker: placeholder discovery against the REAL committed templates, refusal on a missing env
var, successful substitution leaving no placeholder token behind, and that nothing is left in
the temp directory afterward. The Docker-backed boot verification itself was run manually
against quay.io/keycloak/keycloak:26.6.3 for both realms while preparing this change (see the
PR description) — that step is inherently not something a hosted CI runner should be trusted
to gate on, since a container that merely BOOTS proves nothing about a broken template if the
runner has no way to also mint and inspect a token, which is exactly the gap runbook 0009 warns
about ("an import that succeeds with a dropped role block prints nothing useful").
"""

import os
import pathlib
import subprocess
import sys
import tempfile
import unittest

REPO = pathlib.Path(__file__).resolve().parents[2]
SCRIPT = REPO / "openbank-infra" / "scripts" / "render-verify-keycloak-realm-import.sh"
KEYCLOAK_DIR = REPO / "openbank-infra" / "gitops" / "components" / "keycloak"

REALMS = {
    "openbank": KEYCLOAK_DIR / "realm-template.json",
    "openbank-customers": KEYCLOAK_DIR / "customers-realm-template.json",
}


def _placeholders(template_path: pathlib.Path) -> set:
    import re

    return set(re.findall(r"__[A-Z0-9_]+__", template_path.read_text()))


def _run(realm: str, env_extra: dict, tmp_home: str):
    env = dict(os.environ)
    env.update(env_extra)
    env["SKIP_IMPORT_TEST"] = "1"
    env["TMPDIR"] = tmp_home
    return subprocess.run(
        [str(SCRIPT), realm],
        cwd=str(REPO),
        env=env,
        capture_output=True,
        text=True,
        timeout=30,
    )


class ScriptExists(unittest.TestCase):
    def test_present_and_executable(self):
        self.assertTrue(SCRIPT.exists(), f"{SCRIPT} missing")
        self.assertTrue(os.access(SCRIPT, os.X_OK), f"{SCRIPT} is not executable (chmod +x)")


class MissingEnvVarRefusal(unittest.TestCase):
    """The script must refuse to run — not substitute an empty string — for any unset
    placeholder env var. An empty-string secret in a rendered realm is a worse failure than a
    stopped script: it imports cleanly and produces a client nobody can authenticate as.
    """

    def test_no_env_vars_set_fails_and_names_every_placeholder(self):
        for realm, template in REALMS.items():
            with self.subTest(realm=realm), tempfile.TemporaryDirectory() as tmp_home:
                result = _run(realm, {}, tmp_home)
                self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
                expected = {p.strip("_") for p in _placeholders(template)}
                for name in expected:
                    self.assertIn(
                        name, result.stderr,
                        f"missing-var message for {realm} did not name {name}",
                    )

    def test_one_missing_var_still_fails(self):
        # openbank has >1 placeholder; supply all but one and confirm it still refuses.
        template = REALMS["openbank"]
        names = sorted(p.strip("_") for p in _placeholders(template))
        self.assertGreater(len(names), 1, "expected openbank template to have >1 placeholder")
        env = {n: "dummy" for n in names[:-1]}
        with tempfile.TemporaryDirectory() as tmp_home:
            result = _run("openbank", env, tmp_home)
            self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn(names[-1], result.stderr)


class SuccessfulRender(unittest.TestCase):
    """With every placeholder supplied, the script must render valid JSON with no surviving
    placeholder token, and leave nothing behind in the temp directory.
    """

    def test_renders_cleanly_for_every_realm(self):
        for realm, template in REALMS.items():
            with self.subTest(realm=realm), tempfile.TemporaryDirectory() as tmp_home:
                names = {p.strip("_") for p in _placeholders(template)}
                env = {n: f"dummy-{n.lower()}" for n in names}
                result = _run(realm, env, tmp_home)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertIn("no surviving placeholder tokens", result.stdout)
                # Nothing left behind: the trap must have cleaned up regardless of exit path.
                leftovers = list(pathlib.Path(tmp_home).glob("ob-realm-render.*"))
                self.assertEqual(
                    leftovers, [],
                    f"render-verify script left temp file(s) behind: {leftovers}",
                )


class UnknownRealmRejected(unittest.TestCase):
    def test_unknown_realm_argument_fails_fast(self):
        with tempfile.TemporaryDirectory() as tmp_home:
            result = _run("not-a-real-realm", {}, tmp_home)
            self.assertNotEqual(result.returncode, 0)


class OutFlagRefusedWithoutBootVerification(unittest.TestCase):
    """`--out` hands the caller a file meant to be fed straight to `vault kv put`. In
    SKIP_IMPORT_TEST mode that file never passed the Keycloak boot check, so the combination
    must be refused rather than silently producing a file that looks verified and is not.
    """

    def test_out_plus_skip_import_test_is_refused(self):
        template = REALMS["openbank"]
        names = {p.strip("_") for p in _placeholders(template)}
        env = {n: f"dummy-{n.lower()}" for n in names}
        with tempfile.TemporaryDirectory() as tmp_home:
            out_path = os.path.join(tmp_home, "would-be-output.json")
            env_full = dict(os.environ)
            env_full.update(env)
            env_full["SKIP_IMPORT_TEST"] = "1"
            env_full["TMPDIR"] = tmp_home
            result = subprocess.run(
                [str(SCRIPT), "--out", out_path, "openbank"],
                cwd=str(REPO),
                env=env_full,
                capture_output=True,
                text=True,
                timeout=30,
            )
            self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertFalse(
                os.path.exists(out_path),
                "refused run must not have written the --out path anyway",
            )


if __name__ == "__main__":
    if sys.platform == "win32":
        raise SystemExit("this test drives a bash script; not applicable on Windows runners")
    unittest.main()
