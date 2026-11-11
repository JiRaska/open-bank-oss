#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Check the repository's emitted FINREP coordinates against the pinned EBA xBRL-CSV taxonomy."""

import argparse
import io
import json
import sys
from pathlib import Path
from zipfile import BadZipFile, ZipFile

from validate_xbrl_csv_structure import PACKAGE_HASH, TAXONOMY_PACKAGES, digest


FIXTURE = Path(__file__).resolve().parents[1] / "src/test/resources/eba/reporting-framework-4.2-datapoints.json"


def check(package: Path) -> int:
    if digest(package) != PACKAGE_HASH:
        raise ValueError("Taxonomy package digest does not match the pinned release.")
    fixture = json.loads(FIXTURE.read_bytes())
    if fixture["taxonomyPackageSha256"] != PACKAGE_HASH:
        raise ValueError("The FINREP fixture refers to a different taxonomy package.")
    facts = fixture["supportedFacts"]
    tables = {fact["templateId"] for fact in facts}
    with ZipFile(package) as outer, ZipFile(io.BytesIO(outer.read(TAXONOMY_PACKAGES[1]))) as taxonomy:
        for table in sorted(tables):
            check_table(taxonomy, table, [fact for fact in facts if fact["templateId"] == table])
    return len(facts)


def check_table(taxonomy: ZipFile, table: str, facts: list[dict]) -> None:
    file_stem = (table[0] + "_" + table[1:]).lower()
    suffix = f"/finrep/4.2/tab/{file_stem}/{file_stem}.json"
    members = [name for name in taxonomy.namelist() if name.endswith(suffix)]
    if len(members) != 1:
        raise ValueError(f"Expected one official definition for {table}.")
    template = json.loads(taxonomy.read(members[0]))["tableTemplates"][file_stem.upper().replace(".", "-")]
    groups = template["columns"]["datapoint"]["propertyGroups"]
    for fact in facts:
        expected = f"{{{file_stem.upper()},{fact['rowRef']},{fact['colRef']}}}"
        datapoint = "dp" + str(fact["datapointId"])
        metadata = groups.get(datapoint, {}).get("eba:documentation", {})
        cell = "".join(metadata.get("cellcode", "").split())
        if cell != expected or metadata.get("type") != "m":
            raise ValueError(f"{table} {fact['rowRef']}/{fact['colRef']} is not monetary {datapoint}.")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--taxonomy-package", required=True, type=Path)
    args = parser.parse_args()
    try:
        count = check(args.taxonomy_package)
    except (BadZipFile, OSError, ValueError, KeyError, TypeError) as error:
        print(f"Datapoint verification failed: {error}", file=sys.stderr)
        return 1
    print(f"Verified {count} FINREP coordinates against the pinned xBRL-CSV taxonomy.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
