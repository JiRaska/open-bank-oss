#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Select owner-approved permissive alternatives in dual-licensed dependencies.

An SPDX `OR` expression lets the consumer choose one license. The pinned review action
denies any expression that merely MENTIONS a denied license, so each approved choice is
listed here, exactly, and nothing else is exempted:

- RocksDB JNI 10.1.3, `Apache-2.0 OR GPL-2.0`, Apache-2.0 chosen (#13888).
  https://github.com/facebook/rocksdb/blob/v10.1.3/README.md#license
- elkjs 0.12.1, `EPL-2.0 OR GPL-3.0-or-later`, EPL-2.0 chosen. Reached through mermaid;
  main already shipped elkjs 0.9.3 under EPL-2.0 alone, so the choice keeps the
  obligations unchanged (#15330).

The pinned action ignores versions in package exclusions, so EVERY added row of an
approved package must match its entry, both before retry and in the actual retry output.
A removed row adds no obligation and is not an exemption, so it is not checked.
No CVE data is changed; the retry still enforces the full vulnerability policy.
"""

import json
import os
import sys
from pathlib import Path
from urllib.parse import unquote

APPROVED = (
    {
        "purl_kind": "maven", "purl_name": "org.rocksdb/rocksdbjni",
        "name": "org.rocksdb:rocksdbjni", "ecosystem": "maven", "version": "10.1.3",
        "license": "Apache-2.0 OR GPL-2.0",
        "package_urls": (
            "pkg:maven/org.rocksdb/rocksdbjni@10.1.3",
            "pkg:maven/org.rocksdb/rocksdbjni@10.1.3"
            "?repository_url=https:%2F%2Fmaven-central.storage-download.googleapis.com%2Fmaven2",
        ),
        "exclusion": "pkg:maven/org.rocksdb/rocksdbjni@10.1.3",
    },
    {
        "purl_kind": "npm", "purl_name": "elkjs",
        "name": "elkjs", "ecosystem": "npm", "version": "0.12.1",
        "license": "EPL-2.0 OR GPL-3.0-or-later",
        "package_urls": ("pkg:npm/elkjs@0.12.1",),
        "exclusion": "pkg:npm/elkjs@0.12.1",
    },
)
# Kept for callers and tests written against the single RocksDB choice.
PACKAGE = APPROVED[0]["exclusion"]
QUALIFIED_PACKAGE = APPROVED[0]["package_urls"][1]
EXPRESSION = APPROVED[0]["license"]
RETRY_FIELDS = {
    "name": "RETRY_NAMES", "package_url": "RETRY_PACKAGE_URLS", "license": "RETRY_LICENSES",
    "version": "RETRY_VERSIONS", "ecosystem": "RETRY_ECOSYSTEMS", "change_type": "RETRY_CHANGE_TYPES",
}


def retry_changes() -> list[dict]:
    # Project the fields separately: this fleet's full JSON exceeds the OS limit
    # for one environment variable. Missing/unequal arrays cannot silently drop rows.
    columns = [json.loads(os.environ[name]) for name in RETRY_FIELDS.values()]
    if any(not isinstance(column, list) for column in columns):
        raise ValueError("Actual retry fields must be arrays")
    if len({len(column) for column in columns}) != 1:
        raise ValueError("Actual retry field arrays must have equal lengths")
    return [dict(zip(RETRY_FIELDS, values, strict=True)) for values in zip(*columns, strict=True)]


def approved_entry(row: dict) -> dict | None:
    """The approved entry this row belongs to, by name or by purl, or None."""
    name = str(row.get("name", "")).lower()
    purl = row.get("package_url")
    kind = purl_name = ""
    if isinstance(purl, str) and purl.startswith("pkg:"):
        kind, _, rest = purl[4:].partition("/")
        purl_name = unquote(rest.split("@", 1)[0].split("?", 1)[0].split("#", 1)[0]).lower()
    for entry in APPROVED:
        if name == entry["name"] or (kind.lower() == entry["purl_kind"] and purl_name == entry["purl_name"]):
            return entry
    return None


def is_rocksdb(row: dict) -> bool:
    entry = approved_entry(row)
    return entry is not None and entry is APPROVED[0]


def select_packages(changes: object) -> str:
    if not isinstance(changes, list) or any(not isinstance(row, dict) for row in changes):
        raise ValueError("Dependency comparison must be an array of objects")
    selected: list[str] = []
    for row in changes:
        entry = approved_entry(row)
        if entry is None:
            continue
        if entry is not APPROVED[0] and row.get("change_type") == "removed":
            continue
        expected = {key: entry[key] for key in ("ecosystem", "name", "version", "license")}
        expected["change_type"] = "added"
        if (row.get("package_url") not in entry["package_urls"]
                or any(row.get(key) != value for key, value in expected.items())):
            raise ValueError(f"{entry['name']} metadata no longer matches the approved license choice")
        if entry["exclusion"] not in selected:
            selected.append(entry["exclusion"])
    return ", ".join(selected)


def main() -> int:
    try:
        check_result = sys.argv[1] == "--review-result"
        changes = retry_changes() if check_result else json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
        selected = select_packages(changes)
        if check_result and not selected:
            raise ValueError("The actual retry comparison did not confirm the approved choice")
    except (IndexError, KeyError, OSError, ValueError) as error:
        print(f"License choice cannot be established: {error}", file=sys.stderr)
        return 1
    print(f"packages={selected}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
