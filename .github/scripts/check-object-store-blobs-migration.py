#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""A service that uses the shared object store must create the table it reads.

Why this exists
---------------
`ObjectStoreBlobEntity` (`openbank-libs-runtime`, `PostgresBlobStore`) maps `object_store_blobs`,
and `openbank-libs-runtime` carries the Jandex plugin — so the entity is visible to the Hibernate
entity scan of **every** module that depends on it, whether or not that module uses blob storage.
Measured: 37 modules depend on libs-runtime and use Hibernate; exactly ONE
(`openbank-document-service`) has a migration creating the table.

That 36-way gap is BY DESIGN and this check does not flag it. `PostgresBlobStore`'s own KDoc says
so: the entity is visible regardless, "every query against it simply fails until the service's own
migration has created the table". Under `quarkus.hibernate-orm.database.generation: none` — what
production runs — nothing validates the schema, so an unused entity is inert. That is why #3081's
`missing table [object_store_blobs]` finding appeared for sepa-payment: the fuzz harness (#3039)
turns validation ON, which the deployed configuration never does.

What is NOT inert is a service that actually INJECTS the port and has no table. That fails at
first use — a runtime 500 on whichever request first stores or reads a blob, with nothing before
it to say so. This check is exactly that pairing, and nothing else.

WHAT IT CHECKS: a module whose `src/main` references `ObjectStorePort` **and resolves to the
Postgres backend** must have a Flyway migration creating `object_store_blobs`.

The backend selector matters and this check learned it the hard way — its first run flagged
`openbank-customer-edge`, which injects the port for ADR-0192 feedback screenshots and sets
`openbank.objectstore.backend: s3`. It never touches the table. `PostgresBlobStore` carries
`@IfBuildProperty(name = "openbank.objectstore.backend", stringValue = "postgres",
enableIfMissing = true)`, so the Postgres adapter is what you get when the key is ABSENT or set to
`postgres` — an omission is the risky case, which is why absence counts as "postgres" here.

Usage:  check-object-store-blobs-migration.py [--enforce] [--selftest]
Advisory by default (prints ::warning, exits 0) per the repo convention; --enforce fails the build.
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

import yaml

REPO = pathlib.Path(__file__).resolve().parents[2]
USERS = ("ObjectStorePort", "PostgresBlobStore")
TABLE = "object_store_blobs"
CREATE_TABLE_RE = re.compile(
    rf"create\s+table\s+(?:if\s+not\s+exists\s+)?(?:[a-z0-9_]+\.)?{TABLE}\b",
    re.IGNORECASE,
)


def uses_object_store(module: pathlib.Path) -> bool:
    main = module / "src" / "main"
    if not main.is_dir():
        return False
    for path in main.rglob("*.kt"):
        try:
            text = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        if any(marker in text for marker in USERS):
            return True
    return False


def uses_postgres_backend(module: pathlib.Path) -> bool:
    """True when the Postgres adapter is the one this module builds with.

    `enableIfMissing = true` on PostgresBlobStore means an ABSENT key selects Postgres, so the
    default here must be True — the omission is exactly the case that breaks.
    """
    config = module / "src" / "main" / "resources" / "application.yaml"
    if not config.is_file():
        return True
    try:
        doc = yaml.safe_load(config.read_text(encoding="utf-8")) or {}
    except yaml.YAMLError:
        return True
    backend = ((doc.get("openbank") or {}).get("objectstore") or {}).get("backend")
    if backend is None:
        return True
    # `${OBJECTSTORE_BACKEND:postgres}` resolves to its default at BUILD time, which is what
    # @IfBuildProperty reads — so the default inside the expression is the deciding value.
    text = str(backend)
    if "${" in text:
        text = text.split(":", 1)[1].rstrip("}") if ":" in text else "postgres"
    return text.strip() == "postgres"


def creates_table(module: pathlib.Path) -> bool:
    migrations = module / "src" / "main" / "resources" / "db" / "migration"
    if not migrations.is_dir():
        return False
    for path in migrations.glob("*.sql"):
        try:
            text = path.read_text(encoding="utf-8").lower()
        except (UnicodeDecodeError, OSError):
            continue
        # The table name must appear in a CREATE TABLE STATEMENT, not merely somewhere in the
        # file. document-service's V1 carries a comment naming the table ("Object-store blob
        # backing table lives in V4…") and separately creates other tables, so a whole-file
        # "create table" AND "object_store_blobs" test reports V1 as the creator — which made the
        # first version of this check unfalsifiable: deleting the real V4 still read as clean.
        if CREATE_TABLE_RE.search(text):
            return True
    return False


def modules() -> list[pathlib.Path]:
    return sorted(p for p in REPO.glob("openbank-*") if (p / "build.gradle.kts").is_file())


def findings() -> tuple[list[str], int]:
    messages: list[str] = []
    users = 0
    for module in modules():
        # The library that DEFINES the port and the entity is not a consumer of it.
        if module.name in ("openbank-libs-runtime", "openbank-libs-domain"):
            continue
        if not uses_object_store(module):
            continue
        if not uses_postgres_backend(module):
            print(f"::notice::{module.name} uses the object store with a non-Postgres backend — "
                  f"it never reads {TABLE}.")
            continue
        users += 1
        if not creates_table(module):
            messages.append(
                f"::error file={module.name}/build.gradle.kts::{module.name} uses the shared object "
                f"store (ObjectStorePort / PostgresBlobStore) but no Flyway migration creates "
                f"`{TABLE}`. The entity is visible via the Jandex-indexed libs-runtime whether or "
                f"not the table exists, so this does not fail at boot — it fails on the first "
                f"request that stores or reads a blob (#3081).",
            )
    return messages, users


def selftest() -> int:
    """Feed both halves of the pairing inputs they MUST and must NOT flag."""
    all_modules = modules()
    if len(all_modules) < 20:
        print(f"selftest FAIL: only {len(all_modules)} module(s) found — the scan is broken.")
        return 1

    doc = REPO / "openbank-document-service"
    if not doc.is_dir():
        print("selftest FAIL: openbank-document-service is missing — the known-good case is gone.")
        return 1
    if not creates_table(doc):
        print("selftest FAIL: document-service's object_store_blobs migration was not detected — "
              "the migration scan cannot see a table it must see.")
        return 1

    # A module that neither uses nor creates must not be flagged, and a fabricated "uses without
    # creates" pair must be, or the rule is vacuous.
    # The rule is only meaningful if document-service is DETECTED as a user — the first version
    # of this check passed its self-test while failing to flag the one real case.
    if not uses_object_store(doc):
        print("selftest FAIL: document-service is not detected as an object-store user, so the "
              "rule is vacuous for the only module it can apply to.")
        return 1
    # And the creator test must be statement-scoped: V1 NAMES the table in a comment while
    # creating others, so a whole-file match reports V1 as the creator and the check can never
    # go red.
    v1 = doc / "src/main/resources/db/migration/V1__create_document_templates_and_documents.sql"
    if v1.is_file() and CREATE_TABLE_RE.search(v1.read_text(encoding="utf-8")):
        print("selftest FAIL: V1 reads as creating object_store_blobs; it only mentions it.")
        return 1

    if creates_table(REPO / "openbank-ledger-service"):
        print("selftest FAIL: ledger-service reported as creating the table; it does not.")
        return 1

    # The backend selector is the half that produced this check's only false positive, so both
    # verdicts are pinned against the real files rather than a fixture.
    if not uses_postgres_backend(doc):
        print("selftest FAIL: document-service reads as non-Postgres; it defaults to postgres.")
        return 1
    edge = REPO / "openbank-customer-edge"
    if edge.is_dir() and uses_postgres_backend(edge):
        print("selftest FAIL: customer-edge reads as Postgres; it sets backend: s3, and treating "
              "it as a user is exactly the false positive this check exists past.")
        return 1

    print(f"selftest OK: {len(all_modules)} modules scanned; document-service's migration and "
          f"postgres default are detected, customer-edge's s3 backend exempts it, and "
          f"ledger-service's absent migration is not mistaken for one.")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--selftest", action="store_true", help="verify the check can fail")
    args = ap.parse_args()
    if args.selftest:
        return selftest()

    messages, users = findings()
    for line in messages:
        print(line if args.enforce else line.replace("::error", "::warning", 1))
    verdict = "clean." if not messages else f"{len(messages)} finding(s) above."
    print(f"check-object-store-blobs-migration: {users} object-store user(s) — {verdict}")
    return 1 if messages and args.enforce else 0


if __name__ == "__main__":
    sys.exit(main())
