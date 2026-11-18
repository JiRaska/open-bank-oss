#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Differential oracle for the shared identifier validators (#16748).
#
# RodneCislo had 100% line coverage and a 68.8M-input fuzz run that could only check the parser
# against ITSELF (parse/isValid agree, canonical form is stable) — structurally unable to notice
# that it accepted birth numbers nobody was ever issued. This script supplies the missing oracle:
# python-stdnum, an independent implementation maintained against the same specifications.
#
#   generate  writes <out>.tsv: identifier, value, expected verdict, reason. The expected verdict
#             is stdnum's, EXCEPT where our validator is deliberately stricter than stdnum and the
#             rule is documented in DIVERGENCES below. A divergence can only ever turn stdnum's
#             `valid` into `invalid` — never the reverse — so a wrong-ACCEPT by our code always
#             disagrees with the oracle and fails IdentifierDifferentialTest.
#   vectors   checks every committed spec vector file against live stdnum: a vector whose
#             `stdnum` field is stale, or which differs from stdnum without a divergence reason,
#             fails. This keeps the vectors from encoding our own model of the rules.
#   self-test proves the oracle is not vacuous: a known never-issued RČ must come out invalid
#             even though stdnum accepts it, and a stdnum-invalid value must never come out valid.
#
# Pinned to python-stdnum==2.2 (.github/workflows/identifier-differential.yml).
# Run:  python3 identifier-differential.py generate --out oracle.tsv [--seed N] [--count N]
#       python3 identifier-differential.py vectors --dir openbank-libs-domain/src/test/resources/identifier-spec-vectors
#       python3 identifier-differential.py self-test

import argparse
import itertools
import json
import pathlib
import random
import string
import sys

import pycountry
from stdnum import iban as std_iban
from stdnum import isin as std_isin
from stdnum import lei as std_lei
from stdnum import luhn as std_luhn
from stdnum.at import businessid as std_at_fn
from stdnum.cz import bankaccount as std_bank
from stdnum.cz import dic as std_dic
from stdnum.cz import rc as std_rc
from stdnum.fr import siren as std_siren
from stdnum.pl import nip as std_nip


def _rc_divergence(v: str):
    """Documented rules where RodneCislo is stricter than stdnum.cz.rc. None = no divergence."""
    if v.isdigit() and not v.isascii():
        return "only ASCII digits 0-9 are birth-number digits"
    if not v.isdigit() or len(v) not in (9, 10):
        return None
    yy, mm = int(v[0:2]), int(v[2:4])
    if len(v) == 9 and yy > 53:
        return "9-digit form issued only for birth years up to 1953"
    if mm not in (*range(1, 13), *range(21, 33), *range(51, 63), *range(71, 83)):
        return "month is none of the M, M+20, M+50, M+70 encodings"
    if len(v) == 9 and (21 <= mm <= 32 or 71 <= mm <= 82):
        return "9-digit form never carries the +20/+70 overflow"
    if (21 <= mm <= 32 or 71 <= mm <= 82) and yy < 4:
        return "+20/+70 month overflow exists only from 2004"
    if len(v) == 10 and int(v[:9]) % 11 == 10:
        year = 2000 + yy if (mm > 20 and mm < 50) or mm > 70 or yy <= 53 else 1900 + yy
        if year > 1985:
            return "remainder-10 check quirk valid only for birth years up to 1985"
    return None


def _iban_divergence(v: str):
    if len(v) >= 4 and v[2:4].isdigit() and int(v[2:4]) not in range(2, 99):
        return "ISO 13616 check digits are 02..98"
    return None


def _account_divergence(v: str):
    base = v.split("/")[0].split("-")[-1]
    if base.strip("0") == "":
        return "base number must carry a significant digit"
    return None


def _iban_iso13616(v: str) -> bool:
    """ISO 13616 + SWIFT registry structure. stdnum's optional national BBAN checks (BE, ES, ME,
    NO) are outside ISO 13616 and outside what Iban.isValid claims, so they are switched off."""
    return std_iban.is_valid(v, check_country=False)


