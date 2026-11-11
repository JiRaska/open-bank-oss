#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Reconcile the 36 ledger-supported FINREP facts in a report package to frozen template responses."""

import argparse
import csv
import hashlib
import io
import json
import sys
from decimal import Decimal, InvalidOperation
from pathlib import Path
from zipfile import BadZipFile, ZipFile

from check_finrep_datapoints import FIXTURE
from validate_xbrl_csv_structure import check_report


def csv_rows(archive: ZipFile, member: str) -> list[dict[str, str]]:
    with archive.open(member) as source, io.TextIOWrapper(source, encoding="utf-8-sig", newline="") as stream:
        return list(csv.DictReader(stream))


def unique(rows: list[dict], key: str) -> dict:
    indexed = {row[key]: row for row in rows}
    if len(indexed) != len(rows):
        raise ValueError(f"Duplicate {key} in the report evidence.")
    return indexed


def reconcile(report: Path, previews: list[Path]) -> dict:
    report_payload = report.read_bytes()
    check_report(io.BytesIO(report_payload), "FINREP9")
    supported = json.loads(FIXTURE.read_bytes())["supportedFacts"]
    expected_templates = {fact["templateId"] for fact in supported}
    sources = []
    source_hashes = {}
    for path in previews:
        payload = path.read_bytes()
        template = json.loads(payload, parse_float=Decimal, parse_int=Decimal)
        source_hashes[template["templateId"]] = hashlib.sha256(payload).hexdigest()
        sources.append(template)
    templates = unique(sources, "templateId")
    if set(templates) != expected_templates:
        raise ValueError("Supply one frozen source response for each supported FINREP template.")

    with ZipFile(io.BytesIO(report_payload)) as archive:
        metadata = next(name for name in archive.namelist() if name.endswith("/reports/report.json"))
        directory = metadata.removesuffix("report.json")
        params = unique(csv_rows(archive, f"{directory}parameters.csv"), "name")
        if params["baseCurrency"]["value"] != "iso4217:CZK":
            raise ValueError("The selected source mappings require CZK report facts.")
        period = params["refPeriod"]["value"]
        indicators = unique(csv_rows(archive, f"{directory}FilingIndicators.csv"), "templateID")
        for template_id, source in templates.items():
            table = f"{template_id[0]}_{template_id[1:]}"
            if indicators[table]["reported"].lower() != "true":
                raise ValueError(f"The report does not declare {table} as reported.")
            report_rows = unique(csv_rows(archive, f"{directory}{table.lower()}.csv"), "datapoint")
            facts = [fact for fact in supported if fact["templateId"] == template_id]
            check_template(source, period, report_rows, facts)
    return {
        "result": "SUPPORTED_FACTS_RECONCILED",
        "period": period,
        "facts": len(supported),
        "reportSha256": hashlib.sha256(report_payload).hexdigest(),
        "sourceSha256ByTemplate": source_hashes,
    }


def check_template(source: dict, period: str, rows: dict, facts: list[dict]) -> None:
    template = source["templateId"]
    if (
        source["period"] != period or source["dataGaps"] != [] or source["isBalanced"] is not True
        or source["balanceVerdict"] != "AGREED_BALANCED"
    ):
        raise ValueError(f"{template} requires a same-date, balanced source with complete mappings.")
    cells = {(cell["rowRef"], cell["colRef"]): cell for cell in source["cells"]}
    expected = {(fact["rowRef"], fact["colRef"]) for fact in facts}
    if len(cells) != len(source["cells"]) or set(cells) != expected:
        raise ValueError(f"{template} source coordinates differ from the pinned mapping.")
    for fact in facts:
        cell = cells[(fact["rowRef"], fact["colRef"])]
        datapoint = f"dp{fact['datapointId']}"
        report_value = Decimal(rows[datapoint]["factValue"])
        source_value = Decimal(cell["value"])
        if (
            cell["currency"] != "CZK" or not source_value.is_finite() or not report_value.is_finite()
            or source_value != report_value
        ):
            raise ValueError(f"{template} {datapoint} differs from the frozen source.")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", required=True, type=Path)
    parser.add_argument("--template", required=True, action="append", type=Path)
    args = parser.parse_args()
    try:
        result = reconcile(args.report, args.template)
    except (BadZipFile, OSError, ValueError, KeyError, TypeError, InvalidOperation) as error:
        print(f"FINREP reconciliation failed: {error}", file=sys.stderr)
        return 1
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
