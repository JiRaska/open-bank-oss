#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Render the currently supported FINREP9 facts to a deterministic offline draft package."""

import argparse
import csv
import hashlib
import io
import json
import os
import re
import sys
import tempfile
from datetime import date
from decimal import Decimal, InvalidOperation, localcontext
from pathlib import Path
from zipfile import ZipFile, ZipInfo

from reconcile_finrep_facts import load_sources, supported_values
from validate_xbrl_csv_structure import ENTRYPOINTS, check_report


ENTITY = re.compile(r"rs:[A-Z0-9]{20}\.(CON|IND)")
MAX_DECIMAL_DIGITS = 64


def csv_text(rows: list) -> str:
    stream = io.StringIO(newline="")
    csv.writer(stream, lineterminator="\n").writerows(rows)
    return stream.getvalue()


def facts_for_render(templates: dict, supported: list[dict], period: str) -> dict[str, dict[str, Decimal]]:
    values = {
        template: supported_values(source, period, [fact for fact in supported if fact["templateId"] == template])
        for template, source in templates.items()
    }
    amounts = [amount for table in values.values() for amount in table.values()]
    for amount in amounts:
        _, digits, exponent = amount.as_tuple()
        if max(len(digits), abs(amount.adjusted()), abs(exponent)) > MAX_DECIMAL_DIGITS:
            raise ValueError("A source amount exceeds the decimal bounds of the offline draft renderer.")
    with localcontext() as context:
        context.prec = 2 * MAX_DECIMAL_DIGITS + 2
        assets = values["F01.01"]["dp32354"]
        liabilities = values["F01.02"]["dp32592"]
        equity = values["F01.03"]["dp32464"]
        if assets != liabilities + equity or assets != values["F01.03"]["dp32654"]:
            raise ValueError("Assets do not reconcile to liabilities plus equity across the three source templates.")
    return values


def render(previews: list[Path], entity: str, output: Path) -> dict:
    if not ENTITY.fullmatch(entity):
        raise ValueError("Use a reporting entity in rs:<20-character LEI>.CON or .IND form.")
    templates, supported, source_hashes = load_sources(previews)
    period = templates["F01.01"]["period"]
    if date.fromisoformat(period).isoformat() != period:
        raise ValueError("The frozen source needs an ISO reference date.")
    values = facts_for_render(templates, supported, period)
    decimals = max(0, max(-amount.as_tuple().exponent for table in values.values() for amount in table.values()))
    parameters = [
        ("name", "value"), ("entityID", entity), ("refPeriod", period), ("decimalsInteger", 0),
        ("baseCurrency", "iso4217:CZK"), ("decimalsMonetary", decimals),
        ("decimalsPercentage", 4), ("decimalsDecimal", 2),
    ]
    members = {
        "META-INF/reportPackage.json": json.dumps({"documentInfo": {"documentType": "https://xbrl.org/report-package/2023"}}),
        "reports/report.json": json.dumps({"documentInfo": {
            "documentType": "https://xbrl.org/2021/xbrl-csv", "extends": [ENTRYPOINTS["FINREP9"]],
        }}),
        "reports/parameters.csv": csv_text(parameters),
    }
    indicators = [("templateID", "reported")]
    for template, facts in sorted(values.items()):
        table = f"{template[0]}_{template[1:]}"
        indicators.append((table, "true"))
        rows = [("datapoint", "factValue")] + [(dp, format(amount, "f")) for dp, amount in sorted(facts.items())]
        members[f"reports/{table.lower()}.csv"] = csv_text(rows)
    members["reports/FilingIndicators.csv"] = csv_text(indicators)
    archive = io.BytesIO()
    with ZipFile(archive, "w") as package:
        for name, content in sorted(members.items()):
            package.writestr(ZipInfo(f"openbank-finrep-supported/{name}"), content)
    data = archive.getvalue()
    check_report(io.BytesIO(data), "FINREP9")
    with tempfile.TemporaryDirectory(prefix=".finrep-", dir=output.parent) as directory:
        staged = Path(directory) / "report.zip"
        staged.write_bytes(data)
        staged.chmod(0o600)
        os.link(staged, output)
    return {
        "result": "SUPPORTED_FACTS_DRAFT_WRITTEN", "period": period, "facts": len(supported),
        "reportSha256": hashlib.sha256(data).hexdigest(), "sourceSha256ByTemplate": source_hashes,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--template", required=True, action="append", type=Path)
    parser.add_argument("--entity-id", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    try:
        result = render(args.template, args.entity_id, args.output)
    except (OSError, ValueError, KeyError, TypeError, InvalidOperation) as error:
        print(f"FINREP draft rendering failed: {error}", file=sys.stderr)
        return 1
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
