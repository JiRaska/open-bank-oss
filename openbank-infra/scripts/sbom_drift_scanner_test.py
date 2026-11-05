# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Tests for the sbom-drift-scanner image comparison (#14752).

The compare script is read out of the CronJob's own heredoc, so the code under test IS
the code that ships. Only pure functions are exec'd; the script's top-level loop calls
kubectl and is not run.
"""
from __future__ import annotations

import ast
import textwrap
import types
import unittest
from pathlib import Path

import yaml

_ROOT = Path(__file__).resolve().parents[2]
_MANIFEST = _ROOT / "openbank-infra/gitops/components/sbom-drift-scanner/sbom-drift-scanner.yaml"
_PURE = {"image_in_sync"}


def _compare_script() -> str:
    for doc in yaml.safe_load_all(_MANIFEST.read_text()):
        if not doc or doc.get("kind") != "CronJob":
            continue
        pod = doc["spec"]["jobTemplate"]["spec"]["template"]["spec"]
        for c in pod.get("initContainers", []) + pod.get("containers", []):
            for arg in c.get("args", []) or []:
                marker = "cat > /tmp/compare.py << 'PYEOF'\n"
                if marker in arg:
                    body = arg.split(marker, 1)[1].split("\nPYEOF", 1)[0]
                    return textwrap.dedent(body)
    raise AssertionError("compare.py heredoc not found in the CronJob")


def _load() -> types.ModuleType:
    tree = ast.parse(_compare_script())
    funcs = [n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name in _PURE]
    assert {f.name for f in funcs} == _PURE, "image_in_sync missing from the shipped script"
    mod = types.ModuleType("sbom_compare")
    exec(compile(ast.Module(body=funcs, type_ignores=[]), "compare.py", "exec"), mod.__dict__)
    return mod


m = _load()

REPO = "registry.example/openbank-balance-service"
DIGEST = "sha256:" + "01" * 32
OTHER = "sha256:" + "ff" * 32


class ImageInSyncTest(unittest.TestCase):
    def test_digest_pinned_matches_by_image_id(self):
        # The live shape that failed nightly: .image is the local config id.
        self.assertTrue(m.image_in_sync(
            "sha256:" + "08" * 32, f"{REPO}@{DIGEST}", f"{REPO}:sandbox-a47f3661@{DIGEST}"))

    def test_digest_pinned_different_digest_is_drift(self):
        self.assertFalse(m.image_in_sync(
            "sha256:" + "08" * 32, f"{REPO}@{OTHER}", f"{REPO}:sandbox-a47f3661@{DIGEST}"))

    def test_digest_pinned_without_image_id_is_drift(self):
        self.assertFalse(m.image_in_sync("sha256:" + "08" * 32, "", f"{REPO}:t@{DIGEST}"))

    def test_tag_match(self):
        self.assertTrue(m.image_in_sync(f"{REPO}:a", f"{REPO}@{DIGEST}", f"{REPO}:a"))

    def test_tag_mismatch_is_drift_even_with_digest_in_image_id(self):
        self.assertFalse(m.image_in_sync(f"{REPO}:a", f"{REPO}@{DIGEST}", f"{REPO}:b"))

    def test_no_declared_image_is_never_in_sync(self):
        self.assertFalse(m.image_in_sync(f"{REPO}:a", f"{REPO}@{DIGEST}", None))


if __name__ == "__main__":
    unittest.main()