def _ico(v: str) -> bool:
    """IČO (CZ and SK share the algorithm): the check digit of stdnum.cz.dic's 8-digit legal-entity
    form. Two adaptations, both explicit: an IČO written without its leading zeros is padded (ARES
    prints them, people drop them; KYB normalises the same way), and stdnum's DIČ-only rule that a
    legal-entity DIČ cannot start with 9 is not an IČO rule, so it is not applied."""
    if not v.isascii() or not v.isdigit() or not 1 <= len(v) <= 8:
        return False
    v = v.zfill(8)
    return std_dic.calc_check_digit_legal(v[:7]) == v[7]


_ACTIVE_ISO4217 = frozenset(c.alpha_3 for c in pycountry.currencies)
# ISO 4217 codes with no minor unit (precious metals, SDR, testing, no-currency): CurrencyCode
# rejects them on purpose because a Money amount needs a scale.
_NO_MINOR_UNIT = frozenset({"XAG", "XAU", "XBA", "XBB", "XBC", "XBD", "XDR", "XPD", "XPT", "XSU", "XTS", "XUA", "XXX"})


def _currency(v: str) -> bool:
    return v in _ACTIVE_ISO4217


# Active ISO 4217 codes the JDK's currency data does not carry, so CurrencyCode has no minor unit
# to scale Money at and rejects them. Measured on JDK 25: UYW (Uruguayan wage index unit).
_JDK_UNKNOWN = frozenset({"UYW"})


def _currency_divergence(v: str):
    if v in _NO_MINOR_UNIT:
        return "ISO 4217 code without a minor unit cannot denominate Money"
    if v in _JDK_UNKNOWN:
        return "active ISO 4217 code unknown to java.util.Currency: no minor unit available"
    return None


def _at_fn(v: str) -> bool:
    """Firmenbuchnummer; the register prints the check letter lower-case, KYB upper-cases input."""
    return std_at_fn.is_valid(v[:-1] + v[-1:].lower())


def _at_fn_divergence(v: str):
    digits = v[2:-1]
    return "KYB caps the number at 6 digits (local bound, not a published rule)" if len(digits) > 6 else None


def _none(_v: str):
    return None


ORACLES = {
    "cz-rc": (std_rc.is_valid, _rc_divergence),
    "iban": (_iban_iso13616, _iban_divergence),
    "cz-account": (std_bank.is_valid, _account_divergence),
    "cz-ico": (_ico, _none),
    "sk-ico": (_ico, _none),
    "pl-nip": (std_nip.is_valid, _none),
    "fr-siren": (std_siren.is_valid, _none),
    "lei": (std_lei.is_valid, _none),
    "isin": (std_isin.is_valid, _none),
    "pan-luhn": (std_luhn.is_valid, _none),
    "at-fn": (_at_fn, _at_fn_divergence),
    "currency": (_currency, _currency_divergence),
}


# Defects the oracle has FOUND and that are tracked but not yet fixed. The expected verdict stays
# the reference's; the row's reason carries the issue, and the Kotlin conformance test tolerates
# disagreement only on such rows AND fails once none of them disagrees (the defect is fixed, so
# the marker must go). A known defect can neither be forgotten nor silently fixed.
_ISO3166 = frozenset(c.alpha_2 for c in pycountry.countries) | {"XS", "EU", "XA", "XB", "XC", "XD", "XF", "QS", "QT"}
KNOWN_DEFECTS = {
    "isin": ("#16773", lambda v: len(v) == 12 and v[:2] not in _ISO3166),
}


def expected(identifier: str, value: str):
    ref, divergence = ORACLES[identifier]
    verdict = bool(ref(value))
    reason = divergence(value) if verdict else None
    if reason:
        return False, reason
    defect = KNOWN_DEFECTS.get(identifier)
    if defect and defect[1](value):
        return verdict, f"known-defect {defect[0]}"
    return verdict, "stdnum"


def _gen_rc(r: random.Random) -> str:
    yy = r.randrange(100)
    mm = r.choice([r.randrange(100), r.randrange(1, 13) + r.choice([0, 20, 50, 70])])
    dd = r.choice([r.randrange(33), r.randrange(1, 29)])
    head = f"{yy:02d}{mm:02d}{dd:02d}{r.randrange(1000):03d}"
    if r.random() < 0.15:
        return head
    rem = int(head) % 11
    check = (0 if rem == 10 else rem) if r.random() < 0.8 else r.randrange(10)
    return head + str(check)


_IBAN_LENGTHS = {"CZ": 24, "SK": 24, "DE": 22, "GB": 22, "AT": 20, "FR": 27, "NO": 15, "PL": 28, "XA": 24}


