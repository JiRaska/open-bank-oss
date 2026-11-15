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
like "nothing suspicious". Measured 2026-11-15: crt.sh's JSON API returned 2 unexpired
certificates for the domain while 12 hosts were serving Let's Encrypt certificates, i.e. it
was blind to the very CA an attacker would most likely use. The SAME index queried through
crt.sh's public, free, read-only Postgres interface (guest@crt.sh:5432/certwatch) returned 17,
including the exact certificate admin/api/customer serve. So the watch uses that interface
and still does not trust it blindly: it fetches the certificate each control host actually
SERVES and requires the CT source to contain at least one of them. If none is present the
verdict is UNVERIFIABLE (exit 3), never clean; a control that is missing while others are
present is reported as partial visibility.

Free and open: no account, no API key, no paid tier. Cost is one ~25 s query a day.

Exit codes: 0 clean, 2 finding (CAA drift or a foreign issuer), 3 could not verify.
"""
from __future__ import annotations

import argparse
import os
import re
import subprocess
import time
import sys
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


CRTSH_SQL = """
SELECT DISTINCT encode(x509_serialNumber(c.certificate), 'hex'),
       x509_issuerName(c.certificate),
       x509_notAfter(c.certificate),
       (SELECT string_agg(DISTINCT n, ' ') FROM x509_altNames(c.certificate) n)
  FROM certificate c
 WHERE plainto_tsquery('certwatch', :'domain') @@ identities(c.certificate)
   AND x509_notAfter(c.certificate) > now()
   AND NOT x509_hasExtension(c.certificate, '1.3.6.1.4.1.11129.2.4.3', true)
"""  # the last clause drops precertificates, which duplicate the final certificate


def parse_crtsh_rows(text: str, domain: str) -> list[dict]:
    out = []
    for line in text.splitlines():
        parts = line.split("|")
        if len(parts) != 4:
            continue
        serial, issuer, not_after, names = parts
        names_l = sorted(n for n in names.split() if n == domain or n.endswith("." + domain))
        if not names_l:
            continue  # full-text match on a name that merely contains the domain token
        out.append({"serial": norm_serial(serial), "issuer": issuer_org(issuer), "names": names_l,
                    "not_after": not_after, "source": "crt.sh"})
    return out


CT_ERRORS: list[str] = []


def crtsh(domain: str, attempts: int = 3) -> list[dict] | None:
    """crt.sh's public read-only Postgres interface. None means it did not answer.

    crt.sh limits concurrent guest connections and drops some under load, so a refusal is
    retried with a pause; the last reason is kept for the report, because an unexplained
    "could not verify" is as unhelpful as a false "clean".
    """
    for i in range(attempts):
        try:
            r = subprocess.run(["psql", "-h", "crt.sh", "-p", "5432", "-U", "guest", "-d", "certwatch",
                                "-At", "-F", "|", "-v", "ON_ERROR_STOP=1", "-v", f"domain={domain}"],
                               input=CRTSH_SQL, capture_output=True, text=True, timeout=180,
                               env={**os.environ, "PGCONNECT_TIMEOUT": "30"})
            if r.returncode == 0:
                return parse_crtsh_rows(r.stdout, domain)
            CT_ERRORS.append(f"crt.sh attempt {i + 1}: " + (r.stderr.strip().splitlines() or ["exit " + str(r.returncode)])[0][:200])
        except FileNotFoundError:
            CT_ERRORS.append("psql is not installed")
            return None
        except subprocess.TimeoutExpired:
            CT_ERRORS.append(f"crt.sh attempt {i + 1}: timed out")
        if i + 1 < attempts:
            time.sleep(20 * (i + 1))
    return None


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
        lines.append("UNVERIFIABLE ct: no CT source answered" + (f" ({'; '.join(CT_ERRORS)})" if CT_ERRORS else ""))
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
        missing = sorted(set(controls) - set(covered))
        if missing:
            lines.append(f"WARN ct partial visibility: not yet indexed for {', '.join(missing)} "
                         "(a foreign certificate for those names could be missed until it is)")
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
        ("crt.sh row parse", parse_crtsh_rows("06291d|C=US, O=Let's Encrypt, CN=YE1|2027-01-02 05:03:51|a.example.test", "example.test")
         == [{"serial": "6291d", "issuer": "Let's Encrypt", "names": ["a.example.test"], "not_after": "2027-01-02 05:03:51", "source": "crt.sh"}]),
        ("unrelated name dropped", parse_crtsh_rows("01|O=X|2027|example.test.evil.org", "example.test") == []),
        ("partial visibility is reported", any(l.startswith("WARN ct partial") for l in
         verdict(exp, set(exp), [le], {**served, "b.example.test": "ffff"})[1])),
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
    served = {h: served_serial(h) for h in CONTROL_HOSTS}
    code, lines = verdict(expected, published_caa(DOMAIN), entries, served)
    print("\n".join(lines))
    return code


if __name__ == "__main__":
    sys.exit(main())
