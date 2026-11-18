# SPDX-License-Identifier: Apache-2.0
"""Read and write deterministic single-entity xBRL-CSV report packages."""

import csv
import io
import json
import os
import tempfile
from decimal import Decimal
from pathlib import Path
from zipfile import ZipFile, ZipInfo

from validate_xbrl_csv_structure import ENTRYPOINTS, check_report


def csv_rows(archive: ZipFile, member: str) -> list[dict[str, str]]:
    with archive.open(member) as source, io.TextIOWrapper(source, encoding="utf-8-sig", newline="") as stream:
        return list(csv.DictReader(stream))


def unique(rows: list[dict], key: str) -> dict:
    indexed = {row[key]: row for row in rows}
    if len(indexed) != len(rows):
        raise ValueError(f"Duplicate {key} in the report evidence.")
    return indexed


def csv_text(rows: list) -> str:
    stream = io.StringIO(newline="")
    csv.writer(stream, lineterminator="\n").writerows(rows)
    return stream.getvalue()


def report_package(
    module: str, root: str, entity: str, period: str, values: dict[str, dict[str, Decimal]],
) -> bytes:
    decimals = max(0, max(-amount.as_tuple().exponent for table in values.values() for amount in table.values()))
    parameters = [
        ("name", "value"), ("entityID", entity), ("refPeriod", period), ("decimalsInteger", 0),
        ("baseCurrency", "iso4217:CZK"), ("decimalsMonetary", decimals),
        ("decimalsPercentage", 4), ("decimalsDecimal", 2),
    ]
    members = {
        "META-INF/reportPackage.json": json.dumps({"documentInfo": {"documentType": "https://xbrl.org/report-package/2023"}}),
        "reports/report.json": json.dumps({"documentInfo": {
            "documentType": "https://xbrl.org/2021/xbrl-csv", "extends": [ENTRYPOINTS[module]],
        }}),
        "reports/parameters.csv": csv_text(parameters),
    }
    indicators = [("templateID", "reported")]
    for table, facts in sorted(values.items()):
        indicators.append((table, "true"))
        rows = [("datapoint", "factValue")] + [(dp, format(amount, "f")) for dp, amount in sorted(facts.items())]
        members[f"reports/{table.lower()}.csv"] = csv_text(rows)
    members["reports/FilingIndicators.csv"] = csv_text(indicators)
    archive = io.BytesIO()
    with ZipFile(archive, "w") as package:
        for name, content in sorted(members.items()):
            package.writestr(ZipInfo(f"{root}/{name}"), content)
    data = archive.getvalue()
    check_report(io.BytesIO(data), module)
    return data


def publish_report(data: bytes, output: Path) -> None:
    with tempfile.TemporaryDirectory(prefix=".xbrl-", dir=output.parent) as directory:
        staged = Path(directory) / "report.zip"
        staged.write_bytes(data)
        staged.chmod(0o600)
        os.link(staged, output)
