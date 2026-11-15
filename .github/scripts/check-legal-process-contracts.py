#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Validate ADR-0381 design contracts; never confuse them with executed legal controls.

Default mode checks the reviewed requirements and reports DESIGN_CONTRACTS_OK. The
ADR's --require-evidence interface deliberately refuses activation: there is no trusted
execution/approval verifier yet. Adding a file or a status cannot mint readiness.
No code from a pack is executed, no network is accessed and no bank state is changed.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import pathlib
import re
import subprocess
import sys
from urllib.parse import urlsplit
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

ROOT = pathlib.Path(__file__).resolve().parents[2]
DIRECTORY = pathlib.Path("openbank-libs/governance/legal-process")
COMMON = {
    "instrument-revisions", "identity-and-issuer", "service-evidence", "disclosure-scope",
    "notice-permission", "independent-release", "allocation-cap", "no-overdraft",
    "capacity-and-representation", "combined-protection-and-capacity", "admission-reachability",
    "outbox-and-recovery", "stale-input", "law-transition",
}
# The explicit initial product scope of ADR-0381, not a claim of worldwide support.
COUNTRIES = {
    "CZ": ("CZK", "Europe/Prague", {
        "protected-account-link", "qualified-income", "protected-transfer-deadline",
        "protected-account-fees", "ordinary-daily-acts",
    }),
    "SK": ("EUR", "Europe/Bratislava", {
        "one-account-exemption", "income-notice", "same-day-priority", "stay-variants",
        "nonordinary-property-approval",
    }),
    "DE": ("EUR", "Europe/Berlin", {
        "funded-cohorts", "three-month-carry", "oldest-credit-first", "conversion-timing",
        "retrospective-protection", "credit-only", "moratorium", "certificate-change",
        "court-adjustment", "joint-account-shares", "appointment-without-consent-reservation",
    }),
}
OWNERS = {"legal-process", "party", "account", "balance", "transaction", "admission"}
IDENTIFIER = re.compile(r"[a-z][a-z0-9]*(?:-[a-z0-9]+)*\Z")


class ContractError(ValueError):
    """Malformed requirements, not a legal or financial decision."""


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ContractError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def fields(value, expected, where):
    if not isinstance(value, dict) or set(value) != expected:
        raise ContractError(f"{where}: expected fields {', '.join(sorted(expected))}")


def text(value, where, minimum=1):
    if not isinstance(value, str) or len(value.strip()) < minimum or len(value) > 2000 or "\x00" in value:
        raise ContractError(f"{where}: expected nonblank text ({minimum}..2000 characters)")
    return value


def date(value, where):
    if not isinstance(value, str) or not re.fullmatch(r"\d{4}-\d{2}-\d{2}", value):
        raise ContractError(f"{where}: expected ISO date")
    try:
        return dt.date.fromisoformat(value)
    except ValueError as exc:
        raise ContractError(f"{where}: invalid date") from exc


def unique_entries(value, where):
    if not isinstance(value, list) or not value:
        raise ContractError(f"{where}: expected nonempty list")
    result = {}
    for entry in value:
        if not isinstance(entry, dict):
            raise ContractError(f"{where}: expected object entries")
        key = entry.get("id")
        if not isinstance(key, str) or not IDENTIFIER.fullmatch(key):
            raise ContractError(f"{where}: invalid id")
        if key in result:
            raise ContractError(f"{where}: duplicate id {key}")
        result[key] = entry
    return result


