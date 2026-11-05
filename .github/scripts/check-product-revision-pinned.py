#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
"""ADR-0341 D1 `product-revision-pinned`: a service that opens product-bearing contracts persists the
published catalog revision each contract was opened under.

WHAT IT CHECKS (rules.yaml: product_bearing_services)
    For every service listed under `services` with `adoption: pinned`, three STRUCTURAL facts, each
    decidable from the source without running anything:

      1. MIGRATION — some Flyway migration of the service gives the contract table the pin column
         (`CREATE TABLE <table> (... <pin_column> ...)` or `ALTER TABLE <table> ... ADD COLUMN
         <pin_column>`). SQL comments are stripped first, so a column named in a comment is nothing.
      2. ENTITY — the JPA entity mapped to that table (`@Table(name = "<table>")`) maps the column by
         its explicit name (`@Column(name = "<pin_column>"` or the `@field:Column` use-site form), in
         the class body that follows that `@Table`, not anywhere in the file.
      3. CREATION PATH — inside the declared creation function, the aggregate is constructed with the
         pin argument bound to something other than `null` (`<Aggregate>(... <pin_argument> = x ...)`).
         Kotlin comments are stripped and the function body is found by brace matching, so a token in a
         KDoc, or the same call in another function, does not satisfy it.

    `adoption: pending` entries are product-bearing services that have not adopted the pin yet
    (ADR-0341 D5 adopts one service per change). They are REPORTED, never failed: the list grows with
    the adoption, and moving an entry to `pinned` is what arms the three checks for it.

    The LIST IS HELD TO THE CODE, both directions: every service whose migrations give a table a
    catalog product reference column (`product_id`, `product_code`, `catalog_offering_id`,
    `offering_id`) must be listed, either under `services` or under `not_product_bearing` with a
    reason; and an entry under either that no longer has any such column is reported as stale. A new
    product-bearing service therefore cannot stay invisible to this gate by not being added to it.

WHAT IT DOES NOT CHECK
    That a pin is ever READ, or that terms come from the pinned revision only (ADR-0341 D2); that a
    pre-existing contract was backfilled; nor the ADR's GL-code half. It is the pin rule only.

SUBJECTS=<number of services under product_bearing_services.services>.
MODES: advisory (::warning, exit 0) unless --enforce (exit 1 on any finding).
Usage: check-product-revision-pinned.py [--root DIR] [--enforce] | --self-test
"""

from __future__ import annotations

import argparse
import contextlib
import io
import pathlib
import re
import sys
import tempfile
import textwrap

try:
    import yaml
except ImportError:
    sys.stderr.write("PyYAML required: pip install pyyaml\n")
    sys.exit(2)

RULES = "openbank-libs/governance/rules.yaml"
RULE_KEY = "product_bearing_services"
MIGRATIONS = "src/main/resources/db/migration"
PRODUCT_REFERENCE_COLUMNS = ("product_id", "product_code", "catalog_offering_id", "offering_id")
ADOPTIONS = {"pinned", "pending"}


# ---------------------------------------------------------------------------------------------- text


def strip_sql_comments(sql: str) -> str:
    """SQL with `--` line comments and `/* */` block comments blanked; string literals kept."""
    out, i, n = [], 0, len(sql)
    in_str = False
    while i < n:
        c, two = sql[i], sql[i:i + 2]
        if in_str:
            out.append(c)
            if c == "'":
                in_str = False
            i += 1
        elif c == "'":
            in_str = True
            out.append(c)
            i += 1
        elif two == "--":
            while i < n and sql[i] != "\n":
                i += 1
        elif two == "/*":
            end = sql.find("*/", i + 2)
            i = n if end < 0 else end + 2
        else:
            out.append(c)
            i += 1
    return "".join(out)