def _iban_check(cc: str, bban: str) -> str:
    n = int("".join(str(int(ch, 36)) for ch in bban + cc + "00"))
    return f"{98 - n % 97:02d}"


def _gen_iban(r: random.Random) -> str:
    cc = r.choice(list(_IBAN_LENGTHS))
    length = _IBAN_LENGTHS[cc] + r.choice([0, 0, 0, 0, -1, 1])
    alphabet = string.digits if r.random() < 0.7 else string.digits + string.ascii_uppercase
    bban = "".join(r.choice(alphabet) for _ in range(max(1, length - 4)))
    check = _iban_check(cc, bban)
    roll = r.random()
    if roll < 0.1:
        check = r.choice(["00", "01", "99"])
    elif roll < 0.25:
        check = f"{r.randrange(100):02d}"
    return cc + check + bban


def _mod11_fix(digits: str, weights) -> str:
    for last in range(10):
        cand = digits + str(last)
        padded = cand.rjust(len(weights), "0")
        if sum(int(d) * w for d, w in zip(padded, weights, strict=True)) % 11 == 0:
            return cand
    return digits + "0"


def _gen_account(r: random.Random) -> str:
    base_w, pre_w = (6, 3, 7, 9, 10, 5, 8, 4, 2, 1), (10, 5, 8, 4, 2, 1)
    base = "".join(r.choice(string.digits) for _ in range(r.randrange(1, 10)))
    base = _mod11_fix(base, base_w) if r.random() < 0.7 else base + str(r.randrange(10))
    prefix = ""
    if r.random() < 0.4:
        prefix = "".join(r.choice(string.digits) for _ in range(r.randrange(0, 5)))
        prefix = _mod11_fix(prefix, pre_w) if r.random() < 0.7 else prefix + str(r.randrange(10))
    bank = r.choice(["0800", "0100", "0300", "2010", "5500"])
    return (f"{prefix}-" if prefix else "") + f"{base}/{bank}"


_DIGITS = string.digits
_ALNUM = string.digits + string.ascii_uppercase


def _digits(r: random.Random, n: int) -> str:
    return "".join(r.choice(_DIGITS) for _ in range(n))


def _fix_last(r: random.Random, head: str, ok) -> str:
    """Append the check digit that makes `ok` true (most of the time), or a random one."""
    if r.random() < 0.6:
        for d in _DIGITS:
            if ok(head + d):
                return head + d
    return head + r.choice(_DIGITS)


def _gen_ico(r: random.Random) -> str:
    return _fix_last(r, _digits(r, 7), _ico) if r.random() < 0.95 else _digits(r, r.choice([7, 9]))


def _gen_nip(r: random.Random) -> str:
    return _fix_last(r, _digits(r, 9), std_nip.is_valid)


def _gen_siren(r: random.Random) -> str:
    return _fix_last(r, _digits(r, 8), std_luhn.is_valid)


def _gen_luhn(r: random.Random) -> str:
    return _fix_last(r, _digits(r, r.randrange(11, 19)), std_luhn.is_valid)


def _gen_lei(r: random.Random) -> str:
    head = "".join(r.choice(_ALNUM) for _ in range(18))
    n = int("".join(str(int(c, 36)) for c in head + "00"))
    check = f"{98 - n % 97:02d}" if r.random() < 0.7 else f"{r.randrange(100):02d}"
    return head + check


def _gen_isin(r: random.Random) -> str:
    cc = r.choice(["US", "CZ", "DE", "GB", "XS", "EU", "XX", "QQ", "AA"])
    head = cc + "".join(r.choice(_ALNUM if r.random() < 0.3 else _DIGITS) for _ in range(9))
    return _fix_last(r, head, std_isin.is_valid)


def _gen_at_fn(r: random.Random) -> str:
    return "FN" + _digits(r, r.randrange(1, 8)) + r.choice("abcdefghijkmnpqrstuvwxyzlo0")


def _gen_currency(r: random.Random) -> str:
    return "".join(r.choice(string.ascii_uppercase) for _ in range(3))


