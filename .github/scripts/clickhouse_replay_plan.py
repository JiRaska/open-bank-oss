#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Compile the warehouse's finite DDL into a safe desired-state replay, not a migration registry."""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

import yaml

DIRECTORY = Path("openbank-infra/gitops/components/analytics")
OUTPUT = DIRECTORY / "clickhouse-replay-configmap.yaml"
SQL_DIRECTORY = Path("openbank-analytics-sink/src/main/resources/clickhouse")
SUPPLEMENTS = ("ci-gate-runs-schema-configmap.yaml", "cloud-finops-schema-configmap.yaml")
CI_REPLAY_TABLES = {
    "openbank_analytics.ci_gate_runs",
    "openbank_analytics.ci_workflow_runs",
    "openbank_analytics.ci_workflow_jobs",
    "openbank_analytics.deploy_events",
}
OBSOLETE = "campaign-engagement-schema-configmap.yaml"
OBJECT = r"openbank_analytics\.[A-Za-z_][A-Za-z0-9_]*"
VIEW = re.compile(rf"^CREATE\s+(?:OR\s+REPLACE\s+)?VIEW\s+(?:IF\s+NOT\s+EXISTS\s+)?({OBJECT})\b", re.I)
TABLE = re.compile(rf"^CREATE\s+TABLE\s+IF\s+NOT\s+EXISTS\s+({OBJECT})\b", re.I)
DATABASE = re.compile(r"^CREATE\s+DATABASE\s+IF\s+NOT\s+EXISTS\s+openbank_analytics$", re.I)
ALTER = re.compile(rf"^ALTER\s+TABLE\s+({OBJECT})\s+ADD\s+(?:COLUMN|INDEX)\s+IF\s+NOT\s+EXISTS\s+", re.I)
REFERENCE = re.compile(rf"\b({OBJECT})\b", re.I)


class PlanError(ValueError):
    pass


def statements(text: str) -> list[str]:
    """Finite SQL lexer: quoted semicolons are data; comments never become statements."""
    result, current = [], []
    quote = None
    i = 0
    while i < len(text):
        char = text[i]
        if quote:
            current.append(char)
            if char == "\\":
                i += 1
                if i >= len(text):
                    raise PlanError("unfinished quoted escape")
                current.append(text[i])
            elif char == quote:
                if i + 1 < len(text) and text[i + 1] == quote:
                    current.append(text[i + 1])
                    i += 1
                else:
                    quote = None
        elif text.startswith("--", i):
            end = text.find("\n", i)
            i = len(text) if end < 0 else end
            current.append("\n")
            continue
        elif text.startswith("/*", i):
            end = text.find("*/", i + 2)
            if end < 0:
                raise PlanError("unfinished block comment")
            if "/*" in text[i + 2:end]:
                raise PlanError("nested block comments are unsupported")
            current.append(" ")
            i = end + 2
            continue
        elif char in "'\"`":
            quote = char
            current.append(char)
        elif char == ";":
            sql = "".join(current).strip()
            if sql:
                result.append(sql + ";")
            current = []
        else:
            current.append(char)
        i += 1
    if quote or "".join(current).strip():
        raise PlanError("unclosed quote or missing statement terminator")
    return result


def unquoted(text: str) -> str:
    """Mask string/quoted identifier content before looking for qualified dependencies."""
    return re.sub(r"'(?:\\.|''|[^'\\])*'|\"(?:\\.|\"\"|[^\"\\])*\"|`(?:\\.|``|[^`\\])*`", "''", text)


def signature(sql: str) -> str:
    # Only collapse whitespace OUTSIDE strings: literal whitespace/case is semantic.
    pieces = re.split(r"('(?:\\.|''|[^'\\])*')", sql)
    return "".join(piece if i % 2 else re.sub(r"\s+", " ", piece) for i, piece in enumerate(pieces)).strip()


def configmap_sql(path: Path) -> list[str]:
    document = yaml.safe_load(path.read_text())
    data = document.get("data") if isinstance(document, dict) else None
    if not isinstance(data, dict) or not data:
        raise PlanError(f"{path.name}: missing SQL data")
    if any(not isinstance(key, str) or not key.endswith(".sql") or not isinstance(value, str)
           for key, value in data.items()):
        raise PlanError(f"{path.name}: unsupported data entry")
    return [sql for key in sorted(data) for sql in statements(data[key])]


