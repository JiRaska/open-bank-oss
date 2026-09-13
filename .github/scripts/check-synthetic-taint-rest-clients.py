# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Keep synthetic taint propagation explicit across every MicroProfile REST client.

ADR-0252 needs a synthetic journey to stay identifiable as it crosses OpenBank services. A client
that silently omits ``SyntheticTaintClientFilter`` loses that marker before the next persistence or
event boundary. Conversely, putting the header on a public feed, an LLM endpoint or observability
backend leaks an internal banking marker beyond the trust boundary.

The source itself defines the inventory: each ``@RegisterRestClient`` interface must declare
exactly one of:

* ``@RegisterProvider(SyntheticTaintClientFilter::class)`` for an internal banking edge; or
* ``@SyntheticTaintExternalBoundary("reason")`` for an intentional external boundary.

There is no hand-maintained allow-list. A new client is red until its author makes the boundary
decision beside the actual endpoint declaration. This is a static ratchet, not a claim that taint
survives asynchronous outbox/Kafka hops; that separate, persistence-backed delivery remains #4348.
"""

from __future__ import annotations

import argparse
import pathlib
import re
import tempfile

CLIENT = re.compile(r"^\s*@RegisterRestClient(?:\([^\n]*\))?\s*$", re.MULTILINE)
NEXT_CLIENT = re.compile(r"^\s*@RegisterRestClient(?:\([^\n]*\))?\s*$", re.MULTILINE)
INTERFACE = re.compile(r"^\s*(?:public\s+)?interface\s+([A-Za-z_][A-Za-z0-9_]*)\b", re.MULTILINE)
PROPAGATES = re.compile(
    r"@(?:org\.eclipse\.microprofile\.rest\.client\.annotation\.)?RegisterProvider\(\s*"
    r"(?:com\.openbank\.libs\.web\.)?SyntheticTaintClientFilter::class\s*\)"
)
EXTERNAL = re.compile(
    r'@(?:com\.openbank\.libs\.web\.)?SyntheticTaintExternalBoundary\(\s*'
    r'"([^"\\]*(?:\\.[^"\\]*)*)"\s*\)'
)


def kotlin_sources(root: pathlib.Path) -> list[pathlib.Path]:
    return sorted(root.glob("openbank-*/src/main/**/*.kt"))


def scan_file(path: pathlib.Path) -> list[str]:
    text = path.read_text(encoding="utf-8")
    findings: list[str] = []
    for match in CLIENT.finditer(text):
        next_client = NEXT_CLIENT.search(text, match.end())
        end = next_client.start() if next_client else len(text)
        declaration = text[match.start() : end]
        interface = INTERFACE.search(declaration)
        if not interface:
            findings.append(f"{path}: @RegisterRestClient has no following interface declaration")
            continue
        name = interface.group(1)
        propagates = bool(PROPAGATES.search(declaration[: interface.start()]))
        external = EXTERNAL.search(declaration[: interface.start()])
        if propagates and external:
            findings.append(f"{path}:{name}: declares both propagation and an external boundary")
        elif not propagates and not external:
            findings.append(
                f"{path}:{name}: must register SyntheticTaintClientFilter or declare "
                "SyntheticTaintExternalBoundary"
            )
        elif external and not external.group(1).strip():
            findings.append(f"{path}:{name}: external boundary reason must not be blank")
    return findings


def check(root: pathlib.Path) -> tuple[list[str], int]:
    sources = kotlin_sources(root)
    findings: list[str] = []
    subjects = 0
    for source in sources:
        text = source.read_text(encoding="utf-8")
        subjects += len(CLIENT.findall(text))
        findings.extend(scan_file(source))
    if subjects == 0:
        findings.append("found no @RegisterRestClient declarations — scan cannot establish propagation")
    return findings, subjects


def write(root: pathlib.Path, rel: str, content: str) -> None:
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def self_test() -> int:
    cases = {
        "internal propagates": (
            (
                "@RegisterRestClient(configKey = \"ledger\")\n"
                "@RegisterProvider(SyntheticTaintClientFilter::class)\ninterface LedgerClient\n"
            ),
            False,
        ),
        "external has reason": (
            (
                "@RegisterRestClient\n"
                "@SyntheticTaintExternalBoundary(\"public endpoint\")\ninterface PublicClient\n"
            ),
            False,
        ),
        "fully qualified internal propagates": (
            (
                "@RegisterRestClient\n"
                "@org.eclipse.microprofile.rest.client.annotation.RegisterProvider(\n"
                "    com.openbank.libs.web.SyntheticTaintClientFilter::class\n)\n"
                "interface QualifiedInternalClient\n"
            ),
            False,
        ),
        "fully qualified external has reason": (
            (
                "@RegisterRestClient\n"
                "@com.openbank.libs.web.SyntheticTaintExternalBoundary(\"public endpoint\")\n"
                "interface QualifiedPublicClient\n"
            ),
            False,
        ),
        "missing decision": ("@RegisterRestClient\ninterface LostClient\n", True),
        "both decisions": (
            (
                "@RegisterRestClient\n@RegisterProvider(SyntheticTaintClientFilter::class)\n"
                "@SyntheticTaintExternalBoundary(\"wrong\")\ninterface AmbiguousClient\n"
            ),
            True,
        ),
        "blank reason": (
            "@RegisterRestClient\n@SyntheticTaintExternalBoundary(\"  \")\ninterface EmptyReasonClient\n",
            True,
        ),
    }
    failures: list[str] = []
    for label, (content, expect_finding) in cases.items():
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            write(root, "openbank-demo/src/main/kotlin/Demo.kt", content)
            findings, _ = check(root)
            if bool(findings) != expect_finding:
                failures.append(f"{label}: expected finding={expect_finding}, got {findings}")
    if failures:
        for failure in failures:
            print(f"::error::check-synthetic-taint-rest-clients self-test: {failure}")
        return 1
    print(f"check-synthetic-taint-rest-clients self-test: {len(cases)}/{len(cases)} passed")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=pathlib.Path, default=pathlib.Path(__file__).resolve().parents[2])
    parser.add_argument("--enforce", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    findings, subjects = check(args.root)
    # Keep the gate-runner contract deliberately dependency-free: this checker reads Kotlin only,
    # so importing the shared YAML cache would make a missing PyYAML installation look like an
    # unresolved REST-client inventory. run-gates.py consumes this exact marker for min_subjects.
    print(f"SUBJECTS={subjects}  # REST client interfaces examined")
    for finding in findings:
        print(f"::{ 'error' if args.enforce else 'warning' }::check-synthetic-taint-rest-clients: {finding}")
    if findings:
        print(f"check-synthetic-taint-rest-clients: {len(findings)} finding(s)")
        return 2 if args.enforce else 0
    print("check-synthetic-taint-rest-clients: OK — every REST client makes the taint boundary explicit.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