def validate(pack, country, today, root=ROOT):
    fields(pack, {"schema_version", "jurisdiction", "revision", "state", "currency", "timezone",
                  "reviewed_on", "effective_from", "product_scope", "sources", "requirements"}, country)
    if type(pack["schema_version"]) is not int or pack["schema_version"] != 1:
        raise ContractError(f"{country}: unsupported schema_version")
    if pack["jurisdiction"] != country or country not in COUNTRIES:
        raise ContractError(f"{country}: jurisdiction does not match filename or supported design scope")
    if type(pack["revision"]) is not int or pack["revision"] < 1:
        raise ContractError(f"{country}: revision must be a positive integer")
    if pack["state"] != "design":
        raise ContractError(f"{country}: only design state is supported; runtime evidence verification is not delivered")
    # Research date is not an approval, nor a legislative effective date.
    if date(pack["reviewed_on"], f"{country}.reviewed_on") > today:
        raise ContractError(f"{country}: reviewed_on is in the future")
    if pack["effective_from"] is not None:
        raise ContractError(f"{country}: design contract cannot assert an activation effective_from")
    currency, timezone, required = COUNTRIES[country]
    if (pack["currency"], pack["timezone"]) != (currency, timezone):
        raise ContractError(f"{country}: initial currency/calendar contract changed")
    try:
        ZoneInfo(pack["timezone"])
    except (ZoneInfoNotFoundError, TypeError) as exc:
        raise ContractError(f"{country}: timezone unavailable") from exc
    fields(pack["product_scope"], {"natural_person_single_owner", "joint_accounts"}, f"{country}.product_scope")
    if pack["product_scope"] != {"natural_person_single_owner": "planned", "joint_accounts": "not_offered"}:
        raise ContractError(f"{country}: product activation requires runtime evidence verification")
    sources = unique_entries(pack["sources"], f"{country}.sources")
    for key, source in sources.items():
        fields(source, {"id", "ref", "locator", "kind"}, f"{country}.sources.{key}")
        text(source["locator"], f"{country}.sources.{key}.locator")
        if source["kind"] not in ("legislation", "authority-guidance", "channel-specification", "architecture"):
            raise ContractError(f"{country}: unknown source kind")
        if source["kind"] == "architecture":
            ref = pathlib.Path(text(source["ref"], f"{country}.sources.{key}.ref"))
            if ref.is_absolute() or ".." in ref.parts or not (root / ref).is_file():
                raise ContractError(f"{country}: unresolved repository architecture reference")
            if not (root / ref).resolve().is_relative_to(root.resolve()):
                raise ContractError(f"{country}: architecture reference escapes repository")
            continue
        try:
            url = urlsplit(text(source["ref"], f"{country}.sources.{key}.ref"))
        except ValueError as exc:
            raise ContractError(f"{country}: malformed source URL") from exc
        if url.scheme != "https" or not url.hostname or url.username or url.password:
            raise ContractError(f"{country}: source must be an absolute HTTPS URL without credentials")
    requirements = unique_entries(pack["requirements"], f"{country}.requirements")
    expected = COMMON | required
    if set(requirements) != expected:
        raise ContractError(f"{country}: requirement coverage mismatch; missing={sorted(expected - requirements.keys())}, "
                            f"unknown={sorted(requirements.keys() - expected)}")
    for key, requirement in requirements.items():
        fields(requirement, {"id", "owner", "source_ids", "expected", "delivery", "applies_when"},
               f"{country}.requirements.{key}")
        if not isinstance(requirement["owner"], str) or requirement["owner"] not in OWNERS:
            raise ContractError(f"{country}.{key}: unknown operational owner")
        if requirement["delivery"] != "unimplemented":
            raise ContractError(f"{country}.{key}: delivery claims require runtime evidence verification")
        applies = "joint-accounts-offered" if key == "joint-account-shares" else "always"
        if requirement["applies_when"] != applies:
            raise ContractError(f"{country}.{key}: mandatory scenario cannot be made conditional")
        text(requirement["expected"], f"{country}.{key}.expected", minimum=30)
        refs = requirement["source_ids"]
        if not isinstance(refs, list) or not refs or any(not isinstance(ref, str) for ref in refs):
            raise ContractError(f"{country}.{key}: source_ids must be nonempty strings")
        if len(refs) != len(set(refs)) or set(refs) - sources.keys():
            raise ContractError(f"{country}.{key}: duplicate or unresolved source references")
    return len(requirements)


def inspect(root, today):
    directory = root / DIRECTORY
    if not directory.is_dir():
        raise ContractError(f"missing design contracts directory: {DIRECTORY}")
    paths = sorted(directory.glob("*.json"))
    if {path.stem for path in paths} != set(COUNTRIES):
        raise ContractError("design scope must contain exactly CZ.json, SK.json and DE.json")
    count = 0
    for path in paths:
        if path.is_symlink() or not path.resolve().is_relative_to(root.resolve()):
            raise ContractError("design contract must be a regular repository file")
        try:
            pack = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_object,
                              parse_constant=lambda value: (_ for _ in ()).throw(ContractError(f"invalid JSON: {value}")))
        except (OSError, UnicodeError, json.JSONDecodeError) as exc:
            raise ContractError(f"{path.name}: cannot read valid UTF-8 JSON: {exc}") from exc
        count += validate(pack, path.stem, today, root)
    return count


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=pathlib.Path, default=ROOT)
    parser.add_argument("--require-jurisdictions", help="comma-separated country codes; unknown/empty/duplicate codes fail")
    parser.add_argument("--require-evidence", action="store_true", help="refuse readiness until trusted evidence verification exists")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)
    if args.self_test:
        return subprocess.call([sys.executable, str(pathlib.Path(__file__).with_name("test_legal_process_contracts.py"))])
    try:
        if args.require_jurisdictions is not None:
            codes = args.require_jurisdictions.split(",")
            if not codes or len(codes) != len(set(codes)) or any(code not in COUNTRIES for code in codes):
                raise ContractError("required jurisdictions must be distinct supported country codes: CZ,SK,DE")
        count = inspect(args.root, dt.datetime.now(dt.timezone.utc).date())
    except (ContractError, OSError) as exc:
        print(f"LEGAL_PROCESS_CONTRACT_ERROR: {exc}", file=sys.stderr)
        print("SUBJECTS=UNRESOLVED")
        return 1
    if args.require_evidence:
        print("LEGAL_PROCESS_NOT_READY: execution evidence, source transitions, production callers, connector "
              "conformance and legal approval verification are not delivered; country activation is refused",
              file=sys.stderr)
        return 1
    print(f"SUBJECTS={count}")
    print("LEGAL_PROCESS_DESIGN_CONTRACTS_OK (CZ,SK,DE; runtime support unimplemented)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