def strip_kotlin_comments(text: str) -> str:
    """Kotlin with comments blanked and string literals kept. Block comments nest in Kotlin."""
    out: list[str] = []
    i, depth, n = 0, 0, len(text)
    in_str = False
    while i < n:
        two = text[i:i + 2]
        if depth == 0 and not in_str and two == "//":
            while i < n and text[i] != "\n":
                i += 1
            continue
        if not in_str and two == "/*":
            depth += 1
            i += 2
            continue
        if depth > 0:
            if two == "*/":
                depth -= 1
                i += 2
                continue
            if text[i] == "\n":
                out.append("\n")
            i += 1
            continue
        c = text[i]
        if c == '"' and (i == 0 or text[i - 1] != "\\"):
            in_str = not in_str
        out.append(c)
        i += 1
    return "".join(out)


def balanced(text: str, start: int, open_ch: str, close_ch: str) -> str | None:
    """The text from `start` (an `open_ch`) to its matching `close_ch`, inclusive; None if unbalanced."""
    depth = 0
    for j in range(start, len(text)):
        if text[j] == open_ch:
            depth += 1
        elif text[j] == close_ch:
            depth -= 1
            if depth == 0:
                return text[start:j + 1]
    return None


# --------------------------------------------------------------------------------------- the checks


def migration_sql(root: pathlib.Path, service: str) -> list[tuple[str, str]]:
    d = root / service / MIGRATIONS
    if not d.is_dir():
        return []
    return [(str(p.relative_to(root)), strip_sql_comments(p.read_text(encoding="utf-8")).lower())
            for p in sorted(d.glob("*.sql"))]


def split_top_level(text: str) -> list[str]:
    """`text` split on commas that are not inside parentheses (column definitions of a CREATE TABLE)."""
    parts, depth, start = [], 0, 0
    for i, c in enumerate(text):
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
        elif c == "," and depth == 0:
            parts.append(text[start:i])
            start = i + 1
    parts.append(text[start:])
    return parts


def table_columns(sql: str) -> dict[str, set[str]]:
    """table -> column names that the script creates or adds. Coarse but comment-free and decidable."""
    cols: dict[str, set[str]] = {}
    for m in re.finditer(r"create\s+table\s+(?:if\s+not\s+exists\s+)?([a-z_][a-z0-9_.]*)\s*\(", sql):
        body = balanced(sql, m.end() - 1, "(", ")") or ""
        names = {
            item.split()[0].strip('"')
            for item in (part.strip() for part in split_top_level(body[1:-1]))
            if item and not re.match(r"(constraint|primary|unique|check|foreign|exclude|like)\b", item)
        }
        cols.setdefault(m.group(1).split(".")[-1], set()).update(names)
    for m in re.finditer(r"alter\s+table\s+(?:if\s+exists\s+)?(?:only\s+)?([a-z_][a-z0-9_.]*)\s+(.*?);", sql, re.S):
        added = re.findall(r"add\s+column\s+(?:if\s+not\s+exists\s+)?\"?([a-z_][a-z0-9_]*)", m.group(2))
        cols.setdefault(m.group(1).split(".")[-1], set()).update(added)
    return cols


def services_with_product_reference(root: pathlib.Path) -> dict[str, list[str]]:
    """service -> ["table.column", ...] for every catalog product reference column a migration declares."""
    found: dict[str, list[str]] = {}
    for d in sorted(root.glob(f"openbank-*/{MIGRATIONS}")):
        service = d.parts[len(root.parts)]
        hits: set[str] = set()
        for _, sql in migration_sql(root, service):
            for table, columns in table_columns(sql).items():
                hits.update(f"{table}.{c}" for c in columns if c in PRODUCT_REFERENCE_COLUMNS)
        if hits:
            found[service] = sorted(hits)
    return found


def check_migration(root, service, table, column) -> str | None:
    for _, sql in migration_sql(root, service):
        if column in table_columns(sql).get(table, set()):
            return None
    return f"{service}: no migration gives table `{table}` the pin column `{column}`"


