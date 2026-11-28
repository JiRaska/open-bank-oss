#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

"""Source-derived ADR-0326 error-code catalogue.

The scanner deliberately supports the fleet's one auditable enum idiom: a literal category and
title, an optional literal retryability override, and ``override val code ... get() = name``.
An unevaluable code mapping is an error, never a reason to omit an enum from the closed catalogue.
"""

from __future__ import annotations

from dataclasses import dataclass
import json
import pathlib
import re


RETRYABLE_BY_DEFAULT = {"RATE_LIMITED", "UNAVAILABLE"}
WIRE_FORMAT = re.compile(r"[A-Z][A-Z0-9]*(_[A-Z0-9]+)*\Z")
ENUM_START = re.compile(r"\benum\s+class\s+(\w+)")
ENTRY_START = re.compile(r"\s*([A-Za-z][A-Za-z0-9_]*)\s*\(")
CATEGORY = re.compile(r"\bErrorCategory\.([A-Z_]+)\b")
STRING = re.compile(r'"(?:\\.|[^"\\])*"')
RETRYABLE_OVERRIDE = re.compile(r"\bretryableOverride\s*=\s*(true|false)\b")
NAME_CODE_MAPPING = re.compile(r"\boverride\s+val\s+code\s*(?::\s*String\s*)?get\(\)\s*=\s*name\b")


@dataclass(frozen=True)
class CodeRecord:
    code: str
    category: str
    title: str
    retryable: bool
    module: str
    enum: str
    path: str

    @property
    def owner(self) -> str:
        return f"{self.module}:{self.enum}"

    @property
    def metadata(self) -> tuple[str, str, bool]:
        return self.category, self.title, self.retryable


def strip_comments(text: str) -> str:
    """Mask line and nested block comments, preserving strings and every source position."""
    out: list[str] = []
    i = 0
    block_depth = 0
    while i < len(text):
        if block_depth:
            if text.startswith("/*", i):
                block_depth += 1
                out.extend("  ")
                i += 2
            elif text.startswith("*/", i):
                block_depth -= 1
                out.extend("  ")
                i += 2
            else:
                out.append("\n" if text[i] == "\n" else " ")
                i += 1
            continue
        if text.startswith("//", i):
            while i < len(text) and text[i] != "\n":
                out.append(" ")
                i += 1
            continue
        if text.startswith("/*", i):
            block_depth = 1
            out.extend("  ")
            i += 2
            continue
        if text.startswith('"""', i):
            end = text.find('"""', i + 3)
            if end < 0:
                raise ValueError("unterminated Kotlin triple-quoted string")
            end += 3
            out.append(text[i:end])
            i = end
            continue
        if text[i] == '"':
            start = i
            i += 1
            while i < len(text):
                if text[i] == "\\":
                    i += 2
                    continue
                if text[i] == '"':
                    i += 1
                    break
                i += 1
            else:
                raise ValueError("unterminated Kotlin string")
            out.append(text[start:i])
            continue
        out.append(text[i])
        i += 1
    if block_depth:
        raise ValueError("unterminated Kotlin block comment")
    return "".join(out)


def _opening_brace(source: str, start: int) -> int:
    parens = 0
    i = start
    while i < len(source):
        char = source[i]
        if char == '"':
            if source.startswith('"""', i):
                end = source.find('"""', i + 3)
                if end < 0:
                    raise ValueError("unterminated Kotlin triple-quoted string")
                i = end + 3
                continue
            i += 1
            while i < len(source):
                if source[i] == "\\":
                    i += 2
                elif source[i] == '"':
                    break
                else:
                    i += 1
        elif char == "(":
            parens += 1
        elif char == ")":
            parens -= 1
        elif char == "{" and parens == 0:
            return i
        i += 1
    raise ValueError("enum has no body")


def _entries_region(source: str, opening: int) -> tuple[str, str]:
    """Return the top-level enum entries and the declarations following their semicolon/body."""
    parens = brackets = braces = 0
    i = opening + 1
    start = i
    while i < len(source):
        if source.startswith('"""', i):
            end = source.find('"""', i + 3)
            if end < 0:
                raise ValueError("unterminated Kotlin triple-quoted string")
            i = end + 3
            continue
        char = source[i]
        if char == '"':
            i += 1
            while i < len(source):
                if source[i] == "\\":
                    i += 2
                elif source[i] == '"':
                    break
                else:
                    i += 1
        elif char == "(":
            parens += 1
        elif char == ")":
            parens -= 1
        elif char == "[":
            brackets += 1
        elif char == "]":
            brackets -= 1
        elif char == "{":
            braces += 1
        elif char == "}":
            if braces == 0 and parens == 0 and brackets == 0:
                return source[start:i], ""
            braces -= 1
        elif char == ";" and parens == brackets == braces == 0:
            return source[start:i], source[i + 1 :]
        i += 1
    raise ValueError("unterminated enum body")


