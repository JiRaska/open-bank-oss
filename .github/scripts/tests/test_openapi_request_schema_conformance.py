#!/usr/bin/env python3
"""Fail-closed controls for the request-schema conformance gate."""

from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


CHECKER = Path(__file__).resolve().parents[1] / "check-openapi-request-schema-conformance.py"


class RequestSchemaConformanceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        spec = self.root / "sample" / "src/main/resources/openapi.yaml"
        spec.parent.mkdir(parents=True)
        spec.write_text(
            "paths:\n"
            "  /api/v1/sample:\n"
            "    post:\n"
            "      requestBody:\n"
            "        content:\n"
            "          application/json:\n"
            "            schema:\n"
            "              $ref: '#/components/schemas/SampleRequest'\n"
            "components:\n"
            "  schemas:\n"
            "    SampleRequest:\n"
            "      properties:\n"
            "        value:\n"
            "          type: string\n",
            encoding="utf-8",
        )
        self.generated = self.root / "generated.yaml"

    def check(self, *flags: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(CHECKER), "--service", "sample", "--generated", str(self.generated), *flags],
            cwd=self.root,
            capture_output=True,
            text=True,
            check=False,
        )

    def test_missing_generated_document_fails_under_enforce(self) -> None:
        result = self.check("--enforce")
        self.assertEqual(result.returncode, 2)
        self.assertIn("::error::", result.stdout)

    def test_missing_generated_document_remains_advisory(self) -> None:
        result = self.check()
        self.assertEqual(result.returncode, 0)
        self.assertIn("::notice::", result.stdout)

    def test_present_matching_document_is_compared(self) -> None:
        spec = self.root / "sample" / "src/main/resources/openapi.yaml"
        self.generated.write_text(spec.read_text(encoding="utf-8"), encoding="utf-8")
        result = self.check("--enforce")
        self.assertEqual(result.returncode, 0)
        self.assertIn("1 request schema(s) compared, 0 finding(s)", result.stdout)

    def test_present_mismatching_document_fails_under_enforce(self) -> None:
        spec = self.root / "sample" / "src/main/resources/openapi.yaml"
        self.generated.write_text(spec.read_text(encoding="utf-8").replace("value:", "other:"), encoding="utf-8")
        result = self.check("--enforce")
        self.assertEqual(result.returncode, 1)
        self.assertIn("published-not-parsed", result.stdout)


if __name__ == "__main__":
    unittest.main()