def check_entity(root, service, entity, table, column) -> str | None:
    f = root / entity
    if not f.is_file():
        return f"{service}: entity file {entity} does not exist"
    text = strip_kotlin_comments(f.read_text(encoding="utf-8"))
    tables = list(re.finditer(r"@Table\(\s*name\s*=\s*\"([^\"]+)\"", text))
    for i, m in enumerate(tables):
        if m.group(1) != table:
            continue
        end = tables[i + 1].start() if i + 1 < len(tables) else len(text)
        if re.search(r"@(?:field:)?Column\(\s*name\s*=\s*\"" + re.escape(column) + r"\"", text[m.end():end]):
            return None
        return f"{service}: the `{table}` entity in {entity} does not map `@Column(name = \"{column}\")`"
    return f"{service}: no `@Table(name = \"{table}\")` entity in {entity}"


def check_creation(root, service, creation: dict) -> str | None:
    rel, function = creation.get("file", ""), creation.get("function", "")
    aggregate, argument = creation.get("aggregate", ""), creation.get("pin_argument", "")
    f = root / rel
    if not f.is_file():
        return f"{service}: creation file {rel} does not exist"
    text = strip_kotlin_comments(f.read_text(encoding="utf-8"))
    fn = re.search(r"\bfun\s+" + re.escape(function) + r"\s*\(", text)
    if not fn:
        return f"{service}: no function `{function}` in {rel}"
    params = balanced(text, fn.end() - 1, "(", ")") or ""
    brace = text.find("{", fn.end() - 1 + len(params))
    body = balanced(text, brace, "{", "}") if brace >= 0 else None
    if not body:
        return f"{service}: cannot find the body of `{function}` in {rel}"
    for call in re.finditer(r"(?<![\w.])" + re.escape(aggregate) + r"\s*\(", body):
        args = balanced(body, call.end() - 1, "(", ")") or ""
        bound = re.search(r"\b" + re.escape(argument) + r"\s*=\s*([^,)\n]+)", args)
        if bound and bound.group(1).strip() != "null":
            return None
    return (f"{service}: `{function}` in {rel} constructs `{aggregate}` without binding "
            f"`{argument}` to a pin — the contract is created unpinned")


# ---------------------------------------------------------------------------------------------- run


def load_rule(root: pathlib.Path) -> dict:
    doc = yaml.safe_load((root / RULES).read_text(encoding="utf-8")) or {}
    rule = doc.get(RULE_KEY)
    if not isinstance(rule, dict):
        raise SystemExit(f"{RULES}: `{RULE_KEY}` missing or not a mapping")
    return rule


def run(root: pathlib.Path, enforce: bool) -> int:
    rule = load_rule(root)
    services = rule.get("services") or {}
    excluded = rule.get("not_product_bearing") or {}
    findings: list[str] = []
    pending: list[str] = []

    for service, spec in services.items():
        adoption = (spec or {}).get("adoption")
        if adoption not in ADOPTIONS:
            findings.append(f"{service}: adoption must be one of {sorted(ADOPTIONS)}, got {adoption!r}")
            continue
        if adoption == "pending":
            pending.append(f"{service} ({spec.get('reason', 'no reason given')})")
            if not spec.get("reason"):
                findings.append(f"{service}: a pending adoption needs a `reason`")
            continue
        table, column = spec.get("contract_table"), spec.get("pin_column")
        for problem in (
            check_migration(root, service, table, column),
            check_entity(root, service, spec.get("entity", ""), table, column),
            check_creation(root, service, spec.get("creation") or {}),
        ):
            if problem:
                findings.append(problem)

    for service, reason in excluded.items():
        if not isinstance(reason, str) or not reason.strip():
            findings.append(f"{service}: a not_product_bearing entry needs a reason")

    derived = services_with_product_reference(root)
    for service, columns in derived.items():
        if service not in services and service not in excluded:
            findings.append(f"{service}: declares {', '.join(columns)} but is neither in "
                            f"{RULE_KEY}.services nor in {RULE_KEY}.not_product_bearing")
    for service in list(services) + list(excluded):
        if service not in derived:
            findings.append(f"{service}: listed in {RULE_KEY} but no migration declares a product reference "
                            f"column any more — stale entry")

    print(f"SUBJECTS={len(services)}")
    pinned = [s for s, spec in services.items() if (spec or {}).get("adoption") == "pinned"]
    print(f"product-revision-pinned: {len(pinned)} pinned, {len(pending)} pending, "
          f"{len(excluded)} not product-bearing, {len(derived)} services with a product reference")
    for p in pending:
        print(f"::notice::product-revision-pinned: pending adoption — {p}")
    level = "error" if enforce else "warning"
    for f in findings:
        print(f"::{level}::product-revision-pinned: {f}")
    if findings:
        print(f"product-revision-pinned: {len(findings)} finding(s)"
              + ("" if enforce else " — ADVISORY mode, not failing the build"))
        return 1 if enforce else 0
    print("product-revision-pinned: OK")
    return 0


