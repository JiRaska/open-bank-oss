#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Certificate Transparency + CAA watch for the public domain (#16292).

Two questions, answered daily:

1. CAA: does the published CAA record set still equal the one OpenTofu declares
   (openbank-infra/aws/modules/dns/main.tf, resource "aws_route53_record" "caa")? The expected
   set is DERIVED from that file, never restated here, so the two cannot drift apart silently.

2. CT: has any publicly trusted CA logged an unexpired certificate for the domain whose issuer
   is not one CAA allows? That is how a mis-issued or attacker-obtained certificate (phishing,
   DNS hijack, a compromised ACME account elsewhere) becomes visible before it is used.

THE TRAP THIS IS BUILT AROUND. A CT search that returns few or no certificates looks exactly
like "nothing suspicious". Measured 2026-11-15: crt.sh returned 2 unexpired certificates for
the domain (both Amazon) while 12 hosts were serving Let's Encrypt certificates, so a monitor
on crt.sh alone was blind to the very CA an attacker would most likely use. Therefore the
watch first fetches the certificate each known host actually SERVES and requires the CT
source to contain at least one of those serials. If it does not, the verdict is
UNVERIFIABLE (exit 3), never clean.

Exit codes: 0 clean, 2 finding (CAA drift or a foreign issuer), 3 could not verify.
No credential is needed; CERTSPOTTER_TOKEN, if set, adds a second CT source.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import re
import subprocess
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DNS_TF = ROOT / "openbank-infra/aws/modules/dns/main.tf"
DOMAIN = "open-bank.tech"
# Hosts whose served certificate is the known-positive control. Public names only.
CONTROL_HOSTS = ["admin.open-bank.tech", "api.open-bank.tech", "customer.open-bank.tech",
                 "open-bank.tech", "status.open-bank.tech"]
# CAA issuer domain -> organisation name as it appears in a certificate's issuer DN.
CAA_TO_ISSUER_ORG = {"letsencrypt.org": "Let's Encrypt", "amazon.com": "Amazon"}

CLEAN, FINDING, UNVERIFIABLE = 0, 2, 3


# ── CAA ──────────────────────────────────────────────────────────────────────────

def expected_caa(tf_text: str, domain: str) -> set[str]:
    m = re.search(r'resource\s+"aws_route53_record"\s+"caa"\s*\{(.*?)\n\}', tf_text, re.S)
    if not m:
        raise ValueError("CAA resource not found in dns module")
    block = re.search(r"records\s*=\s*\[(.*?)\]", m.group(1), re.S)
    if not block:
        raise ValueError("CAA records list not found")
    out = set()
    for raw in re.findall(r'^\s*"((?:[^"\\]|\\.)*)"\s*,?\s*$', block.group(1), re.M):
        out.add(normalise_caa(raw.replace('\\"', '"').replace("${var.domain}", domain)))
    if not out:
        raise ValueError("CAA records list is empty")
    return out


def normalise_caa(record: str) -> str:
    return re.sub(r"\s+", " ", record.strip())


def published_caa(domain: str) -> set[str] | None:
    try:
        out = subprocess.run(["dig", "+short", "CAA", domain], capture_output=True, text=True, timeout=20)
    except (OSError, subprocess.TimeoutExpired):
        return None
    if out.returncode != 0:
        return None
    recs = {normalise_caa(line) for line in out.stdout.splitlines() if line.strip()}
    return recs or None  # an empty answer is indistinguishable from a resolver failure here


def allowed_issuer_orgs(caa: set[str]) -> set[str]:
    orgs = set()
    for r in caa:
        m = re.match(r'^\d+ issue(?:wild)? "([^";]+)', r)
        if m and m.group(1).strip() in CAA_TO_ISSUER_ORG:
            orgs.add(CAA_TO_ISSUER_ORG[m.group(1).strip()])
    return orgs


# ── CT ───────────────────────────────────────────────────────────────────────────

def issuer_org(issuer_dn: str) -> str:
    m = re.search(r"(?:^|,\s*)O=([^,]+)", issuer_dn)
    return (m.group(1) if m else issuer_dn).strip().strip('"')


def norm_serial(s: str) -> str:
    return s.lower().replace(":", "").lstrip("0")


def crtsh(domain: str) -> list[dict] | None:
    url = f"https://crt.sh/?q=%25.{domain}&output=json&exclude=expired"
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "openbank-ct-watch"}), timeout=90) as r:
            rows = json.load(r)
    except Exception:
        return None
    out = []
    for x in rows if isinstance(rows, list) else []:
        out.append({"serial": norm_serial(str(x.get("serial_number", ""))),
                    "issuer": issuer_org(str(x.get("issuer_name", ""))),
                    "names": sorted(set(str(x.get("name_value", "")).split())),
                    "not_after": str(x.get("not_after", "")), "source": "crt.sh"})
    return out


def certspotter(domain: str, token: str) -> list[dict] | None:
    url = (f"https://api.certspotter.com/v1/issuances?domain={domain}&include_subdomains=true"
           "&expand=dns_names&expand=issuer")
    req = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}", "User-Agent": "openbank-ct-watch"})
    try:
        with urllib.request.urlopen(req, timeout=90) as r:
            rows = json.load(r)
    except Exception:
        return None
    if not isinstance(rows, list):
        return None
    now = dt.datetime.now(dt.timezone.utc).isoformat()
    return [{"serial": norm_serial(str(x.get("cert_der", "") and x.get("serial", "") or x.get("serial", ""))),
             "issuer": issuer_org(str((x.get("issuer") or {}).get("name", ""))),
             "names": sorted(x.get("dns_names") or []),
             "not_after": str(x.get("not_after", "")), "source": "certspotter"}
            for x in rows if str(x.get("not_after", "")) > now]