GENERATORS = {
    "cz-rc": _gen_rc,
    "iban": _gen_iban,
    "cz-account": _gen_account,
    "cz-ico": _gen_ico,
    "sk-ico": _gen_ico,
    "pl-nip": _gen_nip,
    "fr-siren": _gen_siren,
    "lei": _gen_lei,
    "isin": _gen_isin,
    "pan-luhn": _gen_luhn,
    "at-fn": _gen_at_fn,
    "currency": _gen_currency,
}
# Identifiers whose whole input space is small enough to enumerate instead of sample.
EXHAUSTIVE = {
    "currency": lambda: ("".join(t) for t in itertools.product(string.ascii_uppercase, repeat=3)),
}


def generate(out: pathlib.Path, seed: int, count: int) -> int:
    r = random.Random(seed)
    rows = 0
    with out.open("w", encoding="utf-8") as fh:
        for identifier, gen in GENERATORS.items():
            values = EXHAUSTIVE[identifier]() if identifier in EXHAUSTIVE else (gen(r) for _ in range(count))
            for value in values:
                verdict, reason = expected(identifier, value)
                fh.write(f"{identifier}\t{value}\t{str(verdict).lower()}\t{reason}\n")
                rows += 1
    print(f"wrote {rows} oracle rows (seed {seed}) to {out}")
    return 0


def vectors(directory: pathlib.Path) -> int:
    refs = {k: v[0] for k, v in ORACLES.items()}
    bad = checked = 0
    for f in sorted(directory.glob("*.json")):
        d = json.loads(f.read_text(encoding="utf-8"))
        ref = refs.get(d["identifier"])
        if bool(d.get("differential")) != (ref is not None):
            print(f"::error::{f.name}: declares differential={d.get('differential')} but the oracle "
                  f"{'has' if ref else 'has no'} reference for {d['identifier']!r}")
            bad += 1
        if ref is None:
            continue
        for c in d["cases"]:
            live = bool(ref(c["value"]))
            checked += 1
            if c.get("stdnum") != live:
                print(f"::error::{f.name} {c['value']!r}: recorded stdnum={c.get('stdnum')} but stdnum says {live}")
                bad += 1
            if live != c["valid"] and not c.get("stdnum_divergence"):
                print(f"::error::{f.name} {c['value']!r} ({c['rule']}): expects {c['valid']}, stdnum says {live}, no stdnum_divergence reason")
                bad += 1
            if c["valid"] and not live:
                print(f"::error::{f.name} {c['value']!r}: valid per vector but stdnum rejects — a vector may never be LOOSER than the reference")
                bad += 1
    print(f"checked {checked} spec vectors against stdnum: {bad} problem(s)")
    if checked == 0:
        print("::error::no vectors checked")
        return 2
    return 1 if bad else 0


def self_test() -> int:
    ok = True
    # stdnum accepts a +20 month for 2003, which was never issued: the oracle must say invalid.
    never_issued = f"{3:02d}{21:02d}{1:02d}0000"  # 2003, +20 month: built, not a literal (gitleaks rod-cislo)
    never_issued = never_issued[:9] + str(int(never_issued[:9]) % 11 % 10)
    ok &= std_rc.is_valid(never_issued) and expected("cz-rc", never_issued)[0] is False
    # a value stdnum rejects must never be expected valid, whatever the divergence rules say.
    r = random.Random(1)
    for identifier, gen in GENERATORS.items():
        for _ in range(2000):
            v = gen(r)
            if not ORACLES[identifier][0](v) and expected(identifier, v)[0]:
                ok = False
    # the generator must produce both verdicts for every identifier.
    r = random.Random(2)
    for identifier, gen in GENERATORS.items():
        seen = {expected(identifier, gen(r))[0] for _ in range(2000)}
        ok &= seen == {True, False}
    print("self-test", "ok" if ok else "FAILED")
    return 0 if ok else 2


def main() -> int:
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    g = sub.add_parser("generate")
    g.add_argument("--out", required=True, type=pathlib.Path)
    g.add_argument("--seed", type=int, default=16748)
    g.add_argument("--count", type=int, default=20000)
    v = sub.add_parser("vectors")
    v.add_argument("--dir", required=True, type=pathlib.Path)
    sub.add_parser("self-test")
    a = ap.parse_args()
    if a.cmd == "generate":
        return generate(a.out, a.seed, a.count)
    if a.cmd == "vectors":
        return vectors(a.dir)
    return self_test()


if __name__ == "__main__":
    sys.exit(main())
