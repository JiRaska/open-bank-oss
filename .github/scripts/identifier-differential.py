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
import datetime
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
from stdnum.bg import egn as std_bg_egn
from stdnum.cz import bankaccount as std_bank
from stdnum.cz import dic as std_dic
from stdnum.cz import rc as std_rc
from stdnum.eu import vat as std_vat
from stdnum.lv import pvn as std_lv_pvn
from stdnum.fr import siren as std_siren
from stdnum.pl import nip as std_nip
from stdnum.ro import cnp as std_ro_cnp


# The caller-supplied "today" for the birth-number future rule. RodneCislo.parse(raw, today) is clock-free, so the
# oracle, the vectors (cz-rc.json `as_of`) and the Kotlin test all use this fixed date, never a clock.
AS_OF = datetime.date(2026, 6, 15)


def _rc_birthdate(v: str):
    """The date a structurally valid birth number encodes, or None."""
    yy, mm, dd = int(v[0:2]), int(v[2:4]), int(v[4:6])
    month = mm - 70 if mm > 70 else mm - 50 if mm > 50 else mm - 20 if mm > 20 else mm
    if len(v) == 9:
        year = 1900 + yy
    elif 21 <= mm <= 32 or 71 <= mm <= 82:
        year = 2000 + yy
    else:
        year = 1900 + yy if yy >= 54 else 2000 + yy
    try:
        return datetime.date(year, month, dd)
    except ValueError:
        return None


# Sources for the birth-number rules below (RČ). Each rule carries its own citation; where the rule
# is established practice rather than statute, it says so and claims no paragraph.
_RC_LAW = "https://www.zakonyprolidi.cz/cs/2000-133"       # zákon č. 133/2000 Sb., § 13: the birth number
_RC_MVCR = "https://www.mvcr.cz/clanek/rodne-cislo.aspx"    # MV ČR: structure and history of the number


def _rc_divergence(v: str):
    """Rules where RodneCislo is stricter than stdnum.cz.rc. None = no divergence.

    This is the oracle's own model of the rules and shares its author with the Kotlin parser and
    RodneCisloReference; the citations are what make it checkable against something else."""
    if v.isdigit() and not v.isascii():
        # a digit is 0-9: the number is defined over ASCII decimal digits [zákon 133/2000 Sb. § 13, via _RC_LAW]
        return "only ASCII digits 0-9 are birth-number digits"
    if not v.isdigit() or len(v) not in (9, 10):
        return None
    yy, mm = int(v[0:2]), int(v[2:4])
    if len(v) == 9 and yy > 53:
        # the 9-digit form was issued up to 1953 only [MV ČR description, _RC_MVCR; established practice, no paragraph claimed]
        return "9-digit form issued only for birth years up to 1953"
    if mm not in (*range(1, 13), *range(21, 33), *range(51, 63), *range(71, 83)):
        # the month field carries M, M+50 (women); M+20 / M+70 are the overflow encodings [_RC_LAW § 13; _RC_MVCR]
        return "month is none of the M, M+20, M+50, M+70 encodings"
    if len(v) == 9 and (21 <= mm <= 32 or 71 <= mm <= 82):
        # the overflow was introduced for the 10-digit form in 2004 [_RC_MVCR; practice, no paragraph claimed]
        return "9-digit form never carries the +20/+70 overflow"
    if (21 <= mm <= 32 or 71 <= mm <= 82) and yy < 4:
        # +20/+70 overflow exists only from 2004 [_RC_MVCR; practice, no paragraph claimed]
        return "+20/+70 month overflow exists only from 2004"
    if len(v) == 10 and int(v[:9]) % 11 == 10:
        year = 2000 + yy if (mm > 20 and mm < 50) or mm > 70 or yy <= 53 else 1900 + yy
        if year > 1985:
            # remainder 10 (check digit 0) was issued only until 1985 [_RC_MVCR; practice, no paragraph claimed]
            return "remainder-10 check quirk valid only for birth years up to 1985"
    born = _rc_birthdate(v)
    if born is not None and born > AS_OF:
        # a person cannot have been born after today; the caller supplies today [_RC_LAW; common sense, no paragraph claimed]
        return "birthdate is in the future relative to the caller-supplied today (as_of)"
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
# Codes that are ACTIVE in ISO 4217 but that CurrencyCode rejects on purpose. Each entry is local
# Money policy, not spec: a monetary amount needs a minor unit to scale at. The reason and the
# source of the underlying fact are per code, never a blanket set.
_ISO_LIST = "https://www.six-group.com/en/products-services/financial-information/data-standards.html"
_JDK_CCY = "https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/Currency.html"
LOCAL_CURRENCY_POLICY = {
    **{c: ("ISO 4217 lists no minor unit for it (precious metal, SDR, testing or no-currency code)", _ISO_LIST)
       for c in ("XAG", "XAU", "XBA", "XBB", "XBC", "XBD", "XDR", "XPD", "XPT", "XSU", "XTS", "XUA", "XXX")},
    "UYW": ("active ISO 4217 unit-of-account code that the JDK 25 currency data does not carry, so there is no minor unit", _JDK_CCY),
}


