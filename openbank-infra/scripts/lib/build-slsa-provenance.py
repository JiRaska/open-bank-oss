#!/usr/bin/env python3
"""Build a SLSA provenance v0.2 predicate for a GitHub Actions image build (ADR-0030 D4, #8590 #14).

The deploy lane (auto-deploy.yml) already signs every image and attests its CycloneDX SBOM with the
KMS cosign key; what it did NOT attest is *how the image came to be* — which repository, commit,
workflow and run produced it. That is the SLSA build-provenance claim, and without it the admission
chain can prove "this image is ours and we know its contents" but not "this image was built by OUR
CI from THIS commit". ghcr-publish.yml carries the Sigstore keyless variant for the standalone
distribution lane; this builder is the ECR/KMS twin for the deploy lane.

The predicate is consumed two ways:

  1. cosign attest --type slsaprovenance --predicate <file> (cosign-attest.sh), and
  2. kyverno's verify-openbank-image-slsa-provenance ClusterPolicy, which reads the predicate via
     JMESPath at admission and pins invocation.configSource.uri / buildType.

Deliberate honesty in the output (a predicate that overclaims is worse than none):

  - completeness.materials is FALSE: we attest the git source material only, not the resolved
    base-image and GitHub Action dependency tree (that is what the reusable-workflow generator
    would give us; a plain job cannot enumerate it truthfully).
  - completeness.parameters/environment are FALSE for the same reason: GITHUB_* env is the
    invocation record we can stand behind, not a complete one.
  - reproducible is FALSE: the Dockerfile builds are not reproducible and claiming otherwise
    would be a false claim sitting inside a security attestation.

Usage:
  build-slsa-provenance.py --output predicate.json     # reads GITHUB_* env
  build-slsa-provenance.py --self-test                 # fixed-input golden check, exits non-zero on drift
"""
from __future__ import annotations

import argparse
import json
import os
import sys

REPO_URL = "git+https://github.com/JiRaska/open-bank-oss"
BUILDTYPE = "https://openbank.dev/buildtypes/github-actions-docker-buildx/v1"
# Matches the kyverno policy's expectations and ghcr-publish.yml's Sigstore lane vocabulary.
BUILDER_ID = "https://github.com/JiRaska/open-bank-oss/attestations/github-actions-hosted-runner"


def build_predicate(env: dict[str, str]) -> dict:
    """Assemble the predicate from GitHub Actions env. Raises SystemExit on a missing required var."""
    required = [
        "GITHUB_SERVER_URL",
        "GITHUB_REPOSITORY",
        "GITHUB_SHA",
        "GITHUB_REF",
        "GITHUB_WORKFLOW_REF",
        "GITHUB_RUN_ID",
        "GITHUB_RUN_ATTEMPT",
        "GITHUB_EVENT_NAME",
    ]
    missing = [k for k in required if not env.get(k)]
    if missing:
        raise SystemExit(f"build-slsa-provenance: missing required env: {', '.join(missing)}")

    repo = env["GITHUB_REPOSITORY"]
    server = env["GITHUB_SERVER_URL"].rstrip("/")
    # GITHUB_WORKFLOW_REF is "<repo>/<workflow-path>@<ref>"; entryPoint is the path between.
    workflow_ref = env["GITHUB_WORKFLOW_REF"]
    try:
        entry_point = workflow_ref[len(repo) + 1:].split("@", 1)[0]
    except (IndexError, ValueError):
        raise SystemExit(f"build-slsa-provenance: unparseable GITHUB_WORKFLOW_REF: {workflow_ref!r}") from None
    if not entry_point.endswith((".yml", ".yaml")):
        raise SystemExit(f"build-slsa-provenance: entryPoint does not look like a workflow: {entry_point!r}")

    uri = f"git+{server}/{repo}"
    run_url = (
        f"{server}/{repo}/actions/runs/{env['GITHUB_RUN_ID']}"
        f"/attempts/{env['GITHUB_RUN_ATTEMPT']}"
    )

    return {
        "builder": {"id": BUILDER_ID},
        "buildType": BUILDTYPE,
        "invocation": {
            "configSource": {
                "uri": uri,
                "digest": {"sha1": env["GITHUB_SHA"]},
                "entryPoint": entry_point,
            },
            "parameters": {
                "ref": env["GITHUB_REF"],
                "event_name": env["GITHUB_EVENT_NAME"],
            },
        },
        "materials": [{"uri": uri, "digest": {"sha1": env["GITHUB_SHA"]}}],
        "metadata": {
            # The run URL doubles as the unique-per-run identity the post-attest verify binds to —
            # the same role the CycloneDX serialNumber plays for the SBOM attestation (an append-
            # only .att tag means any-match verify alone cannot prove THIS run's envelope landed).
            "buildInvocationId": run_url,
            "completeness": {"parameters": False, "environment": False, "materials": False},
            "reproducible": False,
        },
    }


def self_test() -> int:
    env = {
        "GITHUB_SERVER_URL": "https://github.com",
        "GITHUB_REPOSITORY": "JiRaska/open-bank-oss",
        "GITHUB_SHA": "0123456789abcdef0123456789abcdef01234567",
        "GITHUB_REF": "refs/heads/main",
        "GITHUB_WORKFLOW_REF": "JiRaska/open-bank-oss/.github/workflows/auto-deploy.yml@refs/heads/main",
        "GITHUB_RUN_ID": "33964441963",
        "GITHUB_RUN_ATTEMPT": "1",
        "GITHUB_EVENT_NAME": "push",
    }
    pred = build_predicate(env)
    checks = [
        (pred["buildType"] == BUILDTYPE, "buildType"),
        (pred["builder"]["id"] == BUILDER_ID, "builder.id"),
        (pred["invocation"]["configSource"]["uri"] == "git+https://github.com/JiRaska/open-bank-oss", "configSource.uri"),
        (pred["invocation"]["configSource"]["entryPoint"] == ".github/workflows/auto-deploy.yml", "entryPoint"),
        (pred["invocation"]["configSource"]["digest"]["sha1"] == env["GITHUB_SHA"], "configSource digest"),
        (pred["materials"][0]["digest"]["sha1"] == env["GITHUB_SHA"], "materials digest"),
        (
            pred["metadata"]["buildInvocationId"]
            == "https://github.com/JiRaska/open-bank-oss/actions/runs/33964441963/attempts/1",
            "buildInvocationId",
        ),
        (pred["metadata"]["completeness"] == {"parameters": False, "environment": False, "materials": False}, "completeness honest"),
        (pred["metadata"]["reproducible"] is False, "reproducible honest"),
        (set(pred.keys()) == {"builder", "buildType", "invocation", "materials", "metadata"}, "slsa v0.2 field set"),
    ]
    failed = [name for ok, name in checks if not ok]
    # A missing-var environment must fail loudly, never produce a partial predicate.
    try:
        build_predicate({})
        failed.append("missing-env must raise")
    except SystemExit:
        pass
    if failed:
        print(f"SELF-TEST FAILED: {', '.join(failed)}")
        return 1
    print("build-slsa-provenance self-test: PASS (11 checks)")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--output", help="write the predicate JSON here (default: stdout)")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    pred = build_predicate(os.environ)
    text = json.dumps(pred, indent=2, sort_keys=True) + "\n"
    if args.output:
        with open(args.output, "w", encoding="utf-8") as fh:
            fh.write(text)
        print(f"predicate written to {args.output}", file=sys.stderr)
    else:
        sys.stdout.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
