#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Keep ADR-0252 synthetic origin intact at shared transactional-outbox boundaries.

REST propagation alone is insufficient: a journey can arrive with ``X-OpenBank-Synthetic: true``
and lose that fact when an asynchronous event is persisted.  The common outbox contract represents
that fact as ``OutboxMessage.synthetic``.  A service using ``PanacheOutboxEntity`` must therefore
make two independently verifiable commitments:

* a Flyway migration adds a non-null ``synthetic`` column with a safe false default; and
* an outbox writer that accepts ``OutboxMessage`` copies the message field into the persisted
  entity.

This deliberately scopes itself to the shared Panache outbox.  Agent audit rows and other bespoke
tables are not instances of that contract, and treating their similarly named tables as evidence
would turn an unrelated persistence mechanism into a false pass.
"""

from __future__ import annotations

import argparse
import pathlib
import re
import tempfile

ENTITY = re.compile(r"class\s+\w*OutboxEntity\s*:\s*PanacheOutboxEntity\s*\(")
MIGRATION = re.compile(
    r"ALTER\s+TABLE\s+\w+_outbox\s+ADD\s+COLUMN\s+synthetic\s+BOOLEAN\s+NOT\s+NULL\s+DEFAULT\s+FALSE\s*;",
    re.IGNORECASE,
)
# The extension mapper idiom exposes OutboxMessage.synthetic as the unqualified receiver property;
# other writers call the parameter ``message`` or ``event``.  A literal false is intentionally not
# accepted: it records an explicit non-synthetic event, but cannot preserve a synthetic one.
PROPAGATES = re.compile(r"\bsynthetic\s*=\s*(?:synthetic|message\.synthetic|event\.synthetic)\b")


def files(path: pathlib.Path, pattern: str) -> list[pathlib.Path]:
    return sorted(path.glob(pattern))


def strip_non_code(source: str) -> str:
    """Remove Kotlin comments and strings before looking for a persistence operation.

    A KDoc which correctly describes ``OutboxMessage.synthetic`` is not a writer. Neither is a
    SQL/log string mentioning it. Kotlin block comments nest, so a regex for comments would leave
    an inner tail live and let prose satisfy the gate.
    """
    source = re.sub(r'"""[\s\S]*?"""', '""', source)
    source = re.sub(r'"(?:\\.|[^"\\])*"', '""', source)
    source = re.sub(r"//[^\n]*", "", source)
    result: list[str] = []
    depth = index = 0
    while index < len(source):
        if source.startswith("/*", index):
            depth += 1
            index += 2
            continue
        if source.startswith("*/", index) and depth:
            depth -= 1
            index += 2
            continue
        if not depth:
            result.append(source[index])
        index += 1
    return "".join(result)


def service_findings(service: pathlib.Path) -> tuple[list[str], int]:
    kotlin = {
        path: strip_non_code(path.read_text(encoding="utf-8"))
        for path in files(service, "src/main/**/*.kt")
    }
    entities = [path for path, source in kotlin.items() if ENTITY.search(source)]
    if not entities:
        return [], 0

    migrations = files(service, "src/main/resources/db/migration/*__synthetic_outbox_taint.sql")
    migration_ok = any(MIGRATION.search(p.read_text(encoding="utf-8")) for p in migrations)
    # Some services own an outbox for an internally-derived event and have no OutboxMessage input
    # at all. A literal ``synthetic = false`` there is not a dropped marker: there is no marker at
    # this boundary to receive. Do not turn that distinct design decision into a false failure.
    uses_message_contract = any("OutboxMessage" in source for source in kotlin.values())
    propagates = any(PROPAGATES.search(source) for source in kotlin.values())
    findings: list[str] = []
    if not migration_ok:
        findings.append(
            f"{service.name}: shared outbox entity has no synthetic taint Flyway migration "
            "(ADD COLUMN synthetic BOOLEAN NOT NULL DEFAULT FALSE)"
        )
    if uses_message_contract and not propagates:
        findings.append(
            f"{service.name}: shared outbox entity has no writer copying OutboxMessage.synthetic"
        )
    return findings, len(entities)


def check(root: pathlib.Path) -> tuple[list[str], int]:
    findings: list[str] = []
    subjects = 0
    for service in sorted(root.glob("openbank-*-service")):
        service_findings_result, entity_count = service_findings(service)
        findings.extend(service_findings_result)
        subjects += entity_count
    if subjects == 0:
        findings.append("found no PanacheOutboxEntity implementations — cannot establish taint persistence")
    return findings, subjects


def write(root: pathlib.Path, rel: str, content: str) -> None:
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def fixture(migration: str, writer: str) -> tuple[list[str], int]:
    with tempfile.TemporaryDirectory() as temp:
        root = pathlib.Path(temp)
        write(root, "openbank-demo-service/src/main/kotlin/Outbox.kt", "class DemoOutboxEntity : PanacheOutboxEntity()\n" + writer)
        if migration:
            write(root, "openbank-demo-service/src/main/resources/db/migration/V1__synthetic_outbox_taint.sql", migration)
        return check(root)


def self_test() -> int:
    valid_migration = "ALTER TABLE demo_outbox ADD COLUMN synthetic BOOLEAN NOT NULL DEFAULT FALSE;"
    cases = {
        "valid extension mapper": (valid_migration, "fun OutboxMessage.toEntity() { synthetic = synthetic }", False),
        "valid parameter mapper": (valid_migration, "fun map(message: OutboxMessage) { synthetic = message.synthetic }", False),
        "missing migration": ("", "fun OutboxMessage.toEntity() { synthetic = synthetic }", True),
        "wrong migration default": ("ALTER TABLE demo_outbox ADD COLUMN synthetic BOOLEAN NOT NULL DEFAULT TRUE;", "fun OutboxMessage.toEntity() { synthetic = synthetic }", True),
        "missing propagation": (valid_migration, "fun OutboxMessage.toEntity() { synthetic = false }", True),
        "internally derived event has no marker to drop": (valid_migration, "fun write() { synthetic = false }", False),
        "KDoc mapping does not count": (valid_migration, "fun map(message: OutboxMessage) { /** synthetic = message.synthetic */ synthetic = false }", True),
        "string mapping does not count": (valid_migration, "fun map(message: OutboxMessage) { val description = \"synthetic = message.synthetic\"; synthetic = false }", True),
        "empty shared-outbox corpus fails closed": ("", "class AgentAuditOutbox { }", True),
    }
    failures: list[str] = []
    for label, (migration, writer, expect_finding) in cases.items():
        if label == "empty shared-outbox corpus fails closed":
            with tempfile.TemporaryDirectory() as temp:
                root = pathlib.Path(temp)
                write(root, "openbank-demo-service/src/main/kotlin/Audit.kt", writer)
                findings, subjects = check(root)
            got = bool(findings) and subjects == 0
        else:
            findings, _ = fixture(migration, writer)
            got = bool(findings)
        if got != expect_finding:
            failures.append(f"{label}: expected finding={expect_finding}, got {findings}")
    if failures:
        for failure in failures:
            print(f"::error::check-synthetic-outbox-taint self-test: {failure}")
        return 1
    print(f"check-synthetic-outbox-taint self-test: {len(cases)}/{len(cases)} passed")
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
    print(f"SUBJECTS={subjects}  # shared Panache outbox entity implementations examined")
    for finding in findings:
        print(f"::{ 'error' if args.enforce else 'warning' }::check-synthetic-outbox-taint: {finding}")
    if findings:
        return 2 if args.enforce else 0
    print("check-synthetic-outbox-taint: OK — shared OutboxMessage persistence preserves synthetic origin.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
