"""Exercise the hosted release-identity workflow steps with local API/tool stubs."""

from __future__ import annotations

import hashlib
import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[3]
WORKFLOW = ROOT / ".github/workflows/verify-release-evidence.yml"
VERIFIER = ROOT / ".github/scripts/verify-release-evidence-manifest.py"
TAG = "example-v1.2.3"
COMPONENT = "example"
VERSION = "1.2.3"
COMMIT = "a" * 40


def workflow_step(name: str) -> str:
    document = yaml.safe_load(WORKFLOW.read_text(encoding="utf-8"))
    for step in document["jobs"]["verify"]["steps"]:
        if step.get("name") == name:
            return step["run"]
    raise AssertionError(f"workflow step not found: {name}")


class ReleaseIdentityWorkflowTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.github_env = self.root / "github-env"
        self.github_env.touch()
        self.curl_log = self.root / "curl-calls"
        self.curl = self.bin / "curl"
        self.curl.write_text(
            "#!/bin/sh\n"
            "printf '%s\\n' \"$*\" >> \"$FAKE_CURL_LOG\"\n"
            "out=''\n"
            "url=''\n"
            "while [ $# -gt 0 ]; do\n"
            "  if [ \"$1\" = '-o' ] && [ $# -gt 1 ]; then out=$2; shift 2; continue; fi\n"
            "  url=$1; shift\n"
            "done\n"
            "case \"$FAKE_CURL_MODE\" in\n"
            "  error) exit 22 ;;\n"
            "  malformed) printf '%s\\n' '{\"sha\":\"short\"}' ;;\n"
            "  release)\n"
            "    case \"$url\" in\n"
            "      */releases/tags/*) cat \"$FAKE_RELEASE_JSON_PATH\" ;;\n"
            "      */releases/assets/*) printf 'fixture asset %s\\n' \"${url##*/}\" > \"$out\" ;;\n"
            "      *) exit 22 ;;\n"
            "    esac ;;\n"
            '  *) case "$url" in\n'
            '       */commits/tags/*) printf \'{"sha":"%s"}\\n\' "$FAKE_COMMIT_SHA" ;;\n'
            '       *) printf \'{"sha":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}\\n\' ;;\n'
            '     esac ;;\n'
            "esac\n",
            encoding="utf-8",
        )
        self.curl.chmod(0o755)
        self.cosign = self.bin / "cosign"
        self.cosign.write_text(
            "#!/bin/sh\n"
            "if [ \"${1:-}\" = version ]; then echo 'GitVersion: v2.4.3'; exit 0; fi\n"
            "if [ \"${1:-}\" = verify-blob ]; then exit 0; fi\n"
            "exit 2\n",
            encoding="utf-8",
        )
        self.cosign.chmod(0o755)
        self.env = {
            **os.environ,
            "PATH": f"{self.bin}{os.pathsep}{os.environ['PATH']}",
            "GITHUB_ENV": str(self.github_env),
            "GH_TOKEN": "fixture-token",
            "GITHUB_REPOSITORY": "example/repo",
            "FAKE_CURL_LOG": str(self.curl_log),
            "FAKE_CURL_MODE": "valid",
            "FAKE_COMMIT_SHA": COMMIT,
            "RUNNER_TEMP": str(self.root),
            "INVENTORY_RC": "0",
            "DOWNLOAD_RC": "0",
            "IDENTITY_RC": "0",
        }

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def run_bash(self, source: str, *, check: bool = True) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            ["bash", "-e", "-o", "pipefail", "-c", source],
            cwd=self.root,
            env=self.env,
            text=True,
            capture_output=True,
            check=check,
        )

    def resolve(
        self, tags: list[str], mode: str = "valid"
    ) -> tuple[subprocess.CompletedProcess[str], str, list[str]]:
        (self.root / "tags.txt").write_text("".join(f"{tag}\n" for tag in tags), encoding="utf-8")
        self.env["FAKE_CURL_MODE"] = mode
        result = self.run_bash(workflow_step("Resolve release identities"), check=False)
        tsv = (self.root / "release-identities.tsv").read_text(encoding="utf-8").splitlines()
        env_lines = self.github_env.read_text(encoding="utf-8").splitlines()
        return result, "\n".join(tsv), env_lines

    def test_valid_tag_resolves_to_strict_full_commit(self) -> None:
        result, identities, env_lines = self.resolve([TAG])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(identities, f"{TAG}\t{COMPONENT}\t{VERSION}\t{COMMIT}")
        self.assertIn("IDENTITY_RC=0", env_lines)
        self.assertIn(TAG, self.curl_log.read_text(encoding="utf-8"))

    def test_malformed_resolved_commit_fails_identity(self) -> None:
        result, identities, env_lines = self.resolve([TAG], mode="malformed")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(identities, "")
        self.assertIn("IDENTITY_RC=1", env_lines)
        self.assertIn("could not resolve release tag", result.stdout)
        self.assertIn("invalid resolved commit", result.stderr)

    def test_api_error_fails_identity(self) -> None:
        result, identities, env_lines = self.resolve([TAG], mode="error")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(identities, "")
        self.assertIn("IDENTITY_RC=1", env_lines)
        self.assertIn("could not resolve release tag", result.stdout)

    def test_mixed_tags_keep_valid_identity_and_fail_closed(self) -> None:
        result, identities, env_lines = self.resolve([TAG, "example-vbad"])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(identities, f"{TAG}\t{COMPONENT}\t{VERSION}\t{COMMIT}")
        self.assertIn("IDENTITY_RC=1", env_lines)
        calls = self.curl_log.read_text(encoding="utf-8").splitlines()
        self.assertEqual(len(calls), 1)
        self.assertIn(TAG, calls[0])
        self.assertNotIn("example-vbad", calls[0])

    def test_malformed_tag_does_not_call_api(self) -> None:
        result, identities, env_lines = self.resolve(["bad/tag"])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(identities, "")
        self.assertIn("IDENTITY_RC=1", env_lines)
        self.assertFalse(self.curl_log.exists())

    def write_manifest_fixture(self, *, manifest_commit: str = COMMIT) -> Path:
        evidence_dir = self.root / "ev" / TAG
        evidence_dir.mkdir(parents=True)
        manifest = {"schema": "openbank.evidence/v1", "tag": TAG,
                    "component": COMPONENT, "version": VERSION, "git_commit": manifest_commit}
        for key, suffix in (("sbom", ".cdx.json"),
                            ("slsa_provenance", ".slsa.json"),
                            ("vex", ".vex.json")):
            name = f"{TAG}{suffix}"
            payload = f"{key} fixture".encode()
            (evidence_dir / name).write_bytes(payload)
            (evidence_dir / f"{name}.sig").write_bytes(b"fake signature")
            manifest[key] = {"file": name, "signature": f"{name}.sig",
                             "sha256": hashlib.sha256(payload).hexdigest()}
        (evidence_dir / f"{TAG}.evidence.json").write_text(json.dumps(manifest), encoding="utf-8")
        (evidence_dir / f"{TAG}.evidence.json.sig").write_bytes(b"fake signature")
        (self.root / ".github/scripts").mkdir(parents=True)
        shutil.copy2(VERIFIER, self.root / ".github/scripts/verify-release-evidence-manifest.py")
        (self.root / "release-identities.tsv").write_text(
            f"{TAG}\t{COMPONENT}\t{VERSION}\t{COMMIT}\n", encoding="utf-8"
        )
        return evidence_dir

    def verify_manifest(self, *, manifest_commit: str = COMMIT) -> subprocess.CompletedProcess[str]:
        self.write_manifest_fixture(manifest_commit=manifest_commit)
        return self.run_bash(workflow_step("Verify signatures (cosign + KMS) and manifest digests"),
                             check=False)

    def test_verification_step_accepts_matching_tag_identity(self) -> None:
        result = self.verify_manifest()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("every latest evidence bundle verified", result.stdout)

    def test_verification_step_rejects_wrong_manifest_commit(self) -> None:
        result = self.verify_manifest(manifest_commit="b" * 40)
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("git_commit does not match", result.stderr)
        self.assertIn("verification failed", result.stdout)

    def download_fixture(self, *, mutation: str = "valid") -> tuple[subprocess.CompletedProcess[str], list[str], Path]:
        assets = []
        names = [f"{TAG}{suffix}" for suffix in (
            ".cdx.json", ".cdx.json.sig", ".cdx.json.intoto.jsonl",
            ".slsa.json", ".slsa.json.sig", ".vex.json", ".vex.json.sig",
            ".evidence.json", ".evidence.json.sig",
        )]
        if mutation == "missing":
            names.pop()
        for index, name in enumerate(names):
            asset_id: int | str = 100 + index
            if mutation == "invalid_id" and index == 0:
                asset_id = "not-an-id"
            assets.append({"id": asset_id, "name": name, "size": 20})
        if mutation == "duplicate":
            assets.append({"id": 999, "name": names[0], "size": 20})
        assets.append({"id": 1000, "name": "unrelated-large.jar", "size": 999999999})
        release_json = self.root / "release.json"
        release_json.write_text(json.dumps({"assets": assets}), encoding="utf-8")
        self.env["FAKE_CURL_MODE"] = "release"
        self.env["FAKE_RELEASE_JSON_PATH"] = str(release_json)
        (self.root / "release-identities.tsv").write_text(
            f"{TAG}\t{COMPONENT}\t{VERSION}\t{COMMIT}\n", encoding="utf-8"
        )
        scripts = self.root / ".github/scripts"
        scripts.mkdir(parents=True, exist_ok=True)
        shutil.copy2(ROOT / ".github/scripts/release_evidence_assets.py",
                     scripts / "release_evidence_assets.py")
        result = self.run_bash(workflow_step("Download release assets"), check=False)
        calls = self.curl_log.read_text(encoding="utf-8").splitlines()
        return result, calls, self.root / "ev" / TAG

    def test_download_step_selects_only_nine_required_assets(self) -> None:
        result, calls, evidence_dir = self.download_fixture()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("DOWNLOAD_RC=0", self.github_env.read_text(encoding="utf-8"))
        downloads = [call for call in calls if "/releases/assets/" in call]
        self.assertEqual(len(downloads), 9, calls)
        self.assertEqual(len(list(evidence_dir.iterdir())), 9)
        self.assertFalse((evidence_dir / "unrelated-large.jar").exists())

    def test_download_step_fails_closed_on_incomplete_asset_metadata(self) -> None:
        for mutation in ("missing", "duplicate", "invalid_id"):
            with self.subTest(mutation=mutation):
                self.tearDown()
                self.setUp()
                result, calls, evidence_dir = self.download_fixture(mutation=mutation)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertIn("DOWNLOAD_RC=1", self.github_env.read_text(encoding="utf-8"))
                self.assertIn("required release evidence asset metadata", result.stdout)
                self.assertFalse(any("/releases/assets/" in call for call in calls), calls)
                self.assertEqual(list(evidence_dir.iterdir()), [])


if __name__ == "__main__":
    unittest.main()
