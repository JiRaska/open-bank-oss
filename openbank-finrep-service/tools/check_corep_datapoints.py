#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Verify the C 01.00 and C 02.00.a preview coordinates against the pinned EBA taxonomy."""

import argparse
import io
import json
import sys
from pathlib import Path
from zipfile import BadZipFile, ZipFile

from check_finrep_datapoints import check_table
from validate_xbrl_csv_structure import PACKAGE_HASH, TAXONOMY_PACKAGES, digest


COORDINATES = Path(__file__).resolve().parents[1] / "src/test/resources/eba/reporting-framework-4.2-corep-coordinates.json"


def load_coordinates() -> dict[str, dict[str, int]]:
    fixture = json.loads(COORDINATES.read_bytes())
    if fixture["taxonomyPackageSha256"] != PACKAGE_HASH:
        raise ValueError("The COREP coordinates refer to a different taxonomy package.")
    tables = fixture["tables"]
    if set(tables) != {"c_01.00", "c_02.00.a"}:
        raise ValueError("Expected the C 01.00 and C 02.00.a coordinate tables.")
    for rows in tables.values():
        if len(set(rows.values())) != len(rows) or any(type(value) is not int or value <= 0 for value in rows.values()):
            raise ValueError("COREP datapoint identifiers must be unique positive integers per table.")
    return tables


def check(package: Path) -> int:
    if digest(package) != PACKAGE_HASH:
        raise ValueError("Taxonomy package digest does not match the pinned release.")
    tables = load_coordinates()
    with ZipFile(package) as outer, ZipFile(io.BytesIO(outer.read(TAXONOMY_PACKAGES[1]))) as taxonomy:
        for table, rows in tables.items():
            check_table(taxonomy, "corep", table, [
                {"rowRef": row, "colRef": "c0010", "datapointId": dp} for row, dp in rows.items()
            ])
    return sum(len(rows) for rows in tables.values())


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--taxonomy-package", required=True, type=Path)
    args = parser.parse_args()
    try:
        count = check(args.taxonomy_package)
    except (BadZipFile, OSError, ValueError, KeyError, TypeError) as error:
        print(f"COREP datapoint verification failed: {error}", file=sys.stderr)
        return 1
    print(f"Verified {count} COREP coordinates against the pinned xBRL-CSV taxonomy.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