def served_serial(host: str) -> str | None:
    try:
        s = subprocess.run(["openssl", "s_client", "-connect", f"{host}:443", "-servername", host],
                           input="", capture_output=True, text=True, timeout=20)
        x = subprocess.run(["openssl", "x509", "-noout", "-serial"], input=s.stdout,
                           capture_output=True, text=True, timeout=10)
    except (OSError, subprocess.TimeoutExpired):
        return None
    m = re.search(r"serial=([0-9A-Fa-f]+)", x.stdout)
    return norm_serial(m.group(1)) if m else None


# ── verdict ──────────────────────────────────────────────────────────────────────

def verdict(expected: set[str], published: set[str] | None, entries: list[dict] | None,
            served: dict[str, str | None]) -> tuple[int, list[str]]:
    lines: list[str] = []
    code = CLEAN
    if published is None:
        lines.append("UNVERIFIABLE caa: no CAA answer from DNS (resolver failure or record removed)")
        code = UNVERIFIABLE
    elif published != expected:
        for r in sorted(expected - published):
            lines.append(f"FINDING caa missing: {r}")
        for r in sorted(published - expected):
            lines.append(f"FINDING caa unexpected: {r}")
        code = FINDING
    else:
        lines.append(f"OK caa: {len(published)} records match OpenTofu")

    if entries is None:
        lines.append("UNVERIFIABLE ct: no CT source answered")
        return max(code, UNVERIFIABLE) if code != FINDING else FINDING, lines
    seen = {e["serial"] for e in entries}
    controls = {h: s for h, s in served.items() if s}
    covered = sorted(h for h, s in controls.items() if s in seen)
    if not controls:
        lines.append("UNVERIFIABLE ct: could not read any served certificate to use as a control")
        blind = True
    elif not covered:
        lines.append(f"UNVERIFIABLE ct: the CT source contains NONE of the {len(controls)} certificates our hosts serve "
                     f"({', '.join(sorted(controls))}) - it is blind, so an empty result means nothing")
        blind = True
    else:
        lines.append(f"OK ct coverage: CT source contains the served certificate of {', '.join(covered)}")
        blind = False
    allowed = allowed_issuer_orgs(expected)
    foreign = [e for e in entries if e["issuer"] not in allowed]
    for e in foreign:
        lines.append(f"FINDING ct foreign issuer: {e['issuer']} for {' '.join(e['names'])} (serial {e['serial']}, "
                     f"until {e['not_after']}, via {e['source']})")
    lines.append(f"INFO ct: {len(entries)} unexpired certificates, issuers {sorted({e['issuer'] for e in entries})}, "
                 f"allowed {sorted(allowed)}")
    if foreign:
        return FINDING, lines
    if blind:
        return (FINDING if code == FINDING else UNVERIFIABLE), lines
    return code, lines


def self_test() -> int:
    tf = '''resource "aws_route53_record" "caa" {
  records = [
    "0 issue \\"amazon.com\\"",
    # comment
    "0 issue \\"letsencrypt.org; validationmethods=dns-01\\"",
    "0 iodef \\"mailto:security@${var.domain}\\"",
  ]
}
'''
    exp = expected_caa(tf, "example.test")
    want = {'0 issue "amazon.com"', '0 issue "letsencrypt.org; validationmethods=dns-01"',
            '0 iodef "mailto:security@example.test"'}
    checks = [("derive CAA from tofu", exp == want)]
    le = {"serial": "abc", "issuer": "Let's Encrypt", "names": ["a.example.test"], "not_after": "2099", "source": "t"}
    rogue = {**le, "serial": "bad", "issuer": "Evil CA"}
    served = {"a.example.test": "abc"}
    checks += [
        ("clean", verdict(exp, set(exp), [le], served)[0] == CLEAN),
        ("CAA drift is a finding", verdict(exp, exp - {'0 issue "amazon.com"'}, [le], served)[0] == FINDING),
        ("foreign issuer is a finding", verdict(exp, set(exp), [le, rogue], served)[0] == FINDING),
        ("blind CT source is NOT clean", verdict(exp, set(exp), [{**le, "serial": "zzz"}], served)[0] == UNVERIFIABLE),
        ("empty CT result is NOT clean", verdict(exp, set(exp), [], served)[0] == UNVERIFIABLE),
        ("no CT answer is NOT clean", verdict(exp, set(exp), None, served)[0] == UNVERIFIABLE),
        ("no DNS answer is NOT clean", verdict(exp, None, [le], served)[0] == UNVERIFIABLE),
        ("foreign issuer still reported when blind", verdict(exp, set(exp), [rogue], served)[0] == FINDING),
        ("issuer DN parse", issuer_org("C=US, O=Let's Encrypt, CN=R11") == "Let's Encrypt"),
    ]
    bad = [n for n, ok in checks if not ok]
    if bad:
        print("self-test FAIL:", ", ".join(bad))
        return 1
    print(f"self-test OK: {len(checks)} verdicts, including every way the watch could falsely read clean")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--offline", action="store_true", help="only derive and print the expected CAA set")
    a = ap.parse_args()
    if a.self_test:
        return self_test()
    expected = expected_caa(DNS_TF.read_text(), DOMAIN)
    if a.offline:
        print("\n".join(sorted(expected)))
        return CLEAN
    entries = crtsh(DOMAIN)
    token = os.environ.get("CERTSPOTTER_TOKEN", "")
    if token:
        extra = certspotter(DOMAIN, token)
        if extra is not None:
            entries = (entries or []) + extra
    served = {h: served_serial(h) for h in CONTROL_HOSTS}
    code, lines = verdict(expected, published_caa(DOMAIN), entries, served)
    print("\n".join(lines))
    return code


if __name__ == "__main__":
    sys.exit(main())