def _currency(v: str) -> bool:
    return v in _ACTIVE_ISO4217


# Active ISO 4217 codes the JDK's currency data does not carry, so CurrencyCode has no minor unit
# to scale Money at and rejects them. Measured on JDK 25: UYW (Uruguayan wage index unit).
_JDK_UNKNOWN = frozenset({"UYW"})


def _currency_divergence(v: str):
    policy = LOCAL_CURRENCY_POLICY.get(v)
    return f"local Money policy: {policy[0]}" if policy else None


def _at_fn(v: str) -> bool:
    """Firmenbuchnummer; the register prints the check letter lower-case, KYB upper-cases input."""
    return std_at_fn.is_valid(v[:-1] + v[-1:].lower())


def _at_fn_divergence(v: str):
    digits = v[2:-1]
    return "KYB caps the number at 6 digits (local bound, not a published rule)" if len(digits) > 6 else None


def _sk_official(n: str) -> bool:
    """Slovak DIČ: 10 digits, first digit not 0, third digit in 2,3,4,7,8,9, divisible by 11."""
    return n[0] != "0" and n[2] in "234789" and int(n) % 11 == 0


# ONE policy per identifier for dates: an identifier whose spec EMBEDS a date (a birth number, a personal code) must
# reject a date after the caller-supplied today; every other identifier states why it has none. The inventory
# gate requires an entry for every identifier the oracle judges, like ZERO_POLICY.
#   identifier -> ("future-reject", what encodes the date) | ("no-date", reason)
DATE_POLICY = {
    "cz-rc": ("future-reject", "the birth number itself (RodneCislo.parse(raw, today))"),
    "eu-vat": ("future-reject", "CZ DIČ of a natural person (RČ), BG EGN, LV personal code, RO CNP"),
    "cz-account": ("no-date", "an account number encodes no date"),
    "cz-ico": ("no-date", "an IČO encodes no date"),
    "sk-ico": ("no-date", "an IČO encodes no date"),
    "pl-nip": ("no-date", "a NIP encodes no date"),
    "fr-siren": ("no-date", "a SIREN encodes no date"),
    "at-fn": ("no-date", "a Firmenbuchnummer encodes no date"),
    "lei": ("no-date", "an LEI encodes no date"),
    "isin": ("no-date", "an ISIN encodes no date"),
    "iban": ("no-date", "an IBAN encodes no date"),
    "pan-luhn": ("no-date", "the checksum primitive ignores the expiry date, which is not part of the PAN"),
    "currency": ("no-date", "a currency code encodes no date"),
}


