#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Check local GSFT fixing evidence from exported FX outbox payloads."""

import argparse
import hashlib
import json
import re
import sys
from datetime import date, datetime, time, timezone
from decimal import ROUND_HALF_UP, Decimal, InvalidOperation, localcontext
from pathlib import Path
from uuid import UUID
from zoneinfo import ZoneInfo


EVENT_TYPE = "fx.fixing.published.v1"
PRAGUE = ZoneInfo("Europe/Prague")
CURRENCY = re.compile(r"[A-Z]{3}")
FX_DB_UNIT = Decimal("0.00000001")
CNB_ARCHIVE_URL = (
    "https://www.cnb.cz/cs/financni-trhy/devizovy-trh/kurzy-devizoveho-trhu/"
    "kurzy-devizoveho-trhu/denni_kurz.txt?date="
)
CNB_TABLE_HEADER = "země|měna|množství|kód|kurz"
CNB_FIXING_HEADER = re.compile(r"([0-9]{2})\.([0-9]{2})\.([0-9]{4}) #([0-9]{1,3})")
CNB_QUOTE = re.compile(r"[0-9]{1,18},[0-9]{1,8}")


def unique_object(pairs: list[tuple[str, object]]) -> dict:
    fields = dict(pairs)
    if len(fields) != len(pairs):
        raise ValueError("Duplicate field in FX evidence.")
    return fields


def iso_day(raw: str) -> date:
    value = date.fromisoformat(raw)
    if value.isoformat() != raw:
        raise ValueError("Expected a date in YYYY-MM-DD form.")
    return value


def instant(raw: str) -> datetime:
    value = datetime.fromisoformat(raw)
    if value.utcoffset() is None:
        raise ValueError("FX evidence timestamps must contain a UTC offset.")
    return value


def parse_rate(raw: dict) -> tuple[str, UUID, Decimal]:
    currency = raw["currency"]
    if not CURRENCY.fullmatch(currency) or currency == "CZK":
        raise ValueError("Expected a foreign currency quoted directly in CZK.")
    amount = raw["ratePerUnit"]
    if type(amount) not in (int, Decimal):
        raise ValueError("ratePerUnit must be a JSON number.")
    rate = Decimal(amount)
    if not rate.is_finite() or rate <= 0 or rate.adjusted() >= 10 or rate.quantize(FX_DB_UNIT) != rate:
        raise ValueError("The FX source rate must fit its positive numeric(18,8) storage.")
    return currency, UUID(raw["rateId"]), rate.quantize(FX_DB_UNIT)


def parse_payload(payload: bytes) -> tuple[dict, list[tuple[str, UUID, Decimal]]]:
    event = json.loads(payload, parse_float=Decimal, object_pairs_hook=unique_object)
    if (event["sourceService"], event["source"], event["quoteCurrency"]) != ("fx-service", "CNB", "CZK"):
        raise ValueError("Expected the FX producer's native CNB fixing quoted in CZK.")
    fixing_day = iso_day(event["fixingDate"])
    declared_start = datetime.combine(fixing_day, time.min, PRAGUE).astimezone(timezone.utc)
    if event["validFrom"] != declared_start.isoformat().replace("+00:00", "Z"):
        raise ValueError("The declared fixing date and its Prague midnight validity start disagree.")
    if instant(event["validTo"]) <= declared_start:
        raise ValueError("The FX fixing validity interval is empty.")
    instant(event["occurredAt"])
    sequence = event["sequence"]
    if sequence is not None and (type(sequence) is not int or sequence < 0):
        raise ValueError("The fixing sequence must be a nonnegative integer or null.")

    rates = [parse_rate(raw) for raw in event["rates"]]
    if not rates or len({currency for currency, _, _ in rates}) != len(rates) or len(
        {rate_id for _, rate_id, _ in rates}
    ) != len(rates):
        raise ValueError("An FX publication must have unique currencies and rate IDs.")
    source = {
        "payloadSha256": hashlib.sha256(payload).hexdigest(),
        "fixingDate": fixing_day.isoformat(),
        "sequence": sequence,
        "validFrom": event["validFrom"],
        "validTo": event["validTo"],
        "occurredAt": event["occurredAt"],
    }
    return source, rates


def parse_archive(payload: bytes, reference_day: date) -> tuple[dict, dict[str, tuple[int, Decimal]]]:
    export = json.loads(payload, object_pairs_hook=unique_object)
    if set(export) != {"requestUrl", "httpStatus", "capturedAt", "responseText", "responseSha256"}:
        raise ValueError("Expected the ČNB archive response export fields.")
    request_url = CNB_ARCHIVE_URL + f"{reference_day.day:02d}.{reference_day.month:02d}.{reference_day.year:04d}"
    if export["requestUrl"] != request_url or type(export["httpStatus"]) is not int or export["httpStatus"] != 200:
        raise ValueError("The ČNB archive request must be a successful response for the exact reference-day URL.")
    raw = export["responseText"]
    response_hash = hashlib.sha256(raw.encode("utf-8")).hexdigest()
    if response_hash != export["responseSha256"]:
        raise ValueError("ČNB archive response SHA-256 mismatch.")

    lines = raw.splitlines()
    header = CNB_FIXING_HEADER.fullmatch(lines[0]) if len(lines) >= 3 else None
    if header is None or lines[1] != CNB_TABLE_HEADER:
        raise ValueError("Expected the ČNB Czech daily archive header and currency table.")
    day, month, year, sequence = map(int, header.groups())
    fixing_day = date(year, month, day)
    capture_day = instant(export["capturedAt"]).astimezone(PRAGUE).date()
    if fixing_day > reference_day:
        raise ValueError("The ČNB archive fixing date follows its requested reference day.")
    if capture_day < reference_day or (fixing_day < reference_day and capture_day == reference_day):
        raise ValueError("A different ČNB fixing date requires capture after the Prague reference day.")

    quotes = {}
    for line in lines[2:]:
        country, name, units_text, currency, czk_text = line.split("|")
        if not (country and name and re.fullmatch(r"[0-9]{1,10}", units_text) and CNB_QUOTE.fullmatch(czk_text)):
            raise ValueError("Invalid ČNB archive quote row.")
        units = int(units_text)
        czk = Decimal(czk_text.replace(",", "."))
        if not 0 < units <= 2_147_483_647 or czk <= 0 or not CURRENCY.fullmatch(currency) or currency == "CZK":
            raise ValueError("Invalid ČNB archive currency, quantity or amount.")
        if currency in quotes:
            raise ValueError("Ambiguous duplicate currency in the ČNB archive response.")
        quotes[currency] = units, czk
    return {
        "requestUrl": request_url,
        "httpStatus": 200,
        "capturedAt": export["capturedAt"],
        "responseSha256": response_hash,
        "fixingDate": fixing_day.isoformat(),
        "sequence": sequence,
        "provenanceStatus": "OPERATOR_SUPPLIED_UNVERIFIED",
    }, quotes


