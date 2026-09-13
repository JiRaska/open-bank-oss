#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Keep cloud credentials out of the reusable PR build lane.

``_service-ci.yml``'s ``build`` job runs code checked out from a pull request.
It may build arbitrary Gradle plugins, test fixtures and containers, so an OIDC role intended
for deploys or self-hosted infrastructure would become an exfiltrable credential there. The
separate ``contract`` job is not covered: it runs only after a trusted main push on the dedicated
self-hosted runner and has its own audited need for ECR cache access. The build job also must
not receive OIDC write permission: without that restriction untrusted code could mint a token
even if it never calls an AWS helper action.

This narrow ownership boundary deliberately does not attempt to classify every GitHub workflow
or every safe read-only role. It protects the one reusable PR lane that executes every changed
service, and fails closed if it gains a cloud-login action, role assumption, ECR login command,
or an OIDC token permission.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

WORKFLOW = Path(".github/workflows/_service-ci.yml")
BUILD_BLOCK = re.compile(
    r"^  build:\n(?P<body>.*?)(?=^  [A-Za-z][A-Za-z0-9_-]*:|\Z)",
    re.MULTILINE | re.DOTALL,
)
FORBIDDEN = (
    "aws-actions/configure-aws-credentials@",
    "aws-actions/amazon-ecr-login@",
    "role-to-assume:",
    "aws ecr get-login-password",
    "id-token: write",
)


def findings(workflow: str) -> list[str]:
    match = BUILD_BLOCK.search(workflow)
    if not match:
        return ["could not locate jobs.build in .github/workflows/_service-ci.yml"]
    body = match.group("body")
    return [token for token in FORBIDDEN if token in body]


def self_test() -> int:
    trusted_contract = """jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - run: ./gradlew :service:build
  contract:
    runs-on: openbank-build
    steps:
      - uses: aws-actions/configure-aws-credentials@deadbeef
        with:
          role-to-assume: arn:aws:iam::123:role/trusted
"""
    unsafe_build = trusted_contract.replace(
        "- run: ./gradlew :service:build",
        "- uses: aws-actions/configure-aws-credentials@deadbeef",
    )
    role_only_build = trusted_contract.replace(
        "- run: ./gradlew :service:build",
        "- run: echo role-to-assume: arn:aws:iam::123:role/unsafe",
    )
    oidc_build = trusted_contract.replace(
        "- run: ./gradlew :service:build",
        "permissions:\n      contents: read\n      id-token: write",
    )
    missing_build = "jobs:\n  contract:\n    runs-on: openbank-build\n"
    cases = (
        ("trusted contract lane is outside the guard", trusted_contract, []),
        (
            "cloud login in PR build is rejected",
            unsafe_build,
            ["aws-actions/configure-aws-credentials@"],
        ),
        (
            "role assumption command in PR build is rejected",
            role_only_build,
            ["role-to-assume:"],
        ),
        (
            "OIDC write permission in PR build is rejected",
            oidc_build,
            ["id-token: write"],
        ),
        (
            "missing build job fails closed",
            missing_build,
            ["could not locate jobs.build in .github/workflows/_service-ci.yml"],
        ),
    )
    failed = 0
    for name, source, expected in cases:
        actual = findings(source)
        ok = actual == expected
        print(f"  {'PASS' if ok else 'FAIL'} {name}: {actual}")
        failed += not ok
    return int(failed > 0)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    try:
        source = WORKFLOW.read_text(encoding="utf-8")
    except OSError as exc:
        print(f"::error file={WORKFLOW}::could not read workflow: {exc}")
        return 1
    bad = findings(source)
    if bad:
        for token in bad:
            print(
                f"::error file={WORKFLOW}::PR build job may not contain `{token}`. "
                "It executes pull-request code; use a trusted post-merge lane or a separately "
                "reviewed read-only architecture instead."
            )
        return 1
    print("check-pr-build-cloud-credentials: build lane is credential-free")
    return 0


if __name__ == "__main__":
    sys.exit(main())
