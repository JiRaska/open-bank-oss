#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Verify the signed, digest-bound Admin UI build record for a proposed image tag."""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
import re
import subprocess
import sys
from pathlib import Path

TAG = re.compile(r"sandbox-([0-9a-f]{8,40})(?:-run[0-9]+)?")
DIGEST = re.compile(r"sha256:[0-9a-f]{64}")
ADMIN_UI_MANIFEST = "openbank-infra/gitops/components/admin-ui/admin-ui.yaml"


def materialized_context_matches(record: dict, root: Path) -> bool:
    """Compare main source bytes; accept only explicitly signed producer materials.

    A clean flusher cannot reproduce historical external responses or artifacts.
    Their exact bytes were captured and signed by the trusted producer run. The
    source checkout must still match every Git material (except the proven own pin).
    """
    spec = importlib.util.spec_from_file_location(
        "freeze_admin_ui_context", Path(__file__).with_name("freeze-admin-ui-context.py"))
    if spec is None or spec.loader is None:
        return False
    freeze = importlib.util.module_from_spec(spec)
    # Importing the helper must not create an untracked __pycache__ in the
    # checkout: its own strict input inventory would correctly reject it.
    write_bytecode = sys.dont_write_bytecode
    try:
        sys.dont_write_bytecode = True
        spec.loader.exec_module(freeze)
    finally:
        sys.dont_write_bytecode = write_bytecode
    try:
        expected = {entry["path"]: entry for entry in record["contextManifest"]["files"]}
        entries = record["contextManifest"]["files"]
        if len(expected) != len(entries) or not all(isinstance(path, str) for path in expected):
            return False
        source = record["sourceCommit"]
        producer = record["contextManifest"]["producer"]
        if (producer.get("kind") != "github-actions" or not str(producer.get("runId", "")).isdecimal()
                or not str(producer.get("runAttempt", "")).isdecimal()
                or not str(producer.get("workflow", "")).endswith(
                    ".github/workflows/admin-ui-deploy.yml@refs/heads/main")):
            return False
        listed = subprocess.run(["git", "ls-files", "-z"], cwd=root,
                                check=True, capture_output=True).stdout
        tracked = {os.fsdecode(path) for path in listed.split(b"\0") if path}
        if not tracked.issubset(expected):
            return False
        if not freeze.receipts_cover(entries, record["contextManifest"]["externalFeeds"]):
            return False
        for rel, entry in expected.items():
            path = root / rel
            if not path.resolve().is_relative_to(root.resolve()):
                return False
            material = entry.get("material")
            if not isinstance(material, dict):
                return False
            content_hash = (hashlib.sha256(os.fsencode(entry["symlink"])).hexdigest()
                            if "symlink" in entry else entry["sha256"])
            expected_material = freeze.material_for(Path(rel), content_hash, source, producer)
            if expected_material.get("source") in freeze.ARTIFACT_SOURCES:
                receipt = next((item for item in record["contextManifest"]["externalFeeds"]
                                if item["path"] == rel), None)
                if receipt is None:
                    return False
                expected_material["artifactId"] = receipt["artifactId"]
                expected_material["archiveSha256"] = receipt["archiveSha256"]
            if material != expected_material:
                return False
            if material["kind"] == "git" and rel not in tracked:
                return False
            if rel == ADMIN_UI_MANIFEST:
                continue  # ancestry + exact one-line image-pin proof is checked by the caller
            if material["kind"] == "git":
                if path.is_symlink():
                    if entry.get("symlink") != os.readlink(path):
                        return False
                elif path.is_file():
                    content = path.read_bytes()
                    if (entry.get("sha256") != hashlib.sha256(content).hexdigest()
                            or entry.get("size") != len(content)
                            or entry.get("executable") != bool(path.stat().st_mode & 0o111)):
                        return False
                else:
                    return False
    except (OSError, KeyError, TypeError, ValueError, subprocess.CalledProcessError):
        return False
    return True


