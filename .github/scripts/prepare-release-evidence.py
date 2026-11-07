# SPDX-License-Identifier: Apache-2.0
"""Generate release evidence from an isolated exact-tag checkout, before signing.

The workflow checkout supplies trusted orchestration; the tag checkout supplies
build inputs and reviewed VEX triage. Invocation identity remains GITHUB_SHA.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

SCRIPTS = Path(__file__).resolve().parent
SUFFIXES = (".cdx.json", ".slsa.json", ".vex.json", ".evidence.json")


def git(root: Path, *args: str) -> str:
    return subprocess.check_output(["git", *args], cwd=root, text=True).strip()


def remote_commit(root: Path, tag: str) -> str:
    if re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", tag) is None:
        raise ValueError("invalid release tag")
    ref = "refs/tags/" + tag
    lines = git(root, "ls-remote", "--exit-code", "origin", ref, ref + "^{}").splitlines()
    refs = dict(line.split("\t", 1)[::-1] for line in lines)
    if not refs or set(refs) - {ref, ref + "^{}"}:
        raise ValueError("missing or ambiguous release tag")
    commit = refs.get(ref + "^{}", refs.get(ref, ""))
    if re.fullmatch(r"[0-9a-f]{40}", commit) is None:
        raise ValueError("invalid release commit")
    return commit


def require_remote(root: Path, tag: str, expected: str) -> None:
    if remote_commit(root, tag) != expected:
        raise ValueError("release tag moved during evidence generation")


def require_validated_wrapper(producer: Path, source: Path) -> None:
    # The workflow validated the producer's JAR. Equality transfers that validation
    # and preserves its trusted launcher/distribution selection, without substituting
    # different build inputs into the release checkout.
    for relative in ("gradlew", "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties"):
        paths = []
        for base in (producer, source):
            parts = Path(relative).parts
            if any(base.joinpath(*parts[:end]).is_symlink() for end in range(1, len(parts) + 1)):
                raise ValueError("release wrapper inputs must not contain symlinks")
            path = base / relative
            if not path.is_file():
                raise ValueError("release wrapper input is missing or not a regular file")
            paths.append(path)
        if paths[0].read_bytes() != paths[1].read_bytes():
            raise ValueError("release wrapper differs from the validated producer inputs")


def prepare(root: Path, module: str, component: str, version: str, tag: str, *, expected_commit: str | None = None) -> str:
    if re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]*", module) is None:
        raise ValueError("invalid release module")
    if tag != f"{component}-v{version}":
        raise ValueError("release component/version does not match tag")
    commit = remote_commit(root, tag)
    if expected_commit is not None and (re.fullmatch(r'[0-9a-f]{40}', expected_commit) is None or commit != expected_commit):
        raise ValueError('release tag differs from approved source commit before execution')
    # Fetch only this release ref. Never rely on a stale runner's local tag.
    ref = "refs/release-evidence/" + hashlib.sha256(tag.encode()).hexdigest()
    git(root, "fetch", "--no-tags", "origin", f"+refs/tags/{tag}:{ref}")
    if git(root, "rev-parse", "--verify", ref + "^{commit}") != commit:
        raise ValueError("release tag changed while fetching")
    with tempfile.TemporaryDirectory(prefix="release-evidence-") as temp:
        checkout = Path(temp) / "source"
        git(root, "worktree", "add", "--detach", str(checkout), commit)
        try:
            module_path = checkout / module
            if module_path.is_symlink() or not module_path.is_dir():
                raise ValueError("release module must be a source directory")
            config = json.loads((checkout / "release-please-config.json").read_text())
            if config["packages"].get(module, {}).get("component") != component:
                raise ValueError("release component differs from tagged configuration")
            if (module_path / "version.txt").read_text().strip() != version:
                raise ValueError("release version differs from tagged source")
            sbom = checkout / (tag + ".cdx.json")
            env = {**os.environ, "RELEASE_EVIDENCE_SOURCE_SHA": commit, "SOURCE_COMMIT": commit}
            if (module_path / "build.gradle.kts").is_file():
                require_validated_wrapper(root, checkout)
                # A failed JVM build must not fall back to an incomplete filesystem SBOM.
                subprocess.run(["./gradlew", f":{module}:cyclonedxBom", "--console=plain", "--no-daemon"], cwd=checkout, env=env, stdout=sys.stderr, check=True)
                shutil.copyfile(module_path / "build/reports/bom.json", sbom)
            else:
                subprocess.run(["trivy", "fs", "--format", "cyclonedx", "--output", str(sbom), module], cwd=checkout, env=env, stdout=sys.stderr, check=True)
            if not sbom.is_file() or not sbom.stat().st_size:
                raise ValueError("release SBOM is missing or empty")
            subprocess.run(["bash", str(SCRIPTS / "build-release-evidence.sh"), component, version, tag, module, sbom.name], cwd=checkout, env=env, stdout=sys.stderr, check=True)
            manifest = json.loads((checkout / (tag + ".evidence.json")).read_text())
            if (manifest.get("component"), manifest.get("version"), manifest.get("tag"), manifest.get("git_commit")) != (component, version, tag, commit):
                raise ValueError("generated evidence identity differs from release source")
            for key, suffix in (("sbom", ".cdx.json"), ("slsa_provenance", ".slsa.json"), ("vex", ".vex.json")):
                data = (checkout / (tag + suffix)).read_bytes()
                if not data or manifest[key]["sha256"] != hashlib.sha256(data).hexdigest():
                    raise ValueError("generated evidence content digest mismatch")
            require_remote(root, tag, commit)
            for suffix in SUFFIXES:
                shutil.copyfile(checkout / (tag + suffix), root / (tag + suffix))
        finally:
            git(root, "worktree", "remove", "--force", str(checkout))
    return commit


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check-remote", nargs=2, metavar=("TAG", "COMMIT"))
    parser.add_argument("identity", nargs="*")
    args = parser.parse_args()
    root = Path.cwd()
    try:
        if args.check_remote:
            require_remote(root, *args.check_remote)
        elif len(args.identity) == 4:
            print(prepare(root, *args.identity))
        else:
            parser.error("expected MODULE COMPONENT VERSION TAG")
    except (OSError, ValueError, KeyError, subprocess.CalledProcessError) as exc:
        print(f"::error::release source preparation failed: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