def _split_entries(region: str) -> list[str]:
    entries: list[str] = []
    parens = brackets = braces = 0
    start = 0
    i = 0
    while i < len(region):
        if region.startswith('"""', i):
            end = region.find('"""', i + 3)
            if end < 0:
                raise ValueError("unterminated Kotlin triple-quoted string")
            i = end + 3
            continue
        char = region[i]
        if char == '"':
            i += 1
            while i < len(region):
                if region[i] == "\\":
                    i += 2
                elif region[i] == '"':
                    break
                else:
                    i += 1
        elif char == "(":
            parens += 1
        elif char == ")":
            parens -= 1
        elif char == "[":
            brackets += 1
        elif char == "]":
            brackets -= 1
        elif char == "{":
            braces += 1
        elif char == "}":
            braces -= 1
        elif char == "," and parens == brackets == braces == 0:
            entries.append(region[start:i])
            start = i + 1
        i += 1
    entries.append(region[start:])
    return [entry for entry in entries if entry.strip()]


def _entry(record_path: str, enum: str, entry: str) -> tuple[str, str, str, bool]:
    match = ENTRY_START.match(entry)
    if not match:
        raise ValueError(f"{record_path}: {enum}: cannot evaluate enum entry {entry.strip()!r}")
    code = match.group(1)
    if not WIRE_FORMAT.fullmatch(code) or len(code) > 64:
        raise ValueError(f"{record_path}: {enum}.{code}: error code must be UPPER_SNAKE_CASE, at most 64 characters")
    opening = entry.find("(", match.start(1))
    depth = 0
    closing = -1
    for i in range(opening, len(entry)):
        if entry[i] == "(":
            depth += 1
        elif entry[i] == ")":
            depth -= 1
            if depth == 0:
                closing = i
                break
    if closing < 0:
        raise ValueError(f"{record_path}: {enum}.{code}: unterminated constructor arguments")
    arguments = entry[opening + 1 : closing]
    category_match = CATEGORY.search(arguments)
    title_match = STRING.search(arguments)
    if not category_match or not title_match:
        raise ValueError(f"{record_path}: {enum}.{code}: category and title must be literals")
    try:
        title = json.loads(title_match.group())
    except (ValueError, json.JSONDecodeError) as exc:
        raise ValueError(f"{record_path}: {enum}.{code}: unsupported title literal") from exc
    if not isinstance(title, str) or not title.strip() or "\n" in title or "\t" in title:
        raise ValueError(f"{record_path}: {enum}.{code}: title must be a non-empty single-line literal")
    override = RETRYABLE_OVERRIDE.search(arguments)
    category = category_match.group(1)
    retryable = (override.group(1) == "true") if override else category in RETRYABLE_BY_DEFAULT
    return code, category, title, retryable


def _supertype_list(header: str) -> str:
    """Return the colon-delimited supertype list, ignoring constructor property types."""
    depth = 0
    for index, char in enumerate(header):
        if char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
        elif char == ":" and depth == 0:
            return header[index + 1 :]
    return ""


def scan(root: pathlib.Path) -> tuple[list[CodeRecord], int]:
    records: list[CodeRecord] = []
    subjects = 0
    for path in sorted(root.glob("openbank-*/**/src/main/**/*.kt")):
        relative = path.relative_to(root).as_posix()
        if "/build/" in relative or "/generated/" in relative.lower():
            continue
        subjects += 1
        text = path.read_text(encoding="utf-8")
        if "ErrorCode" not in text or "enum class" not in text:
            continue
        try:
            source = strip_comments(text)
            for match in ENUM_START.finditer(source):
                opening = _opening_brace(source, match.end())
                header = source[match.end() : opening]
                supertype = _supertype_list(header)
                if not re.search(r"\bErrorCode\b", supertype):
                    continue
                enum = match.group(1)
                entries, declarations = _entries_region(source, opening)
                if not NAME_CODE_MAPPING.search(declarations):
                    raise ValueError(f"{relative}: {enum}: ErrorCode.code must expose its enum name with get() = name")
                for raw_entry in _split_entries(entries):
                    code, category, title, retryable = _entry(relative, enum, raw_entry)
                    records.append(CodeRecord(
                        code=code,
                        category=category,
                        title=title,
                        retryable=retryable,
                        module=relative.split("/", 1)[0],
                        enum=enum,
                        path=relative,
                    ))
        except ValueError as exc:
            raise ValueError(str(exc)) from exc
    return records, subjects