def _vat_birthdate(v: str):
    """The birthdate a natural-person VAT number encodes (CZ RČ, BG EGN, LV personal code, RO CNP), or None."""
    cc, n = v[:2], v[2:]
    if not n.isdigit() or not n.isascii():
        return None
    try:
        if cc == "CZ" and len(n) in (9, 10):
            return _rc_birthdate(n)
        if cc == "SK" and len(n) == 10 and not _sk_official(n):
            return _rc_birthdate(n)           # the RČ branch of stdnum.sk.dph
        if cc == "BG" and len(n) == 10:
            return std_bg_egn.get_birth_date(n)
        if cc == "LV" and len(n) == 11 and n[0] in "0123":
            return std_lv_pvn.get_birth_date(n)
        if cc == "RO" and len(n) == 13:
            return std_ro_cnp.get_birth_date(n)
    except Exception:  # noqa: BLE001 - an undecodable date is just not a birth date
        return None
    return None


def _be_official(n: str) -> bool:
    """Belgian VAT: first digit 0 or 1, check digits = 97 - (first eight digits mod 97)."""
    return n[0] in "01" and int(n[8:]) == 97 - int(n[:8]) % 97


def _eu_vat_divergence(v: str):
    """Where kyb's EuVatChecksums is deliberately stricter than stdnum.eu.vat."""
    cc, national = v[:2], v[2:]
    born = _vat_birthdate(v)
    if born is not None and born > AS_OF:
        return "birthdate is in the future relative to the caller-supplied today (as_of)"
    if cc == "CZ" and national.isdigit() and len(national) in (9, 10) and not (len(national) == 9 and national[0] == "6"):
        return _rc_divergence(national)          # a CZ DIČ of a natural person is a birth number
    if cc == "BE" and national.isdigit() and len(national) == 10 and not _be_official(national):
        return ("Belgian check digits are 97 - (first eight digits mod 97); stdnum accepts any congruent pair, "
                "including 98 and 99")
    if cc == "SK" and national.isdigit() and len(national) == 10 and not _sk_official(national):
        # stdnum.sk.dph accepts EITHER the official rule OR a Slovak birth number (RČ). The RČ branch is kept,
        # judged by the strict RČ rules (and the future bound) like every other RČ
        return _rc_divergence(national)
    return None


def _siren_divergence(v: str):
    if v == "000000000":
        return "an all-zero SIREN is not assignable (Luhn passes, but it is never allocated)"
    return None


def _none(_v: str):
    return None


