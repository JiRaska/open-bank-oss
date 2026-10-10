#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Verify the signed, digest-bound Admin UI build record for a proposed image tag."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
from pathlib import Path

TAG = re.compile(r"sandbox-([0-9a-f]{8,40})(?:-run[0-9]+)?")
DIGEST = re.compile(r"sha256:[0-9a-f]{64}")
ECR_REGISTRY = re.compile(
    r"(?P<account>[0-9]{12})\.dkr\.ecr\.(?P<region>[a-z0-9-]+)\.amazonaws\.com(?:\.cn)?"
)


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
    registry_match = ECR_REGISTRY.fullmatch(trusted_registry)
    if not registry_match:
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
    tag_digest = run("aws", "ecr", "describe-images",
                     "--registry-id", registry_match["account"], "--region", registry_match["region"],
                     "--repository-name", repository,
                     "--image-ids", f"imageTag={tag}", "--query",
                     "imageDetails[0].imageDigest", "--output", "text")
    if not DIGEST.fullmatch(tag_digest):
        raise ValueError("registry did not return an image digest")
    password = run("aws", "ecr", "get-login-password", "--region", registry_match["region"])
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
