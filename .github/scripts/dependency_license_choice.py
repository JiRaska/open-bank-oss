#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Select the owner-approved Apache alternative for RocksDB JNI 10.1.3 (#13888).

https://github.com/facebook/rocksdb/blob/v10.1.3/README.md#license permits choosing
Apache-2.0. The pinned review action ignores versions in package exclusions, so
EVERY RocksDB row must match here, both before retry and in its actual output.
No CVE data is changed; the retry still enforces the full vulnerability policy.
"""

import json
import os
import sys
from pathlib import Path
from urllib.parse import unquote

PACKAGE = "pkg:maven/org.rocksdb/rocksdbjni@10.1.3"
QUALIFIED_PACKAGE = PACKAGE + "?repository_url=https:%2F%2Fmaven-central.storage-download.googleapis.com%2Fmaven2"
EXPRESSION = "Apache-2.0 OR GPL-2.0"
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


def is_rocksdb(row: dict) -> bool:
    if str(row.get("name", "")).lower() == "org.rocksdb:rocksdbjni":
        return True
    purl = row.get("package_url")
    if not isinstance(purl, str) or not purl.startswith("pkg:"):
        return False
    kind, _, rest = purl[4:].partition("/")
    name = rest.split("@", 1)[0].split("?", 1)[0].split("#", 1)[0]
    return kind.lower() == "maven" and unquote(name).lower() == "org.rocksdb/rocksdbjni"


def select_packages(changes: object) -> str:
    if not isinstance(changes, list) or any(not isinstance(row, dict) for row in changes):
        raise ValueError("Dependency comparison must be an array of objects")
    selected = False
    for row in changes:
        if not is_rocksdb(row):
            continue
        expected = {
            "change_type": "added", "ecosystem": "maven",
            "name": "org.rocksdb:rocksdbjni", "version": "10.1.3", "license": EXPRESSION,
        }
        if (row.get("package_url") not in (PACKAGE, QUALIFIED_PACKAGE)
                or any(row.get(key) != value for key, value in expected.items())):
            raise ValueError("RocksDB metadata no longer matches the approved Apache license choice")
        selected = True
    return PACKAGE if selected else ""


def main() -> int:
    try:
        check_result = sys.argv[1] == "--review-result"
        changes = retry_changes() if check_result else json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
        selected = select_packages(changes)
        if check_result and selected != PACKAGE:
            raise ValueError("The actual retry comparison did not confirm the approved choice")
    except (IndexError, KeyError, OSError, ValueError) as error:
        print(f"License choice cannot be established: {error}", file=sys.stderr)
        return 1
    print(f"packages={selected}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
