#!/usr/bin/env python3
"""Deterministic synthetic EU VAT test-vector corpus for openbank-kyb-service (#16774).

Every number printed is SYNTHETIC and never a real company or person: it is drawn from a fixed
seed over random digits. The first column says what a vector is kept for:

  valid          kept only if python-stdnum's stdnum.eu.vat accepts it AND no local-policy rule rejects it
  invalid        kept only if python-stdnum rejects it
  policy-invalid python-stdnum ACCEPTS it, kyb rejects it on purpose (all-zero payload, BE remainder 0,
                 IE old form with a 9th character, FR Monaco SIREN 000000000, RO birth date in the future)

Regenerate the lists in EuVatChecksumsTest.kt with:

    python3 -m venv /tmp/v && /tmp/v/bin/pip install python-stdnum==2.2
    /tmp/v/bin/python .github/scripts/eu-vat-vector-corpus.py

Only the standard library and python-stdnum are used. The policy rules mirror the Kotlin ones and
are listed in POLICY below; stdnum has none of them.
"""
import datetime
import random
import string

from stdnum.eu import vat

TODAY = datetime.date(2026, 11, 19)  # the fixed clock of the Kotlin RO tests
R = random.Random(20261119)
L = string.ascii_uppercase


def D(k):
    return "".join(R.choice(string.digits) for _ in range(k))


def nonzero(k):
    return str(R.randint(1, 9)) + D(k - 1)


def policy_reason(v):
    """Why kyb rejects a number stdnum accepts, or None."""
    cc, n = v[:2], v[2:]
    digits = [c for c in n if c.isdigit()]
    if digits and all(c == "0" for c in digits):
        return "all-zero payload"
    if cc == "BE" and (int(n[:8]) % 97 == 0 and n[8:] != "97" or int(n[:8]) == 0):
        return "BE remainder 0 / zero base"
    if cc == "IE" and len(n) == 9 and not n[:7].isdigit():
        return "IE old form with 9th character"
    if cc == "FR" and n[2:] == "000000000":
        return "FR zero SIREN"
    if cc == "RO" and len(n) == 13:
        century = {"3": 1800, "4": 1800, "5": 2000, "6": 2000}.get(n[0], 1900)
        try:
            if datetime.date(century + int(n[1:3]), int(n[3:5]), int(n[5:7])) > TODAY:
                return "RO future birth date"
        except ValueError:
            pass
    return None


OUT = []


def emit(tag, kind, v):
    OUT.append((tag, kind, v))


def find(tag, shape, want, tries=400000):
    for _ in range(tries):
        v = shape()
        if want and vat.is_valid(v) and policy_reason(v) is None:
            return v
        if not want and not vat.is_valid(v):
            return v
    raise SystemExit("no vector for " + tag)


def pair(tag, shape, n_valid=2, n_invalid=2):
    seen = set()
    for kind, want, n in (("valid", True, n_valid), ("invalid", False, n_invalid)):
        got = 0
        while got < n:
            v = find(tag, shape, want)
            if v not in seen:
                seen.add(v)
                emit(tag, kind, v)
                got += 1


# ---- one pair per state --------------------------------------------------------------------
BASE = {
    "ES": lambda: "ES" + D(8) + R.choice(L),
    "IT": lambda: "IT" + D(10) + "0",
    "NL": lambda: "NL" + D(9) + "B" + "%02d" % R.randint(1, 99),
    "BE": lambda: "BE0" + D(9),
    "PT": lambda: "PT" + nonzero(9),
    "IE": lambda: "IE" + D(7) + "W",
    "DK": lambda: "DK" + nonzero(8),
    "FI": lambda: "FI" + D(8),
    "SE": lambda: "SE" + D(10) + "01",
    "LU": lambda: "LU" + D(8),
    "SI": lambda: "SI" + nonzero(8),
    "HR": lambda: "HR" + D(11),
    "HU": lambda: "HU" + D(8),
    "RO": lambda: "RO" + nonzero(R.randint(2, 10)),
    "BG": lambda: "BG" + D(9),
    "EE": lambda: "EE" + D(9),
    "LV": lambda: "LV" + str(R.randint(4, 9)) + D(10),
    "LT": lambda: "LT" + D(7) + "1" + D(1),
    "EL": lambda: "EL" + D(9),
    "CY": lambda: "CY" + D(8) + "A",
    "MT": lambda: "MT" + nonzero(8),
    "XI": lambda: "XI" + D(9),
}
for cc, shape in BASE.items():
    pair(cc, shape)

