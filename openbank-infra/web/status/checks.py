#!/usr/bin/env python3
"""Collect public status evidence outside the banking cluster.

The output is a read-only JSON document suitable for a separate static origin.
An error in one probe is data, never permission to reuse its last green result.
"""

from __future__ import annotations

import argparse
import json
import socket
import ssl
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

TARGETS = {
    "dns": "status.open-bank.tech",
    "tls": "status.open-bank.tech",
    "website": "https://open-bank.tech/",
    "customer_sign_in": "https://kc.open-bank.tech/realms/openbank-customers/.well-known/openid-configuration",
    "api_edge": "https://api.open-bank.tech/",
}
MAX_HISTORY_DAYS = 30
MAX_INPUT_AGE_SECONDS = 600


def _http(url: str, *, require_200: bool, marker: bytes | None = None) -> tuple[bool, int | None, float]:
    started = time.monotonic()
    try:
        with urlopen(Request(url, headers={"User-Agent": "OpenBankStatus/1.0"}), timeout=8) as response:
            status = response.status
            body = response.read(64_000) if marker else b""
    except HTTPError as exc:
        status = exc.code
        body = b""
    except (OSError, URLError):
        return False, None, round((time.monotonic() - started) * 1000)
    ok = status == 200 if require_200 else 200 <= status < 500
    if marker is not None:
        ok = ok and marker in body
    return ok, status, round((time.monotonic() - started) * 1000)


def probe(name: str, target: str) -> dict[str, object]:
    try:
        if name == "dns":
            addresses = socket.getaddrinfo(target, 443, type=socket.SOCK_STREAM)
            return {"ok": bool(addresses), "latency_ms": None}
        if name == "tls":
            started = time.monotonic()
            with socket.create_connection((target, 443), timeout=8) as connection:
                with ssl.create_default_context().wrap_socket(connection, server_hostname=target) as secure:
                    certificate = secure.getpeercert()
            expiry = ssl.cert_time_to_seconds(certificate["notAfter"])
            return {
                "ok": expiry > time.time() + 86400,
                "latency_ms": round((time.monotonic() - started) * 1000),
                "certificate_expires_at": datetime.fromtimestamp(expiry, timezone.utc).isoformat(),
            }
        marker = {
            "customer_sign_in": b'"issuer"',
            "website": b"<title>OpenBank",
        }.get(name)
        ok, status, latency = _http(target, require_200=name != "api_edge", marker=marker)
        return {"ok": ok, "http_status": status, "latency_ms": latency}
    except (OSError, KeyError, ValueError, ssl.SSLError):
        return {"ok": False, "latency_ms": None}


def build_document(now: datetime, previous: object, observations: dict[str, dict[str, object]]) -> dict[str, object]:
    """Use only fresh current observations for headline status; history is context."""
    now = now.astimezone(timezone.utc)
    history: list[dict[str, object]] = []
    if isinstance(previous, dict) and isinstance(previous.get("history"), list):
        cutoff = now - timedelta(days=MAX_HISTORY_DAYS)
        for sample in previous["history"]:
            if not isinstance(sample, dict) or not isinstance(sample.get("at"), str):
                continue
            try:
                at = datetime.fromisoformat(sample["at"].replace("Z", "+00:00"))
            except ValueError:
                continue
            if at.tzinfo is not None and cutoff <= at < now and isinstance(sample.get("checks"), dict):
                history.append(sample)
    current: dict[str, dict[str, object]] = {}
    for name in TARGETS:
        check = observations.get(name)
        current[name] = check if isinstance(check, dict) and isinstance(check.get("ok"), bool) else {"ok": False, "missing": True}
    state = "OPERATIONAL" if all(check["ok"] is True for check in current.values()) else "DEGRADED"
    sample = {"at": now.isoformat().replace("+00:00", "Z"), "checks": {name: check["ok"] for name, check in current.items()}}
    history.append(sample)
    return {
        "schema_version": 1,
        "observed_at": sample["at"],
        "expires_at": (now + timedelta(seconds=MAX_INPUT_AGE_SECONDS)).isoformat().replace("+00:00", "Z"),
        "state": state,
        "checks": current,
        "history": history,
        "incidents": [],  # supplied from the reviewed incident file by main()
    }


def confirmed_incidents(raw: object) -> list[dict[str, object]]:
    """Publish only explicit, reviewed incident records and recovery timestamps."""
    if not isinstance(raw, list):
        raise ValueError("incidents must be a list")
    result = []
    for item in raw:
        if not isinstance(item, dict) or item.get("confirmed") is not True:
            raise ValueError("every published incident must be confirmed")
        if not all(isinstance(item.get(key), str) and item[key] for key in ("id", "summary", "started_at")):
            raise ValueError("incident id, summary and start are required")
        started = datetime.fromisoformat(item["started_at"].replace("Z", "+00:00"))
        if started.tzinfo is None:
            raise ValueError("incident timestamps must include timezone")
        resolved_at = item.get("resolved_at")
        if resolved_at is not None:
            if not isinstance(resolved_at, str):
                raise ValueError("resolved_at must be a timestamp")
            resolved = datetime.fromisoformat(resolved_at.replace("Z", "+00:00"))
            if resolved.tzinfo is None or resolved < started:
                raise ValueError("invalid recovery timestamp")
        result.append({key: item[key] for key in ("id", "summary", "started_at", "resolved_at") if key in item})
    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--previous", type=Path)
    parser.add_argument("--incidents", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    previous: object = None
    if args.previous and args.previous.exists():
        try:
            previous = json.loads(args.previous.read_text())
        except (OSError, ValueError):
            pass
    observations = {name: probe(name, target) for name, target in TARGETS.items()}
    document = build_document(datetime.now(timezone.utc), previous, observations)
    document["incidents"] = confirmed_incidents(json.loads(args.incidents.read_text()))
    args.output.write_text(json.dumps(document, separators=(",", ":"), sort_keys=True) + "\n")


if __name__ == "__main__":
    main()