def baseline_lines(records: list[CodeRecord]) -> list[str]:
    unique: dict[str, CodeRecord] = {}
    for record in records:
        unique.setdefault(record.code, record)
    return [
        f"{record.code}\t{record.category}\t{str(record.retryable).lower()}\t{record.title}"
        for _, record in sorted(unique.items())
    ]


def duplicate_owner_lines(records: list[CodeRecord]) -> list[str]:
    grouped: dict[str, set[str]] = {}
    for record in records:
        grouped.setdefault(record.code, set()).add(record.owner)
    return [
        f"{code}\t{owner.split(':', 1)[0]}\t{owner.split(':', 1)[1]}"
        for code, owners in sorted(grouped.items()) if len(owners) > 1
        for owner in sorted(owners)
    ]


def load_baseline(root: pathlib.Path, lines: list[str] | None = None) -> dict[str, tuple[str, str, bool]]:
    if lines is None:
        lines = (root / ".github/scripts/error-code-catalogue-baseline.tsv").read_text(encoding="utf-8").splitlines()
    baseline: dict[str, tuple[str, str, bool]] = {}
    for number, line in enumerate(lines, 1):
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        columns = line.split("\t")
        if len(columns) != 4 or columns[2] not in {"true", "false"}:
            raise ValueError(f"error-code baseline line {number}: expected CODE<TAB>CATEGORY<TAB>RETRYABLE<TAB>TITLE")
        code, category, retryable, title = columns
        if code in baseline:
            raise ValueError(f"error-code baseline line {number}: duplicate code {code}")
        baseline[code] = category, title, retryable == "true"
    return baseline


def load_duplicate_owners(root: pathlib.Path, lines: list[str] | None = None) -> dict[str, set[str]]:
    if lines is None:
        lines = (root / ".github/scripts/error-code-duplicate-owners.tsv").read_text(encoding="utf-8").splitlines()
    duplicates: dict[str, set[str]] = {}
    for number, line in enumerate(lines, 1):
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        columns = line.split("\t")
        if len(columns) != 3 or not all(columns):
            raise ValueError(f"duplicate-owner baseline line {number}: expected CODE<TAB>MODULE<TAB>ENUM")
        code, module, enum = columns
        owner = f"{module}:{enum}"
        if owner in duplicates.setdefault(code, set()):
            raise ValueError(f"duplicate-owner baseline line {number}: duplicate owner {code} {owner}")
        duplicates[code].add(owner)
    return duplicates


def evaluate(
    records: list[CodeRecord],
    baseline: dict[str, tuple[str, str, bool]],
    duplicate_owners: dict[str, set[str]],
) -> list[str]:
    errors: list[str] = []
    if not records:
        errors.append("no ErrorCode enums were discovered; the catalogue corpus is missing")
    grouped: dict[str, list[CodeRecord]] = {}
    for record in records:
        grouped.setdefault(record.code, []).append(record)
    for code, occurrences in sorted(grouped.items()):
        metadata = {record.metadata for record in occurrences}
        if len(metadata) != 1:
            errors.append(f"{code}: duplicate wire code owners disagree on category/title/retryability")
        if code not in baseline:
            errors.append(f"{code}: not in the append-only baseline")
        elif baseline[code] != occurrences[0].metadata:
            errors.append(f"{code}: changed its published metadata from {baseline[code]!r} to {occurrences[0].metadata!r}")
        owners = {record.owner for record in occurrences}
        allowed = duplicate_owners.get(code, set())
        if len(owners) > 1 and owners != allowed:
            errors.append(f"{code}: duplicate wire code owners {sorted(owners)!r} do not match the explicit legacy set {sorted(allowed)!r}")
    for code in sorted(set(baseline) - set(grouped)):
        errors.append(f"{code}: published code disappeared; catalogue rows are append-only")
    for code, owners in sorted(duplicate_owners.items()):
        current = {record.owner for record in grouped.get(code, [])}
        if owners != current or len(current) < 2:
            errors.append(f"{code}: stale duplicate-owner baseline {sorted(owners)!r}; current owners are {sorted(current)!r}")
    return errors
