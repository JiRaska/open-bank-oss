# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

"""Offline regression tests for release asset uploads that return HTTP 422."""

from __future__ import annotations

import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

import yaml

WORKFLOW = Path(__file__).resolve().parents[1] / "workflows/release-please.yml"


def uploader_function() -> str:
    workflow = yaml.safe_load(WORKFLOW.read_text(encoding="utf-8"))
    steps = workflow["jobs"]["release-evidence"]["steps"]
    script = next(
        step["run"]
        for step in steps
        if step.get("name") == "Build, sign and attach the release evidence bundle"
    )
    start = script.index("upload_asset() {")
    end = script.index("\n}\n\nwhile IFS=", start) + len("\n}\n")
    return script[start:end]


class ReleaseAssetUploadTest(unittest.TestCase):
    def run_case(
        self,
        code: int,
        existing: bytes,
        *,
        is_signature: bool = False,
        asset_size: int | None = None,
    ) -> subprocess.CompletedProcess[str]:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            binaries = root / "bin"
            binaries.mkdir()
            (binaries / "curl").write_text(
                """#!/usr/bin/env python3
import os, pathlib, sys
args = sys.argv[1:]
url = args[-1]
if '-X' in args and args[args.index('-X') + 1] == 'POST':
    print(os.environ['MOCK_POST_CODE'], end='')
elif '/releases/tags/' in url:
    print(pathlib.Path(os.environ['MOCK_RELEASE']).read_text(), end='')
elif '/releases/assets/' in url:
    pathlib.Path(args[args.index('-o') + 1]).write_bytes(pathlib.Path(os.environ['MOCK_EXISTING']).read_bytes())
else:
    sys.exit(2)
"""
            )
            (binaries / "cosign").write_text(
                """#!/usr/bin/env python3
import pathlib, sys
args = sys.argv[1:]
if args[0] != 'verify-blob': sys.exit(2)
signature = pathlib.Path(args[args.index('--signature') + 1])
sys.exit(0 if signature.read_bytes() == b'VALID-SIGNATURE' else 1)
"""
            )
            for name in ("curl", "cosign"):
                (binaries / name).chmod(0o755)
            document = root / "fixture-v1.cdx.json"
            document.write_bytes(b"CURRENT-DOCUMENT")
            asset = root / (document.name + ".sig") if is_signature else document
            if is_signature:
                asset.write_bytes(b"NEW-SIGNATURE")
            remote = root / "remote"
            remote.write_bytes(existing)
            release = root / "release.json"
            release.write_text(
                json.dumps(
                    {
                        "assets": [
                            {
                                "name": asset.name,
                                "size": len(existing)
                                if asset_size is None
                                else asset_size,
                                "id": 42,
                            }
                        ]
                    }
                )
            )
            env = os.environ.copy()
            env.update(
                PATH=f"{binaries}:{env['PATH']}",
                GH_TOKEN="dummy",
                GITHUB_REPOSITORY="owner/repo",
                COSIGN_KEY="dummy-key",
                MOCK_POST_CODE=str(code),
                MOCK_RELEASE=str(release),
                MOCK_EXISTING=str(remote),
            )
            command = uploader_function() + '\nbin="' + str(binaries / "cosign") + '"\n'
            command += (
                'upload_asset "https://uploads.invalid/assets" "fixture-v1" "'
                + str(asset)
                + '"\n'
            )
            result = subprocess.run(
                ["bash", "-euo", "pipefail", "-c", command],
                env=env,
                capture_output=True,
                text=True,
                check=False,
                timeout=10,
            )
            self.assertNotIn("dummy-key", result.stdout + result.stderr)
            self.assertNotIn("CURRENT-DOCUMENT", result.stdout + result.stderr)
            return result

    def test_new_upload_succeeds(self) -> None:
        self.assertEqual(self.run_case(201, b"").returncode, 0)

    def test_identical_existing_document_succeeds(self) -> None:
        self.assertEqual(self.run_case(422, b"CURRENT-DOCUMENT").returncode, 0)

    def test_stale_existing_document_fails(self) -> None:
        self.assertNotEqual(self.run_case(422, b"STALE-DOCUMENT").returncode, 0)

    def test_empty_existing_asset_fails(self) -> None:
        self.assertNotEqual(self.run_case(422, b"", asset_size=0).returncode, 0)

    def test_valid_existing_signature_succeeds(self) -> None:
        self.assertEqual(
            self.run_case(422, b"VALID-SIGNATURE", is_signature=True).returncode, 0
        )

    def test_invalid_existing_signature_fails(self) -> None:
        self.assertNotEqual(
            self.run_case(422, b"BAD-SIGNATURE", is_signature=True).returncode, 0
        )


if __name__ == "__main__":
    unittest.main()
