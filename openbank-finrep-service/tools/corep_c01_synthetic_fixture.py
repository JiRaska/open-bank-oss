#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Render or reconcile the checked-in synthetic C 01.00 own-funds fixture offline."""

import argparse
import hashlib
import io
import json
import sys
from decimal import Decimal, InvalidOperation
from pathlib import Path
from zipfile import BadZipFile, ZipFile

from check_corep_datapoints import load_coordinates
from validate_xbrl_csv_structure import check_report
from xbrl_csv_package import csv_rows, publish_report, report_package, unique


FIXTURE = Path(__file__).resolve().parents[1] / "src/test/resources/eba/reporting-framework-4.2-corep-c01-synthetic.json"
MODULE = "COREP_OF"
TABLE = "C_01.00"
ROOT = "openbank-synthetic-corep-c01"
ENTITY = "rs:DUMMYLEI123456789012.CON"
PERIOD = "2026-06-30"
SOURCE = "SYNTHETIC_CAPITAL_SLICE"
SOURCE_CODES = {"6000", "6010", "6020", "6030", "6040", "6050", "6060"}
SUPPORTED_ROWS = {"r0010", "r0015", "r0020", "r0030", "r0130", "r0200", "r0530", "r0750"}
GAP_ROWS = {"r0160", "r0300"}


def load_fixture() -> tuple[dict, str]:
    payload = FIXTURE.read_bytes()
    fixture = json.loads(payload)
    if (fixture["source"], fixture["period"], fixture["currency"]) != (SOURCE, PERIOD, "CZK"):
        raise ValueError("Only the checked-in synthetic C 01.00 capital slice is supported.")
    return fixture, hashlib.sha256(payload).hexdigest()


def amount(value: str) -> Decimal:
    if not isinstance(value, str):
        raise ValueError("Synthetic C 01.00 amounts must be decimal strings.")
    number = Decimal(value)
    if not number.is_finite() or number.as_tuple().exponent != 0 or len(number.as_tuple().digits) > 18:
        raise ValueError("The synthetic C 01.00 fixture requires finite whole CZK amounts.")
    return number


def coordinates() -> dict[str, str]:
    datapoints = load_coordinates()["c_01.00"]
    if set(datapoints) != SUPPORTED_ROWS | GAP_ROWS:
        raise ValueError("The synthetic C 01.00 coordinates differ from the pinned table subset.")
    return {row: f"dp{datapoint}" for row, datapoint in datapoints.items()}


def rows(fixture: dict) -> dict[str, dict]:
    facts = unique(fixture["previewFacts"], "rowRef")
    gaps = unique(fixture["omittedGaps"], "rowRef")
    if set(facts) != SUPPORTED_ROWS or set(gaps) != GAP_ROWS or any(not gap["reason"] for gap in gaps.values()):
        raise ValueError("The synthetic C 01.00 supported rows or omitted data gaps differ.")
    return facts


def source_values(fixture: dict) -> dict[str, Decimal]:
    lines = unique(fixture["trialBalanceLines"], "code")
    if set(lines) != SOURCE_CODES or any(line["accountType"] != "EQUITY" for line in lines.values()):
        raise ValueError("The synthetic C 01.00 source needs exactly the seven equity GL balances.")
    balances = {code: amount(line["net"]) for code, line in lines.items()}
    values = {
        "r0030": -balances["6000"] - balances["6010"],
        "r0130": -balances["6020"],
        "r0200": -balances["6030"],
        "r0530": -balances["6050"],
        "r0750": -balances["6060"],
    }
    values["r0020"] = values["r0030"] + values["r0130"] + values["r0200"] - balances["6040"]
    values["r0015"] = values["r0020"] + values["r0530"]
    values["r0010"] = values["r0015"] + values["r0750"]
    return values


def readback(data: bytes, fixture: dict, datapoints: dict[str, str]) -> None:
    check_report(io.BytesIO(data), MODULE)
    report = f"{ROOT}/reports"
    with ZipFile(io.BytesIO(data)) as archive:
        expected_members = {
            f"{ROOT}/META-INF/reportPackage.json", f"{report}/report.json",
            f"{report}/parameters.csv", f"{report}/FilingIndicators.csv", f"{report}/c_01.00.csv",
        }
        if set(archive.namelist()) != expected_members:
            raise ValueError("Expected only the synthetic C 01.00 report members.")
        params = unique(csv_rows(archive, f"{report}/parameters.csv"), "name")
        if (params["entityID"]["value"], params["refPeriod"]["value"], params["baseCurrency"]["value"]) != (
            ENTITY, PERIOD, "iso4217:CZK",
        ) or params["decimalsMonetary"]["value"] != "0":
            raise ValueError("The report entity, period, currency or precision differs from the synthetic fixture.")
        if csv_rows(archive, f"{report}/FilingIndicators.csv") != [{"templateID": TABLE, "reported": "true"}]:
            raise ValueError("The report must declare only the synthetic C 01.00 table.")
        values = unique(csv_rows(archive, f"{report}/c_01.00.csv"), "datapoint")
    if set(values) != {datapoints[row] for row in SUPPORTED_ROWS} or any(
        set(row) != {"datapoint", "factValue"} for row in values.values()
    ):
        raise ValueError("The report contains missing or unexpected C 01.00 facts.")
    for row, expected in source_values(fixture).items():
        datapoint = datapoints[row]
        if amount(values[datapoint]["factValue"]) != expected:
            raise ValueError(f"{row} {datapoint} differs from the synthetic GL source.")


def result(data: bytes, fixture: dict, datapoints: dict[str, str], source_hash: str, outcome: str) -> dict:
    gaps = [dict(gap, datapoint=datapoints[gap["rowRef"]]) for gap in fixture["omittedGaps"]]
    return {
        "result": outcome, "filingReady": False, "source": SOURCE, "period": PERIOD,
        "facts": len(fixture["previewFacts"]), "omittedGaps": gaps,
        "reportSha256": hashlib.sha256(data).hexdigest(), "sourceSha256": source_hash,
    }


def render(output: Path) -> dict:
    fixture, source_hash = load_fixture()
    datapoints = coordinates()
    values = {datapoints[row]: amount(fact["value"]) for row, fact in rows(fixture).items()}
    data = report_package(MODULE, ROOT, ENTITY, PERIOD, {TABLE: values})
    readback(data, fixture, datapoints)
    evidence = result(data, fixture, datapoints, source_hash, "SYNTHETIC_COREP_FIXTURE_WRITTEN")
    publish_report(data, output)
    return evidence


def reconcile(report: Path) -> dict:
    fixture, source_hash = load_fixture()
    rows(fixture)
    data = report.read_bytes()
    datapoints = coordinates()
    readback(data, fixture, datapoints)
    return result(data, fixture, datapoints, source_hash, "SYNTHETIC_COREP_FIXTURE_RECONCILED")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    inputs = parser.add_mutually_exclusive_group(required=True)
    inputs.add_argument("--output", type=Path, help="Render to a new synthetic report package.")
    inputs.add_argument("--report", type=Path, help="Reconcile a rendered synthetic report package.")
    args = parser.parse_args()
    try:
        evidence = render(args.output) if args.output else reconcile(args.report)
    except (BadZipFile, OSError, ValueError, KeyError, TypeError, InvalidOperation) as error:
        print(f"Synthetic COREP fixture failed: {error}", file=sys.stderr)
        return 1
    print(json.dumps(evidence, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