def top_level_commas(text: str) -> bool:
    depth = 0
    for char in unquoted(text):
        if char == '(':
            depth += 1
        elif char == ')':
            depth -= 1
            if depth < 0:
                raise PlanError("unbalanced DDL parentheses")
        elif char == ',' and depth == 0:
            return True
    if depth:
        raise PlanError("unbalanced DDL parentheses")
    return False


def view_dependencies(sql: str) -> set[str]:
    """Recognize only the relation forms actually used by this warehouse."""
    header = VIEW.match(sql)
    tail = sql[header.end():]
    if not re.match(r"^\s+AS\s+SELECT\b", tail, re.I):
        raise PlanError("unsupported view body")
    masked = unquoted(tail)
    for match in re.finditer(r"\b(FROM|JOIN)\s+", masked, re.I):
        remaining = masked[match.end():]
        prefix = masked[:match.start()]
        if match[1].upper() == "JOIN" and re.search(r"\bARRAY\s*$", prefix, re.I):
            if not re.match(r"splitByChar\s*\(", remaining, re.I):
                raise PlanError("unsupported ARRAY JOIN expression")
            continue
        if re.match(r"\(\s*SELECT\b", remaining, re.I):
            continue  # Inner FROM/JOIN clauses are checked by the same scan.
        relation = re.match(rf"({OBJECT})(?![A-Za-z0-9_.])", remaining, re.I)
        if relation is None or re.match(r"\s*\(", remaining[relation.end():]):
            raise PlanError("unsupported relation: " + remaining[:70])
        suffix = remaining[relation.end():].lstrip()
        clauses = r"(?:WHERE|GROUP|ORDER|LIMIT|UNION|INNER|LEFT|RIGHT|FULL|CROSS|JOIN|ARRAY|ON|HAVING|SETTINGS)\b"
        if suffix and suffix[0] not in ');' and not re.match(clauses, suffix, re.I):
            alias = re.match(r"(?:AS\s+)?[A-Za-z_][A-Za-z0-9_]*\b", suffix, re.I)
            if alias is None or alias[0].upper() in {"FINAL", "SAMPLE"}:
                raise PlanError("unsupported relation alias or modifier")
            suffix = suffix[alias.end():].lstrip()
        if suffix and suffix[0] not in ');' and not re.match(clauses, suffix, re.I):
            raise PlanError("unsupported relation suffix: " + suffix[:70])
    return {name.lower() for name in REFERENCE.findall(masked)}