# ------------------------------------------------------------------------------------------ self-test

_ENTITY = """
@Entity
@Table(
    name = "accounts",
)
class AccountEntity {
    @Column(name = "product_revision_id", updatable = false)
    var productRevisionId: UUID? = null
}

@Table(name = "other")
class OtherEntity {
    @Column(name = "x")
    var x: String? = null
}
"""

_CREATION = """
class AccountService {
    override suspend fun openAccount(command: OpenAccountCommand): Account {
        val pin = pinning.pinFor(command)
        val account = Account(
            id = UUID.randomUUID(),
            productRevisionPin = pin,
        )
        return account
    }

    fun other() = Account(id = UUID.randomUUID(), productRevisionPin = null)
}
"""

_MIGRATION = """
CREATE TABLE accounts (
    id UUID PRIMARY KEY,
    product_id UUID NOT NULL
);
"""

_PIN_MIGRATION = """
-- the pin
ALTER TABLE accounts
    ADD COLUMN product_revision_id UUID,
    ADD COLUMN product_revision_number BIGINT;
"""

_RULES = """
product_bearing_services:
  services:
    openbank-account-service:
      adoption: pinned
      contract_table: accounts
      pin_column: product_revision_id
      entity: openbank-account-service/src/main/kotlin/Entities.kt
      creation:
        file: openbank-account-service/src/main/kotlin/AccountService.kt
        function: openAccount
        aggregate: Account
        pin_argument: productRevisionPin
    openbank-card-service:
      adoption: pending
      reason: adopts later
  not_product_bearing:
    openbank-billing-service: consumer of the pin, opens no contract
"""


def _tree(base: pathlib.Path, **overrides: str | None) -> pathlib.Path:
    files = {
        RULES: _RULES,
        "openbank-account-service/src/main/kotlin/Entities.kt": _ENTITY,
        "openbank-account-service/src/main/kotlin/AccountService.kt": _CREATION,
        f"openbank-account-service/{MIGRATIONS}/V1__init.sql": _MIGRATION,
        f"openbank-account-service/{MIGRATIONS}/V2__pin.sql": _PIN_MIGRATION,
        f"openbank-card-service/{MIGRATIONS}/V1__cards.sql": "CREATE TABLE cards (id UUID, product_code TEXT);",
        f"openbank-billing-service/{MIGRATIONS}/V1__pins.sql": "CREATE TABLE pin (account_id UUID, product_id UUID);",
        f"openbank-ledger-service/{MIGRATIONS}/V1__l.sql": "CREATE TABLE journal (id UUID, amount NUMERIC);",
    }
    files.update(overrides)
    for rel, content in files.items():
        if content is None:
            continue
        p = base / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(textwrap.dedent(content), encoding="utf-8")
    return base