def validate(record: dict, manifest: bytes, image: str, tag: str) -> str:
    match = TAG.fullmatch(tag)
    if not match or record.get("schema") != "openbank.admin-ui.image-build/v1":
        raise ValueError("unknown Admin UI build record or tag")
    source = record.get("sourceCommit", "")
    if not re.fullmatch(r"[0-9a-f]{40}", source) or not source.startswith(match[1]):
        raise ValueError("tag does not identify the recorded source commit")
    if record.get("image") != image or record.get("tag") != tag:
        raise ValueError("image or tag differs from the signed record")
    if not DIGEST.fullmatch(record.get("digest", "")):
        raise ValueError("missing immutable image digest")
    if record.get("contextManifestSha256") != hashlib.sha256(manifest).hexdigest():
        raise ValueError("frozen context manifest differs from signed build record")
    context = json.loads(manifest)
    if record.get("contextManifest") != context:
        raise ValueError("signed predicate does not contain the frozen context inventory")
    if context.get("schema") != "openbank.admin-ui.build-context/v1" or context.get("sourceCommit") != source:
        raise ValueError("frozen context has unknown schema or source")
    files = context.get("files")
    if not isinstance(files, list) or not files or not any(x.get("path") == "openbank-admin-ui/Dockerfile" for x in files):
        raise ValueError("frozen context lacks file inventory or Dockerfile")
    args = record.get("buildArgs", {})
    if args.get("BUILD_GIT_SHA") != match[1] or not args.get("BUILD_DATE") or not args.get("BUILD_VERSION"):
        raise ValueError("build arguments are missing or disagree with tag")
    if record.get("platform") != "linux/arm64":
        raise ValueError("unexpected image platform")
    return source


def run(*argv: str) -> str:
    result = subprocess.run(argv, check=True, capture_output=True, text=True)
    return result.stdout.strip()


def check_tag_digest(record: dict, tag_digest: str) -> None:
    if tag_digest != record["digest"]:
        raise ValueError("registry tag no longer resolves to the signed image digest")


def attestation_matches(record: dict, verified: list[dict]) -> bool:
    return any(item.get("verificationResult", {}).get("statement", {}).get("predicate") == record
               and any(subject.get("name") == record["image"]
                       and subject.get("digest", {}).get("sha256") == record["digest"].removeprefix("sha256:")
                       for subject in item.get("verificationResult", {}).get("statement", {}).get("subject", []))
               for item in verified)


def verify(root: Path, repo: str, image: str, tag: str) -> None:
    trusted_registry = os.environ.get("ADMIN_UI_IMAGE_VERIFY_REGISTRY", "")
    if not re.fullmatch(r"[0-9]{12}\.dkr\.ecr\.[a-z0-9-]+\.amazonaws\.com(?:\.cn)?", trusted_registry):
        raise ValueError("trusted Admin UI registry is unavailable or invalid")
    if image != f"{trusted_registry}/openbank-admin-ui":
        raise ValueError("Admin UI image is outside the trusted registry and repository")
    if not TAG.fullmatch(tag):
        raise ValueError("unexpected Admin UI image reference")
    source = run("git", "-C", str(root), "rev-parse", "--verify", f"{TAG.fullmatch(tag)[1]}^{{commit}}")
    if not source.startswith(TAG.fullmatch(tag)[1]):
        raise ValueError("tag source is ambiguous or unavailable")
    run("git", "-C", str(root), "merge-base", "--is-ancestor", source, "HEAD")
    registry, repository = image.split("/", 1)
    tag_digest = run("aws", "ecr", "describe-images", "--repository-name", repository,
                     "--image-ids", f"imageTag={tag}", "--query",
                     "imageDetails[0].imageDigest", "--output", "text")
    if not DIGEST.fullmatch(tag_digest):
        raise ValueError("registry did not return an image digest")
    password = run("aws", "ecr", "get-login-password")
    subprocess.run(["docker", "login", "--username", "AWS", "--password-stdin", registry],
                   input=password, text=True, check=True, capture_output=True)
    verified = json.loads(run("gh", "attestation", "verify", f"oci://{image}@{tag_digest}", "--repo", repo,
        "--signer-workflow", f"{repo}/.github/workflows/admin-ui-deploy.yml",
        "--source-digest", source, "--predicate-type",
        "https://github.com/JiRaska/open-bank-oss/attestations/admin-ui-build-inputs/v1", "--format", "json"))
    for item in verified:
        record = item.get("verificationResult", {}).get("statement", {}).get("predicate")
        if not isinstance(record, dict):
            continue
        try:
            context = json.dumps(record["contextManifest"], sort_keys=True, separators=(",", ":")).encode() + b"\n"
            if validate(record, context, image, tag) != source:
                continue
            check_tag_digest(record, tag_digest)
        except (KeyError, TypeError, ValueError):
            continue
        if attestation_matches(record, [item]):
            if materialized_context_matches(record, root):
                return
    raise ValueError("no verified digest-bound attestation contains the image's frozen inputs")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument("--repo", required=True)
    parser.add_argument("--image", required=True)
    parser.add_argument("--tag", required=True)
    args = parser.parse_args()
    verify(args.root, args.repo, args.image, args.tag)
    print("verified signed Admin UI build inputs")


if __name__ == "__main__":
    main()
