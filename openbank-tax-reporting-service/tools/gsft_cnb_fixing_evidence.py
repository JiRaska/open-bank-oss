#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Check local GSFT fixing evidence from exported FX outbox payloads."""

import argparse
import hashlib
import json
import re
import sys
from datetime import date, datetime, time, timezone
from decimal import Decimal, InvalidOperation
from pathlib import Path
from uuid import UUID
from zoneinfo import ZoneInfo


EVENT_TYPE = "fx.fixing.published.v1"
PRAGUE = ZoneInfo("Europe/Prague")
CURRENCY = re.compile(r"[A-Z]{3}")
FX_DB_UNIT = Decimal("0.00000001")


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


def verify(payloads: list[bytes], reference_day: date, deposit_currencies: list[str]) -> dict:
    if any(not CURRENCY.fullmatch(currency) for currency in deposit_currencies):
        raise ValueError("Use uppercase three-letter deposit currency codes.")
    required = sorted((set(deposit_currencies) - {"CZK"}) | {"EUR"})
    sources: dict[str, dict] = {}
    by_currency: dict[str, tuple[UUID, Decimal]] = {}
    by_id: dict[UUID, str] = {}
    sequences: set[int] = set()
    selected = reference_day.isoformat()
    for payload in payloads:
        source, rates = parse_payload(payload)
        sources[source["payloadSha256"]] = source
        if source["fixingDate"] != selected:
            continue
        if source["sequence"] is not None:
            sequences.add(source["sequence"])
            if len(sequences) > 1:
                raise ValueError("Conflicting publication sequences for the same declared fixing day.")
        for currency, rate_id, rate in rates:
            previous = by_currency.setdefault(currency, (rate_id, rate))
            if previous != (rate_id, rate) or by_id.setdefault(rate_id, currency) != currency:
                raise ValueError("Conflicting rate rows for the same declared fixing day.")

    missing = [currency for currency in required if currency not in by_currency]
    evidence = {
        "state": "FX_RATES" if missing else "EXACT_FIXING_EVIDENCE",
        "filingReady": False,
        "eventType": EVENT_TYPE,
        "referenceDay": selected,
        "observedFixingDates": sorted({source["fixingDate"] for source in sources.values()}),
        "requiredCurrencies": required,
        "missingCurrencies": missing,
        "publications": [sources[key] for key in sorted(sources)],
    }
    if not missing:
        evidence["fixingDate"] = selected
        evidence["czkPerUnit"] = {currency: format(by_currency[currency][1], "f") for currency in required}
        evidence["rateIds"] = {currency: str(by_currency[currency][0]) for currency in required}
    return evidence


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reference-day", required=True, type=iso_day)
    parser.add_argument("--deposit-currency", action="append", default=[])
    parser.add_argument("--publication", required=True, action="append", type=Path)
    args = parser.parse_args()
    try:
        evidence = verify([path.read_bytes() for path in args.publication], args.reference_day, args.deposit_currency)
    except (OSError, KeyError, TypeError, ValueError, InvalidOperation, AttributeError) as error:
        print(f"GSFT fixing evidence rejected: {error}", file=sys.stderr)
        return 2
    print(json.dumps(evidence, sort_keys=True))
    return 1 if evidence["state"] == "FX_RATES" else 0


if __name__ == "__main__":
    raise SystemExit(main())
