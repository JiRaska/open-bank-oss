#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""
Name a Docker-registry fetch failure as such, instead of leaving it disguised as a test bug.

THE PROBLEM THIS SOLVES
-----------------------
`_service-ci.yml` runs every PR build on `ubuntu-latest` (only the main-push lane gets the
self-hosted pool). The "Pre-warm Testcontainers image cache via ECR" step is gated on
`runner.environment == 'self-hosted'`, so on the PR lane it is skipped entirely: there is no
ECR pull-through cache and no in-cluster registry-cache, and Testcontainers pulls
postgres/valkey/redpanda/apicurio straight from Docker Hub. When Docker Hub is slow or
rate-limits the shared hosted-runner egress IP, the pull times out.

What the reader then sees at the top of the log is:

    LedgerPactProviderVerificationTest > ... FAILED
    LedgerSchedulerVertxContextIT > the scheduled FX revaluation reaches its use case() FAILED

Two named test failures. The actual cause sits ~900 lines further down, as a
`ContainerFetchException` wrapped in four layers of Quarkus test-resource plumbing. Measured on
run 30769096077 (job 91553216566, PR #3515): the registry error appears 32 times in that log and
the reader has no reason to scroll to it, because the summary already offered a plausible and
completely wrong explanation. The failure is MISDIAGNOSED BY CONSTRUCTION — it is not that the
signal is missing, it is that a more legible and incorrect signal is printed above it.

This script reads the JUnit XML that Gradle writes for the failing tests and, if the stack traces
carry a registry-fetch signature, prints a `::error` naming Docker Hub and recommending a re-run.

WHY THE JUnit XML AND NOT THE CONSOLE LOG
-----------------------------------------
Reading the XML needs no change to the "Build + test" step. The alternative — teeing the Gradle
output to a file — puts a pipe in the repo's single most load-bearing command, where a missing
`set -o pipefail` silently swallows the build's exit code (this repo has been bitten by
`gradlew | tail` doing exactly that). A post-step that only reads artifacts already on disk
cannot change the build verdict at all, which is the correct blast radius for a diagnostic.

The trade-off is deliberate and bounded: if the pull failure aborts the task before any JUnit XML
is written, this prints nothing and the job behaves exactly as it does today. It never makes the
diagnosis worse, and there is no case where it turns a green build red.

WHY THE SIGNATURES ARE WHAT THEY ARE
------------------------------------
`ContainerFetchException` is the discriminating one: Testcontainers raises it only when it cannot
obtain an image, so it cannot be confused with a container that started and then misbehaved. The
registry hostnames and `toomanyrequests` are corroborating. Deliberately NOT matched:
`ConditionTimeoutException` alone (awaitility is used all over the test suite for reasons that
have nothing to do with Docker) and `InternalServerErrorException` alone (a 500 from any
docker-java call, including ones a real bug could provoke).

Note the code-about-code hazard the repo documents: a checker that greps a text file matches
prose ABOUT the string as readily as the string itself, and in this direction — a false POSITIVE
that tells a reader to re-run a genuinely broken build — that is the expensive mistake. It is
avoided structurally here rather than by comment-stripping: the scan reads the `<failure>` and
`<error>` element TEXT out of parsed XML, never the raw file, so a test NAME or a source comment
mentioning `ContainerFetchException` cannot trigger it. `--self-test` asserts that specific
non-match.

Exit code is ALWAYS 0. This is a diagnostic, not a gate: it runs under `if: failure()` when the
job is already failing, and a non-zero exit here would only add a second red step saying nothing
new.