def compile_plan(root: Path) -> list[tuple[str, str]]:
    paths = list((root / SQL_DIRECTORY).glob("V*__*.sql"))
    if any(re.fullmatch(r"V(\d+)__.+\.sql", path.name) is None for path in paths):
        raise PlanError("unsupported migration filename")
    sources = sorted(paths,
                     key=lambda path: (int(re.fullmatch(r"V(\d+)__.+\.sql", path.name)[1]), path.name))
    if not sources:
        raise PlanError("no authoritative migrations")
    versions = [int(re.fullmatch(r"V(\d+)__.+\.sql", path.name)[1]) for path in sources]
    if len(set(versions)) != len(versions):
        raise PlanError("duplicate migration versions")
    prerequisites, tables, views, historical_views = [], {}, {}, set()
    campaign_history = set()
    database = None

    def consume(sql: str, origin: str, supplemental: bool = False) -> None:
        nonlocal database
        body = sql[:-1].strip()
        if DATABASE.fullmatch(body):
            if database is None:
                prerequisites.append((origin, sql))
                database = signature(sql)
            elif signature(sql) != database:
                raise PlanError("conflicting database declarations")
            return
        match = VIEW.match(body)
        if match:
            if supplemental:
                raise PlanError("supplemental view authority is unsupported")
            name = match[1].lower()
            if name in tables:
                raise PlanError(f"table/view collision: {name}")
            historical_views.add(signature(sql))
            if origin == "V6__campaign_engagement.sql" and name == "openbank_analytics.gold_campaign_engagement":
                campaign_history.add(signature(sql))
            # Reconcile the final view even when its original creation was IF NOT EXISTS.
            views[name] = (origin, "CREATE OR REPLACE VIEW " + name + body[match.end():] + ";")
            return
        match = TABLE.match(body)
        if match:
            name = match[1].lower()
            if not body[match.end():].lstrip().startswith("(") or re.search(r"\b(?:SELECT|WITH|AS\s+openbank_analytics\.)\b", unquoted(body), re.I):
                raise PlanError("unsupported CREATE TABLE body or CTAS")
            if name in tables or name in views:
                raise PlanError(f"duplicate object authority: {name}")
            if supplemental:
                owned = name in CI_REPLAY_TABLES if origin == SUPPLEMENTS[0] else name.startswith("openbank_analytics.finops_")
                if not owned:
                    raise PlanError(f"supplemental object outside its ownership: {name}")
            tables[name] = origin
            prerequisites.append((origin, sql))
            return
        match = ALTER.match(body)
        if match and match[1].lower() in tables:
            if top_level_commas(body[match.end():]) or re.search(r"\b(?:DROP|DELETE|UPDATE|MODIFY|CLEAR|RENAME|MATERIALIZE)\b", unquoted(body[match.end():]), re.I):
                raise PlanError("unsupported trailing ALTER action")
            if supplemental and tables[match[1].lower()] != origin:
                raise PlanError("supplemental ALTER crosses ownership")
            prerequisites.append((origin, sql))
            return
        raise PlanError(f"unsupported or out-of-order DDL in {origin}: {body[:90]}")

    for path in sources:
        for sql in statements(path.read_text()):
            consume(sql, path.name)
    obsolete = configmap_sql(root / DIRECTORY / OBSOLETE)
    if len(obsolete) != 1 or signature(obsolete[0]) not in campaign_history:
        raise PlanError("obsolete campaign supplement is not an exact historical view definition")
    for name in SUPPLEMENTS:
        for sql in configmap_sql(root / DIRECTORY / name):
            consume(sql, name, supplemental=True)
    if database is None:
        raise PlanError("missing database prerequisite")
    pending = dict(views)
    ready = set(tables)
    ordered = []
    while pending:
        progressed = False
        for name, definition in list(pending.items()):
            dependencies = view_dependencies(definition[1])
            if name in dependencies:
                raise PlanError(f"self-dependent view: {name}")
            unknown = dependencies - set(tables) - set(views)
            if unknown:
                raise PlanError(f"{name}: unknown dependencies {sorted(unknown)}")
            if dependencies <= ready:
                ordered.append(definition)
                ready.add(name)
                del pending[name]
                progressed = True
        if not progressed:
            raise PlanError(f"cyclic view dependencies: {sorted(pending)}")
    return prerequisites + ordered


def render(root: Path) -> str:
    plan = compile_plan(root)
    lines = ["# Generated by .github/scripts/clickhouse_replay_plan.py --write; edit its SQL inputs.",
             "apiVersion: v1", "kind: ConfigMap", "metadata:", "  name: clickhouse-replay-plan",
             "  namespace: analytics", "data:"]
    for number, (origin, sql) in enumerate(plan, 1):
        # JSON is a YAML scalar subset: preserve literal whitespace exactly without
        # introducing trailing-space errors in the generated manifest itself.
        value = f"-- Source: {origin}; final authoritative definition, not historical replay.\n" + sql + "\n"
        lines.append(f"  {number:04d}.sql: " + json.dumps(value, ensure_ascii=False))
    return "\n".join(lines) + "\n"


def check(root: Path) -> list[str]:
    try:
        expected = render(root)
        if not (root / OUTPUT).is_file() or (root / OUTPUT).read_text() != expected:
            return [f"stale replay plan: run python3 .github/scripts/clickhouse_replay_plan.py --write ({OUTPUT})"]
    except (PlanError, OSError, ValueError, yaml.YAMLError) as failure:
        return [f"ClickHouse replay plan: {failure}"]
    return []


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args()
    if args.write:
        (args.root / OUTPUT).write_text(render(args.root))
    else:
        failures = check(args.root)
        for failure in failures:
            print(failure)
        raise SystemExit(bool(failures))