# ---- SK: one vector per third digit (the official set is 2,3,4,7,8,9) ----------------------
for third in string.digits:
    while True:
        n = str(R.randint(1, 9)) + D(1) + third + D(7)
        if int(n) % 11 == 0:
            break
    # stdnum accepts a Slovak birth number first, so a mod-11 number with a bad third digit is
    # only "invalid" by the official rule: kept as policy-invalid when stdnum accepts it.
    ok = vat.is_valid("SK" + n)
    emit("SK third=%s" % third, "valid" if third in "234789" else ("policy-invalid" if ok else "invalid"), "SK" + n)
n = [v for t, k, v in OUT if t == "SK third=2"][0][2:]
emit("SK", "invalid", "SK" + n[:9] + str((int(n[9]) + 1) % 10))
while True:
    n = "0" + D(1) + R.choice("234789") + D(7)
    if int(n) % 11 == 0:
        emit("SK first=0", "invalid", "SK" + n)
        break

# ---- ES: DNI / NIE / K / CIF letter-vs-digit per organisation letter ------------------------
pair("ES NIE", lambda: "ES" + R.choice("XYZ") + D(7) + R.choice(L), 1, 1)
pair("ES K", lambda: "ES" + "K" + D(7) + R.choice(L), 1, 1)
for first in "ABEHPQRSNWCDFGJUV":
    pair("ES CIF %s digit-control" % first, lambda f=first: "ES" + f + D(7) + R.choice(string.digits), 1, 0)
    pair("ES CIF %s letter-control" % first, lambda f=first: "ES" + f + D(7) + R.choice("JABCDEFGHI"), 1, 0)
    pair("ES CIF %s" % first, lambda f=first: "ES" + f + D(7) + R.choice(string.digits + "JABCDEFGHI"), 0, 1)

# ---- IE old form, two-letter form ------------------------------------------------------------
pair("IE old", lambda: "IE" + D(1) + R.choice("ABCDEFG") + D(5) + R.choice("WABCDEFGHIJKLMNOPQRSTUV"), 1, 1)
pair("IE two letters", lambda: "IE" + D(7) + R.choice("WABCDEFGHIJKLMNOPQRSTUV") + R.choice("ABH"), 1, 1)
while True:  # an old-form number carrying a 9th character: stdnum ignores it, kyb rejects it
    v = "IE" + D(1) + R.choice("ABCDEFG") + D(5) + R.choice("WABCDEFGHIJKLMNOPQRSTUV") + R.choice("WABH")
    if vat.is_valid(v):
        emit("IE old+9th", "policy-invalid", v)
        break