ORACLES = {
    "cz-rc": (std_rc.is_valid, _rc_divergence),
    "iban": (_iban_iso13616, _iban_divergence),
    "cz-account": (std_bank.is_valid, _account_divergence),
    "cz-ico": (_ico, _none),
    "sk-ico": (_ico, _none),
    "pl-nip": (std_nip.is_valid, _none),
    "fr-siren": (std_siren.is_valid, _siren_divergence),
    "eu-vat": (std_vat.is_valid, _eu_vat_divergence),
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
# Each defect is ONE predicate on ONE implementation behaviour, never a per-identifier blanket:
#   id -> (issue, identifier, manifests(value))
# `manifests` is true where the implementation is currently WRONG (it accepts a value the reference
# rejects). Only rows whose expected verdict is False can carry it, so a regression that rejects a
# valid value is never tagged and always fails. The Kotlin judge fails a defect whose tagged rows
# all agree (fixed -> the marker must go) and any disagreement that is not tagged.
_EU_VAT_SHAPE_ONLY = ()  # kyb implements a check for every state (#16887); a state left shape-only must be named here with a reason
KNOWN_DEFECTS = {
    "currency-cuc": ("#16821", "currency", lambda v: v == "CUC"),
    "currency-bgn": ("#16821", "currency", lambda v: v == "BGN"),
    "isin-prefix-not-iso3166": ("#16773", "isin", lambda v: len(v) == 12 and v[:2] not in _ISO3166),
}
for _cc, _issue in (("CZ", "#16896"), ("SK", "#16897")):
    KNOWN_DEFECTS[f"eu-vat-future-birthdate-{_cc}"] = (
        _issue, "eu-vat", (lambda cc: lambda v: v[:2] == cc and (_vat_birthdate(v) or AS_OF) > AS_OF)(_cc))
for _cc in _EU_VAT_SHAPE_ONLY:
    KNOWN_DEFECTS[f"eu-vat-shape-only-{_cc}"] = ("#16774", "eu-vat", (lambda cc: lambda v: v[:2] == cc)(_cc))


def expected(identifier: str, value: str):
    ref, divergence = ORACLES[identifier]
    verdict = bool(ref(value))
    reason = divergence(value) if verdict else None
    if reason:
        verdict = False
    if not verdict:
        for defect_id, (issue, ident, manifests) in KNOWN_DEFECTS.items():
            if ident == identifier and manifests(value):
                return verdict, f"known-defect {defect_id} {issue}"
    return verdict, reason or "stdnum"


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
    if r.random() < 0.01:
        return "000000000"
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


def _gen_person_vat(r: random.Random) -> str:
    """A natural-person VAT number whose date straddles AS_OF (past, today, future), check digit by search."""
    cc = r.choice(["BG", "LV", "RO", "CZ"])
    day = AS_OF + datetime.timedelta(days=r.choice([-20000, -400, -1, 0, 1, 3, 400, 9000]))
    if cc == "BG":
        body = f"{day.year % 100:02d}{day.month + (40 if day.year >= 2000 else 0):02d}{day.day:02d}" + _digits(r, 3)
        for d in string.digits:
            if std_bg_egn.is_valid(body + d):
                return cc + body + d
    if cc == "LV":
        body = f"{day.day:02d}{day.month:02d}{day.year % 100:02d}{(day.year - 1800) // 100}" + _digits(r, 3)
        for d in string.digits:
            if std_vat.is_valid(cc + body + d):
                return cc + body + d
    if cc == "RO":
        s = "5" if day.year >= 2000 else "1"
        body = s + f"{day.year % 100:02d}{day.month:02d}{day.day:02d}" + f"{r.randrange(1, 47):02d}" + _digits(r, 3)
        for d in string.digits:
            if std_ro_cnp.is_valid(body + d):
                return cc + body + d
    if cc == "CZ":
        v = _gen_rc(r)
        return "CZ" + v
    return _gen_eu_vat(r)


def _gen_eu_vat(r: random.Random) -> str:
    if r.random() < 0.2:
        return _gen_person_vat(r)
    cc = r.choice(["CZ", "CZ", "SK", "PL", "DE", "FR", "AT", "IT", "ES", "NL", "BE", "SE", "DK", "PT"])
    lengths = {"CZ": (8, 9, 10), "SK": (10,), "PL": (10,), "DE": (9,), "FR": (11,), "AT": (9,), "IT": (11,),
               "ES": (9,), "NL": (12,), "BE": (10,), "SE": (12,), "DK": (8,), "PT": (9,)}
    n = r.choice(lengths[cc])
    body = _digits(r, n - 1)
    if cc == "AT":
        body = "U" + _digits(r, n - 2)
    for d in string.digits:
        if r.random() < 0.6 and std_vat.is_valid(cc + body + d):
            return cc + body + d
    return cc + body + r.choice(string.digits)


GENERATORS = {
    "eu-vat": _gen_eu_vat,
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


# ONE property for every identifier: an ALL-ZERO payload with a correct-looking check is never issued,
# so it must be rejected, unless the identifier is exempt with a stated reason. The inventory gate
# requires every identifier with a reference to appear here, so the next validator cannot skip it.
#   identifier -> ("reject", builder) | ("exempt", reason)
_EU_VAT_STATES = ("AT", "BE", "BG", "CY", "CZ", "DE", "DK", "EE", "EL", "ES", "FI", "FR", "HR", "HU", "IE", "IT",
                  "LT", "LU", "LV", "MT", "NL", "PL", "PT", "RO", "SE", "SI", "SK", "XI")
_COMPLETIONS = string.digits + string.ascii_uppercase


def _zero_vat() -> list:
    """All-zero national part with every completion of the check position(s). CZ and SK are skipped: their
    8-digit form is an IČO, and leading-zero IČOs exist (see the cz-ico exemption). ES also gets the
    type-letter forms (CIF: letter + zeros + control, DNI: zeros + letter)."""
    out = []
    for cc in _EU_VAT_STATES:
        if cc in ("CZ", "SK"):
            continue
        for n in range(7, 13):
            zeros = "0" * n
            out.append(cc + zeros)
            out.extend(cc + zeros + c for c in _COMPLETIONS)
        if cc == "ES":
            out.extend(f"ES{lt}{'0' * 7}{c}" for lt in string.ascii_uppercase for c in _COMPLETIONS)
    return [v for v in out if std_vat.is_valid(v)]


def _zero_with_digit(length: int, ok):
    return lambda: [("0" * (length - 1)) + d for d in string.digits if ok(("0" * (length - 1)) + d)]


ZERO_POLICY = {
    "cz-rc": ("reject", lambda: ["0" * 9, "0" * 10]),
    "cz-account": ("reject", lambda: ["0000000000/0800", "0-0000000000/0800"]),
    "cz-ico": ("exempt", "leading-zero IČOs exist; no primary source shows an all-zero payload is unissued"),
    "sk-ico": ("exempt", "same IČO numbering as CZ; no primary source for an all-zero exclusion"),
    "pl-nip": ("reject", _zero_with_digit(10, std_nip.is_valid)),
    "fr-siren": ("reject", _zero_with_digit(9, std_siren.is_valid)),
    "eu-vat": ("reject", _zero_vat),
    "at-fn": ("reject", lambda: ["FN0" + c for c in string.ascii_lowercase] + ["FN0000" + c for c in string.ascii_lowercase]),
    "lei": ("exempt", "ISO 17442 allocates LOU prefixes; an all-zero head is not shown unissued by a source we hold"),
    "isin": ("exempt", "ISO 6166 allows numeric NSINs (all-zero test instruments appear in published examples)"),
    "iban": ("exempt", "ISO 13616 defines no all-zero exclusion; issuance is the bank's decision, not the standard's"),
    "pan-luhn": ("exempt", "isLuhnValid is a checksum primitive; issuer ranges are not its concern"),
    "currency": ("exempt", "a currency code has no numeric payload"),
}
# States whose kyb check accepts an all-zero payload today (review of #16887); each is its own defect id,
# so a fix in one state removes one marker. A state not listed must already reject it.
_ZERO_VAT_DEFECT_STATES = ()
ZERO_ISSUE = "#16890"


def zero_rows() -> list:
    """(identifier, value, expected, reason) for every reject-policy identifier. Expected is always False."""
    rows = []
    for identifier, (kind, how) in ZERO_POLICY.items():
        if kind != "reject":
            continue
        for v in how():
            reason = expected(identifier, v)[1]
            if not reason.startswith("known-defect"):
                defect = None
                if identifier == "eu-vat" and v[:2] in _ZERO_VAT_DEFECT_STATES:
                    defect = f"all-zero-eu-vat-{v[:2]}"
                elif identifier in ("at-fn",):
                    defect = f"all-zero-{identifier}"
                reason = f"known-defect {defect} {ZERO_ISSUE}" if defect else "all-zero payload is never issued (local policy)"
            rows.append((identifier, v, "false", reason))
    return rows


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
        zr = zero_rows()
        for identifier, value, verdict, reason in zr:
            fh.write(f"{identifier}\t{value}\t{verdict}\t{reason}\n")
        rows += len(zr)
    print(f"wrote {rows} oracle rows (seed {seed}) to {out}")
    return 0


def vectors(directory: pathlib.Path) -> int:
    refs = {k: v[0] for k, v in ORACLES.items()}
    bad = checked = 0
    for f in sorted(directory.glob("*.json")):
        d = json.loads(f.read_text(encoding="utf-8"))
        ref = refs.get(d["identifier"])
        if (d["identifier"] == "cz-rc" or any("[as_of]" in c["rule"] for c in d["cases"])) \
                and d.get("as_of") != AS_OF.isoformat():
            print(f"::error::{f.name}: as_of {d.get('as_of')!r} must equal the oracle's AS_OF {AS_OF.isoformat()}")
            bad += 1
        if bool(d.get("differential")) != (ref is not None):
            print(f"::error::{f.name}: declares differential={d.get('differential')} but the oracle "
                  f"{'has' if ref else 'has no'} reference for {d['identifier']!r}")
            bad += 1
        if ref is None:
            continue
        for c in d["cases"]:
            live = bool(ref(c["value"]))
            checked += 1
            if bool(c["valid"]) != expected(d["identifier"], c["value"])[0]:
                print(f"::error::{f.name} {c['value']!r} ({c['rule']}): vector says valid={c['valid']} but the oracle's "
                      f"narrowed expectation is {expected(d['identifier'], c['value'])[0]} — vector and oracle disagree")
                bad += 1
            origin = c.get("origin")
            if origin not in ("synthetic", "published"):
                print(f"::error::{f.name} {c['value']!r}: origin must be 'synthetic' or 'published', got {origin!r}")
                bad += 1
            elif origin == "published" and not str(c.get("cite", "")).startswith("https://"):
                print(f"::error::{f.name} {c['value']!r}: a published value needs an https `cite`")
                bad += 1
            if c.get("policy") not in ("spec", "local") or (c.get("policy") == "local" and not c.get("policy_reason")):
                print(f"::error::{f.name} {c['value']!r}: policy must be 'spec', or 'local' with a policy_reason")
                bad += 1
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
    # a known defect tags only rows the reference REJECTS: a valid value from a shape-only member
    # state must never be tagged (a regression rejecting it has to fail, not be tolerated).
    r = random.Random(3)
    valid_it = next(v for v in (_gen_eu_vat(r) for _ in range(5000)) if v[:2] == "IT" and std_vat.is_valid(v))
    ok &= expected("eu-vat", valid_it) == (True, "stdnum")
    r = random.Random(5)
    future_cz = next(v for v in (_gen_person_vat(r) for _ in range(5000))
                     if v[:2] == "CZ" and (_vat_birthdate(v) or AS_OF) > AS_OF and std_vat.is_valid(v))
    ok &= expected("eu-vat", future_cz) == (False, "known-defect eu-vat-future-birthdate-CZ #16896")
    # the future-date property is not vacuous: each natural-person form yields rows the reference ACCEPTS (so the
    # expectation really narrows it) and rows after as_of
    r = random.Random(4)
    seen = {}
    for _ in range(8000):
        v = _gen_person_vat(r)
        d = _vat_birthdate(v)
        if d is not None and std_vat.is_valid(v):
            seen.setdefault(v[:2], set()).add(d > AS_OF)
    ok &= all(seen.get(cc) == {True, False} for cc in ("BG", "LV", "RO", "CZ"))
    # the all-zero property is not vacuous: every reject-policy identifier yields rows, and where the reference
    # has a check, at least one row the reference ACCEPTS (a correct-looking check), so rejecting it is real work
    for identifier, (kind, how) in ZERO_POLICY.items():
        if kind == "reject":
            cands = how()
            ok &= bool(cands) and (identifier in ("cz-rc",) or any(ORACLES[identifier][0](v) for v in cands))
        else:
            ok &= bool(how.strip())
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