def self_test() -> int:
    cases = [
        ("the compliant tree passes", {}, 0),
        ("no migration adds the pin column", {f"openbank-account-service/{MIGRATIONS}/V2__pin.sql": None}, 1),
        ("a pin column named only in an SQL comment is nothing",
         {f"openbank-account-service/{MIGRATIONS}/V2__pin.sql": "-- ADD COLUMN product_revision_id UUID\nSELECT 1;"}, 1),
        ("the entity does not map the pin column",
         {"openbank-account-service/src/main/kotlin/Entities.kt": _ENTITY.replace("product_revision_id", "other_col")}, 1),
        ("the pin column mapped only on ANOTHER table's entity",
         {"openbank-account-service/src/main/kotlin/Entities.kt":
          _ENTITY.replace('@Column(name = "product_revision_id", updatable = false)', '@Column(name = "y")')
                 .replace('@Column(name = "x")', '@Column(name = "product_revision_id")')}, 1),
        ("the @field:Column use-site form counts",
         {"openbank-account-service/src/main/kotlin/Entities.kt": _ENTITY.replace("@Column(name = \"product_revision_id\"",
                                                                                   "@field:Column(name = \"product_revision_id\"")}, 0),
        ("the creation path drops the pin argument",
         {"openbank-account-service/src/main/kotlin/AccountService.kt": _CREATION.replace("            productRevisionPin = pin,\n", "")}, 1),
        ("the creation path binds the pin to null",
         {"openbank-account-service/src/main/kotlin/AccountService.kt": _CREATION.replace("productRevisionPin = pin", "productRevisionPin = null")}, 1),
        ("the pin argument only in a comment of the creation path",
         {"openbank-account-service/src/main/kotlin/AccountService.kt": _CREATION.replace("            productRevisionPin = pin,\n", "            // productRevisionPin = pin,\n")}, 1),
        ("the creation function is missing",
         {"openbank-account-service/src/main/kotlin/AccountService.kt": _CREATION.replace("fun openAccount", "fun openSomething")}, 1),
        ("an unlisted service with a product reference fails",
         {f"openbank-pension-service/{MIGRATIONS}/V1__p.sql": "CREATE TABLE contract (id UUID, product_id UUID);"}, 1),
        ("a product reference added by ALTER TABLE is derived too",
         {f"openbank-pension-service/{MIGRATIONS}/V1__p.sql": "ALTER TABLE contract ADD COLUMN catalog_offering_id UUID;"}, 1),
        ("a stale exclusion fails",
         {f"openbank-billing-service/{MIGRATIONS}/V1__pins.sql": "CREATE TABLE pin (account_id UUID);"}, 1),
        ("a pending entry without a reason fails",
         {RULES: _RULES.replace("      reason: adopts later\n", "")}, 1),
    ]
    failures = 0
    for name, overrides, expected in cases:
        with tempfile.TemporaryDirectory() as d:
            root = _tree(pathlib.Path(d), **overrides)
            with contextlib.redirect_stdout(io.StringIO()) as out:
                got = run(root, enforce=True)
            ok = got == expected
            failures += not ok
            print(f"{'PASS' if ok else 'FAIL'}: {name} (exit {got}, expected {expected})")
            if not ok:
                print(textwrap.indent(out.getvalue(), "    "))
    # Advisory mode reports the same finding and exits 0: the mode is the only difference.
    with tempfile.TemporaryDirectory() as d:
        root = _tree(pathlib.Path(d), **{f"openbank-account-service/{MIGRATIONS}/V2__pin.sql": None})
        with contextlib.redirect_stdout(io.StringIO()) as out:
            got = run(root, enforce=False)
        ok = got == 0 and "::warning::product-revision-pinned" in out.getvalue()
        failures += not ok
        print(f"{'PASS' if ok else 'FAIL'}: advisory mode warns and exits 0")
    print(f"self-test: {failures} failure(s)")
    return 1 if failures else 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--root", default=".")
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    return run(pathlib.Path(args.root).resolve(), args.enforce)


if __name__ == "__main__":
    sys.exit(main())
