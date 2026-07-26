# SPDX-License-Identifier: Apache-2.0
"""Falsification tests for check-authz-pdp-parity.py (issue #2228).

THE POINT OF THIS FILE. The guard reports 0 violations on `main` today, and that 0 is NOT
evidence it works: #2403 set `AUTHZ_ENFORCE=false` on the finrep and onboarding manifests, so
the OLD manifest-only reasoning also reports 0. A gate that has only ever printed 0 is
unfalsified — its failure path is code nobody has run. So every test here builds a fixture tree
and asserts what the guard PRINTS, not merely its exit code, in both directions:

  must_flag     — @Authorize present, enforce true, no `opa` sidecar  -> 1 violation
  must_not_flag — @Authorize present, enforce true, sidecar declared  -> 0 violations
  must_not_flag — NO @Authorize, enforce true, no sidecar             -> 0 violations, skipped
  must_flag     — @Authorize only in a NESTED KDoc                    -> not an annotation

Run: python3 .github/scripts/check_authz_pdp_parity_test.py
"""
from __future__ import annotations

import importlib.util
import pathlib
import subprocess
import sys
import tempfile
import textwrap
import unittest

import yaml

HERE = pathlib.Path(__file__).resolve().parent
SCRIPT = HERE / "check-authz-pdp-parity.py"

_spec = importlib.util.spec_from_file_location("check_authz_pdp_parity", SCRIPT)
mod = importlib.util.module_from_spec(_spec)
assert _spec.loader is not None
_spec.loader.exec_module(mod)


def workload_yaml(service: str, *, sidecar: bool, enforce: str | None) -> str:
    """A minimal but REAL workload doc — built as a dict and dumped, so the fixture cannot be
    quietly malformed. A hand-indented f-string produced `0 workload(s) checked` and every
    assertion passed vacuously; that near-miss is why this goes through yaml.safe_dump."""
    app: dict = {"name": "app", "image": f"ghcr.io/jiraska/openbank-{service}:1.0.0"}
    if enforce is not None:
        app["env"] = [{"name": "AUTHZ_ENFORCE", "value": enforce}]
    containers = [app]
    if sidecar:
        containers.append({"name": "opa", "image": "openpolicyagent/opa:1.0.0"})
    return yaml.safe_dump(
        {
            "apiVersion": "apps/v1",
            "kind": "Deployment",
            "metadata": {"name": service},
            "spec": {"template": {"spec": {"containers": containers}}},
        }
    )


class Fixture:
    """A minimal repo: one gitops workload plus one service module."""

    def __init__(self, service: str, *, sidecar: bool, enforce: str | None, kotlin: str | None):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.tmp.name)
        comp = self.root / "openbank-infra" / "gitops" / "components" / service
        comp.mkdir(parents=True)
        (comp / f"{service}.yaml").write_text(workload_yaml(service, sidecar=sidecar, enforce=enforce))
        src = self.root / f"openbank-{service}" / "src" / "main" / "kotlin"
        src.mkdir(parents=True)
        if kotlin is not None:
            (src / "Resource.kt").write_text(kotlin)
        res = self.root / f"openbank-{service}" / "src" / "main" / "resources"
        res.mkdir(parents=True)
        res.joinpath("application.yaml").write_text("authz:\n  enforce: \"${AUTHZ_ENFORCE:true}\"\n")

    def run(self, *extra: str) -> tuple[int, str]:
        proc = subprocess.run(
            [sys.executable, str(SCRIPT), str(self.root), *extra],
            capture_output=True,
            text=True,
        )
        return proc.returncode, proc.stdout + proc.stderr

    def __enter__(self):
        return self

    def __exit__(self, *a):
        self.tmp.cleanup()


ANNOTATED = textwrap.dedent(
    """\
    package com.openbank.x

    import com.openbank.libs.authz.Authorize

    class Resource {
        @Authorize(action = "read", resource = "x")
        fun get(): String = "ok"
    }
    """
)

# The exact shape that broke sibling guards: prose ABOUT the annotation, inside a KDoc whose
# nested block comment closes early under a naive stripper.
PROSE_ONLY = textwrap.dedent(
    """\
    package com.openbank.x

    /**
     * This resource deliberately carries no @Authorize.
     * /* a nested block, as KDoc code samples routinely contain: @Authorize(action = "x") */
     * Everything above is commentary, not an annotation.
     */
    class Resource {
        fun get(): String = "ok"
    }
    """
)


class TestParity(unittest.TestCase):
    # ---- direction 1: it MUST flag ------------------------------------------------
    def test_flags_annotated_service_with_no_sidecar(self):
        with Fixture("must-flag-service", sidecar=False, enforce="true", kotlin=ANNOTATED) as f:
            code, out = f.run("--enforce")
            self.assertIn("1 enforce-without-PDP violation", out, out)
            self.assertIn("must-flag-service enforces authz", out, out)
            self.assertIn("::error", out, out)
            self.assertEqual(1, code, out)

    def test_flags_when_enforce_comes_from_the_application_yaml_default(self):
        with Fixture("must-flag-service", sidecar=False, enforce=None, kotlin=ANNOTATED) as f:
            code, out = f.run("--enforce")
            self.assertIn("1 enforce-without-PDP violation", out, out)
            self.assertIn("(app-default)", out, out)
            self.assertEqual(1, code, out)

    # ---- direction 2: it MUST NOT flag --------------------------------------------
    def test_does_not_flag_annotated_service_that_has_a_sidecar(self):
        with Fixture("good-service", sidecar=True, enforce="true", kotlin=ANNOTATED) as f:
            code, out = f.run("--enforce")
            self.assertIn("0 enforce-without-PDP violation", out, out)
            self.assertNotIn("0 skipped", out.replace("0 skipped (no @Authorize in src/main: none)", ""), out)
            self.assertEqual(0, code, out)

    def test_does_not_flag_a_service_with_no_authorize_at_all(self):
        """finrep/onboarding: nothing to fail closed, so a missing PDP is not a defect."""
        with Fixture("bare-service", sidecar=False, enforce="true", kotlin=None) as f:
            code, out = f.run("--enforce")
            self.assertIn("0 enforce-without-PDP violation", out, out)
            self.assertIn("skipped (no @Authorize in src/main: bare-service)", out, out)
            self.assertEqual(0, code, out)

    def test_prose_about_the_annotation_is_not_an_annotation(self):
        """A KDoc naming @Authorize — including one with a NESTED block comment — must not count."""
        with Fixture("prose-service", sidecar=False, enforce="true", kotlin=PROSE_ONLY) as f:
            code, out = f.run("--enforce")
            self.assertIn("0 enforce-without-PDP violation", out, out)
            self.assertIn("skipped (no @Authorize in src/main: prose-service)", out, out)
            self.assertEqual(0, code, out)


class TestCommentStripper(unittest.TestCase):
    def test_nested_block_comments_close_once(self):
        got = mod.strip_kotlin_comments("a /* x /* y */ z */ b")
        self.assertNotIn("z", got)
        self.assertIn("a", got)
        self.assertIn("b", got)

    def test_string_literals_survive(self):
        self.assertIn('"// not a comment"', mod.strip_kotlin_comments('val s = "// not a comment"'))
        self.assertIn("/* not a comment */", mod.strip_kotlin_comments('val s = """/* not a comment */"""'))

    def test_line_comment_removed_but_newline_kept(self):
        self.assertEqual("val a = 1\nval b = 2\n", mod.strip_kotlin_comments("val a = 1 // c\nval b = 2\n").replace(" \n", "\n"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