# ---- BE: remainder-0 numbers ------------------------------------------------------------------
# first8 % 97 == 0, so stdnum accepts last two digits 00 and 97; kyb accepts only 97 (the check is
# 97 - remainder, and a remainder of 0 gives 97).
for last2 in ("00", "97"):
    first8 = 97 * R.randint(1, 20000000 // 97)  # first digit 0 or 1, as the rule requires
    v = "BE%08d%s" % (first8, last2)
    assert vat.is_valid(v), v
    assert (policy_reason(v) is None) == (last2 == "97"), v
    emit("BE remainder 0 / last=" + last2, "policy-invalid" if last2 == "00" else "valid", v)

# ---- NL: the MOD 97-10 form ------------------------------------------------------------------
while True:
    v = "NL" + D(9) + "B" + "%02d" % R.randint(1, 99)
    n = v[2:11]
    bsn = sum((9 - i) * int(n[i]) for i in range(8)) - int(n[8])
    if bsn % 11 != 0 and vat.is_valid(v):
        emit("NL mod97-only", "valid", v)
        break

# ---- BG, LT, XI long forms ---------------------------------------------------------------------
pair("BG10", lambda: "BG" + D(10), 1, 1)
pair("LT12", lambda: "LT" + D(10) + "1" + D(1), 1, 1)
pair("XI12", lambda: "XI" + D(12), 1, 1)
for v in ("XIGD000", "XIGD499", "XIHA500", "XIHA999"):
    emit("XI " + v[2:], "policy-invalid" if v == "XIGD000" else "valid", v)
for v in ("XIGD500", "XIHA499", "XIGD8888499", "XIGD1"):
    emit("XI " + v[2:], "invalid", v)
assert all(vat.is_valid(v) == (k == "valid" or k == "policy-invalid") for t, k, v in OUT if t.startswith("XI G") or t.startswith("XI H"))

# ---- LV: legal entity, personal code, and the sum-complement variant --------------------------
pair("LV person", lambda: "LV" + "%02d" % R.randint(1, 28) + "%02d" % R.randint(1, 12) + D(2) + "0" + D(4), 1, 1)
pair("LV 32-code", lambda: "LV32" + D(9), 1, 1)
W = (10, 5, 8, 4, 2, 1, 6, 3, 7, 9)
while True:  # valid under (1101 - sum) mod 11 mod 10 but not under stdnum's (1 + sum) mod 11 mod 10
    body = "%02d%02d%02d0%03d" % (R.randint(1, 28), R.randint(1, 12), R.randint(0, 99), R.randint(0, 999))
    s = sum(w * int(c) for w, c in zip(W, body, strict=False))
    alt = (1101 - s) % 11 % 10
    v = "LV" + body + str(alt)
    if not vat.is_valid(v):
        emit("LV 1101-variant", "invalid", v)
        break

# ---- SI: the 11 - sum mod 11 rule and its two edge remainders ---------------------------------
for rem, last, kind in ((0, "0", "invalid"), (1, "0", "valid")):
    while True:
        body = nonzero(7)
        s = sum((8 - i) * int(body[i]) for i in range(7))
        if s % 11 == rem:
            break
    emit("SI remainder %d last 0" % rem, kind, "SI" + body + last)
    assert bool(vat.is_valid("SI" + body + last)) == (kind == "valid")

# ---- FR: numeric key, alphanumeric key, Monaco ----------------------------------------------------
ALPHA = "0123456789ABCDEFGHJKLMNPQRSTUVWXYZ"


def fr():
    return "FR" + R.choice(ALPHA) + R.choice("ABCDEFGHJKLMNPQRSTUVWXYZ") + D(9)


pair("FR alpha key", fr, 2, 2)
pair("FR numeric key", lambda: "FR" + D(2) + D(9), 1, 1)
emit("FR Monaco zero SIREN", "policy-invalid", "FR12000000000")
assert vat.is_valid("FR12000000000")

# ---- RO: CNP with an explicit birth date ---------------------------------------------------------
def cnp(first, date):
    for _ in range(100000):
        v = "RO%s%02d%02d%02d%02d%s" % (first, date.year % 100, date.month, date.day, R.choice([1, 2, 12, 40]), D(4))
        if vat.is_valid(v):
            return v


emit("RO CNP born 2005", "valid", cnp("5", datetime.date(2005, 6, 15)))
emit("RO CNP born today", "valid", cnp("5", TODAY))
emit("RO CNP born tomorrow", "policy-invalid", cnp("5", TODAY + datetime.timedelta(days=1)))
emit("RO CNP born 2067", "policy-invalid", cnp("6", datetime.date(2067, 6, 15)))
pair("RO CNP", lambda: "RO" + R.choice("1256") + D(12), 1, 1)

for tag, kind, v in OUT:
    print("%s\t%s\t%s" % (kind, v, tag))