Usage:  check-testcontainers-registry-failure.py --service <name> [--self-test]
"""

from __future__ import annotations

import argparse
import pathlib
import sys
import tempfile
import xml.etree.ElementTree as ET

# The discriminating signature. Testcontainers raises this ONLY on a failure to obtain an image.
PRIMARY = "ContainerFetchException"

# Corroborating signatures — any one of these alongside PRIMARY sharpens the message, and each is
# also sufficient on its own because none of them can be produced by anything but a registry call.
CORROBORATING = (
    "registry-1.docker.io",
    "toomanyrequests",
    "You have reached your pull rate limit",
    "Retrying pull for image",
)


def failure_texts(xml_path: pathlib.Path) -> list[str]:
    """Return the text of every <failure>/<error> element, or [] if the file is unparseable.

    Parsing rather than grepping is what keeps a test name or a source comment mentioning
    `ContainerFetchException` from triggering a false positive.
    """
    try:
        root = ET.parse(xml_path).getroot()
    except (ET.ParseError, OSError):
        return []
    out = []
    for el in root.iter():
        if el.tag in ("failure", "error"):
            out.append((el.get("message") or "") + "\n" + (el.text or ""))
    return out


def scan(results_dir: pathlib.Path) -> tuple[bool, set[str]]:
    """(registry failure seen?, set of matched signatures) across every JUnit XML under the dir."""
    matched: set[str] = set()
    for xml_path in sorted(results_dir.rglob("*.xml")):
        for text in failure_texts(xml_path):
            if PRIMARY in text:
                matched.add(PRIMARY)
            for sig in CORROBORATING:
                if sig in text:
                    matched.add(sig)
    return (bool(matched), matched)


def self_test() -> int:
    """Prove BOTH directions: the signature is detected, and prose about it is not."""
    hit_xml = """<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="X"><testcase name="a" classname="X">
<failure message="boom">org.testcontainers.containers.ContainerFetchException at GenericContainer.java:1308
Status 500: {"message":"Get \\"https://registry-1.docker.io/v2/\\": context deadline exceeded"}</failure>
</testcase></testsuite>
"""
    # The false-positive control: the signature appears as a TEST NAME and as free text outside
    # any <failure>/<error> element — i.e. code about the code. It must NOT be reported.
    miss_xml = """<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="X">
<testcase name="reports a ContainerFetchException clearly" classname="X">
<failure message="expected true">java.lang.AssertionError at Foo.kt:42</failure>
</testcase>
<system-out>registry-1.docker.io was mentioned here in passing</system-out>
</testsuite>
"""
    ok = True
    with tempfile.TemporaryDirectory() as tmp:
        d = pathlib.Path(tmp)
        (d / "hit").mkdir()
        (d / "miss").mkdir()
        (d / "hit" / "TEST-a.xml").write_text(hit_xml)
        (d / "miss" / "TEST-b.xml").write_text(miss_xml)

        seen, sigs = scan(d / "hit")
        if not seen or PRIMARY not in sigs:
            print(f"SELF-TEST FAIL: registry failure not detected (matched={sorted(sigs)})")
            ok = False

        seen, sigs = scan(d / "miss")
        if seen:
            print(f"SELF-TEST FAIL: false positive on prose-about-the-code (matched={sorted(sigs)})")
            ok = False

    print("SELF-TEST PASS" if ok else "SELF-TEST FAILED")
    return 0 if ok else 1


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--service", help="module directory whose build/test-results is scanned")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()

    if args.self_test:
        return self_test()
    if not args.service:
        print("--service is required (or use --self-test)", file=sys.stderr)
        return 0

    results = pathlib.Path(args.service) / "build" / "test-results"
    if not results.is_dir():
        return 0

    seen, sigs = scan(results)
    if not seen:
        return 0

    print(
        "::error title=Build failed on a Docker registry pull, not on your code::"
        f"Testcontainers could not fetch an image for {args.service}. "
        "PR builds run on GitHub-hosted runners, which have no ECR pull-through cache, so images "
        "come straight from Docker Hub and a slow or rate-limited pull surfaces as ordinary test "
        "failures. The named tests above are almost certainly NOT the defect. "
        f"Signatures: {', '.join(sorted(sigs))}. Re-run the job before investigating."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