def archive_rate_per_unit(units: int, czk: Decimal) -> Decimal:
    # Match RoundingPolicy.FX_RATE. The bounded quote and divisor leave enough precision
    # to distinguish both sides of a half-unit at scale eight before rounding.
    with localcontext() as context:
        context.prec = 50
        return (czk / units).quantize(FX_DB_UNIT, rounding=ROUND_HALF_UP)


def verify(
    payloads: list[bytes],
    reference_day: date,
    deposit_currencies: list[str],
    archive_response: bytes | None = None,
) -> dict:
    if any(not CURRENCY.fullmatch(currency) for currency in deposit_currencies):
        raise ValueError("Use uppercase three-letter deposit currency codes.")
    required = sorted((set(deposit_currencies) - {"CZK"}) | {"EUR"})
    sources: dict[str, dict] = {}
    by_currency: dict[str, tuple[UUID, Decimal]] = {}
    by_id: dict[UUID, str] = {}
    sequences: set[int] = set()
    selected = reference_day.isoformat()
    archive, quotes = parse_archive(archive_response, reference_day) if archive_response is not None else ({}, {})
    fixing_date = archive.get("fixingDate", selected)
    for payload in payloads:
        source, rates = parse_payload(payload)
        sources[source["payloadSha256"]] = source
        if archive and fixing_date < source["fixingDate"] <= selected:
            raise ValueError("ČNB archive association conflicts with a later FX fixing on or before the reference day.")
        if source["fixingDate"] != fixing_date:
            continue
        sequence = source["sequence"]
        if archive:
            if sequence is None:
                continue
            if sequence != archive["sequence"]:
                raise ValueError("ČNB archive and FX publication sequences disagree.")
        if sequence is not None:
            sequences.add(sequence)
            if len(sequences) > 1:
                raise ValueError("Conflicting publication sequences for the same declared fixing day.")
        for currency, rate_id, rate in rates:
            previous = by_currency.setdefault(currency, (rate_id, rate))
            if previous != (rate_id, rate) or by_id.setdefault(rate_id, currency) != currency:
                raise ValueError("Conflicting rate rows for the same declared fixing day.")

    if archive:
        for currency in required:
            if currency in quotes and currency in by_currency and (
                archive_rate_per_unit(*quotes[currency]) != by_currency[currency][1]
            ):
                raise ValueError(f"ČNB archive rate and FX publication disagree for {currency}.")
    missing = [currency for currency in required if currency not in by_currency or (archive and currency not in quotes)]
    matched_state = "ARCHIVE_MATCHED_FIXING_EVIDENCE" if archive else "EXACT_FIXING_EVIDENCE"
    evidence = {
        "state": "FX_RATES" if missing else matched_state,
        "filingReady": False,
        "eventType": EVENT_TYPE,
        "referenceDay": selected,
        "observedFixingDates": sorted({source["fixingDate"] for source in sources.values()}),
        "requiredCurrencies": required,
        "missingCurrencies": missing,
        "publications": [sources[key] for key in sorted(sources)],
    }
    if archive:
        evidence["archiveResponse"] = archive
    if not missing:
        evidence["fixingDate"] = fixing_date
        if archive:
            evidence["archiveQuotes"] = {
                currency: {"units": quotes[currency][0], "czk": format(quotes[currency][1], "f")} for currency in required
            }
        evidence["czkPerUnit"] = {currency: format(by_currency[currency][1], "f") for currency in required}
        evidence["rateIds"] = {currency: str(by_currency[currency][0]) for currency in required}
    return evidence


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reference-day", required=True, type=iso_day)
    parser.add_argument("--deposit-currency", action="append", default=[])
    parser.add_argument("--publication", required=True, action="append", type=Path)
    parser.add_argument("--cnb-archive-response", type=Path, help="Local JSON export of the ČNB reference-day text response")
    args = parser.parse_args()
    try:
        evidence = verify(
            [path.read_bytes() for path in args.publication],
            args.reference_day,
            args.deposit_currency,
            args.cnb_archive_response.read_bytes() if args.cnb_archive_response is not None else None,
        )
    except (OSError, KeyError, TypeError, ValueError, InvalidOperation, AttributeError) as error:
        print(f"GSFT fixing evidence rejected: {error}", file=sys.stderr)
        return 2
    print(json.dumps(evidence, sort_keys=True))
    return 1 if evidence["state"] == "FX_RATES" else 0


if __name__ == "__main__":
    raise SystemExit(main())
