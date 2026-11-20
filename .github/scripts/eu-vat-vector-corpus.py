#!/usr/bin/env python3
"""Deterministic synthetic EU VAT test-vector corpus for openbank-kyb-service (#16774).

Every number printed is SYNTHETIC and never a real company or person: it is drawn from a fixed
seed over random digits. The first column says what a vector is kept for:

  valid          kept only if python-stdnum's stdnum.eu.vat accepts it AND no local-policy rule rejects it
  invalid        kept only if python-stdnum rejects it
  policy-invalid python-stdnum ACCEPTS it, kyb rejects it on purpose (all-zero payload, XI 12-digit zero
                 base, LV 7th digit above 2, BE remainder 0,
                 IE old form with a 9th character, FR Monaco SIREN 000000000, a birth date in the future
                 in an RO CNP, LV personal code or BG EGN)
  clock-valid / clock-invalid
                 birth dates on the local-civil-date boundary (RO Europe/Bucharest, LV Europe/Riga, BG
                 Europe/Sofia), valid / invalid at a fixed clock the Kotlin boundary test sets

Regenerate the lists in EuVatChecksumsTest.kt with:

    python3 -m venv /tmp/v && /tmp/v/bin/pip install python-stdnum==2.2
    /tmp/v/bin/python .github/scripts/eu-vat-vector-corpus.py

Check mode, for CI: `eu-vat-vector-corpus.py --check [path-to-EuVatChecksumsTest.kt]` exits 1 when a
regeneration and the committed test file disagree: a generated number missing from the file, or a
number in the file's three lists that no regeneration produces. Prints nothing and exits 0 when they match.

Only the standard library and python-stdnum are used. The policy rules mirror the Kotlin ones and
are listed in POLICY below; stdnum has none of them.
"""
import datetime
import pathlib
import random
import re
import string
import sys

from stdnum.bg import egn as bg_egn
from stdnum.bg import pnf as bg_pnf
from stdnum.eu import vat
from stdnum.sk import rc as sk_rc

TODAY = datetime.date(2026, 11, 19)  # the fixed clock of the Kotlin RO tests
R = random.Random(20261119)
L = string.ascii_uppercase


def D(k):
    return "".join(R.choice(string.digits) for _ in range(k))


def nonzero(k):
    return str(R.randint(1, 9)) + D(k - 1)


def lv_date(n):
    return datetime.date(1800 + int(n[6]) * 100 + int(n[4:6]), int(n[2:4]), int(n[0:2]))


def egn_date(n):
    year, month = 1900 + int(n[0:2]), int(n[2:4])
    if month > 40:
        year, month = year + 100, month - 40
    elif month > 20:
        year, month = year - 100, month - 20
    return datetime.date(year, month, int(n[4:6]))


def bg_other(n):
    w = (4, 3, 2, 7, 6, 5, 4, 3, 2)
    return (11 - sum(a * int(b) for a, b in zip(w, n[:9], strict=True))) % 11 == int(n[9])


def bg_kyb_valid(n):
    """A 10-digit BG number as kyb reads it: a not-future EGN, or a PNF, or the 'other' rule."""
    try:
        egn_ok = bg_egn.is_valid(n) and egn_date(n) <= TODAY
    except ValueError:
        egn_ok = False
    return egn_ok or bg_pnf.is_valid(n) or bg_other(n)


def sk_kyb_valid(n):
    """A 10-digit SK number as kyb reads it: the official rule, or an RC that libs RodneCislo accepts."""
    if n[0] != "0" and n[2] in "234789" and int(n) % 11 == 0:
        return True
    year = 1900 + int(n[:2]) if int(n[:2]) > 53 else 2000 + int(n[:2])
    return sk_rc.is_valid(n) and not (int(n[:9]) % 11 == 10 and year > 1985)


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
    if cc == "BG" and len(n) == 10 and vat.is_valid(v) and not bg_kyb_valid(n):
        return "BG EGN birth date in the future"
    if cc == "LV" and len(n) == 11 and n[0] <= "3" and not n.startswith("32"):
        try:
            if lv_date(n) > TODAY:
                return "LV future birth date"
        except ValueError:
            pass
    if cc == "SK" and len(n) == 10 and vat.is_valid(v) and not sk_kyb_valid(n):
        return "SK RC remainder 10 after 1985 (libs RodneCislo is stricter than stdnum)"
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
    # stdnum sk/dph.py accepts a valid Slovak RC OR the official rule, so a mod-11 number with a
    # third digit outside 2,3,4,7,8,9 is valid only if it is also a valid RC (third digit 0,1,5,6).
    emit("SK third=%s" % third, "valid" if vat.is_valid("SK" + n) else "invalid", "SK" + n)
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

# ---- LV personal code and BG EGN with an explicit birth date -------------------------------------
def lv_person_on(date):
    for _ in range(100000):
        body = "%02d%02d%02d%d%s" % (date.day, date.month, date.year % 100, (date.year - 1800) // 100, D(3))
        for c in string.digits:
            if vat.is_valid("LV" + body + c):
                return "LV" + body + c
    raise SystemExit("no LV vector")


def egn_on(date):
    month = date.month + (40 if date.year >= 2000 else 20 if date.year < 1900 else 0)
    for _ in range(100000):
        body = "%02d%02d%02d%s" % (date.year % 100, month, date.day, D(3))
        for c in string.digits:
            n = body + c
            if bg_egn.is_valid(n) and not bg_pnf.is_valid(n) and not bg_other(n):
                return "BG" + n
    raise SystemExit("no BG vector")


for label, make in (("LV", lv_person_on), ("BG EGN", egn_on)):
    emit(label + " born 1985", "valid", make(datetime.date(1985, 3, 4)))
    emit(label + " born today", "valid", make(TODAY))
    emit(label + " born tomorrow", "policy-invalid", make(TODAY + datetime.timedelta(days=1)))
    emit(label + " born 2067", "policy-invalid", make(datetime.date(2067, 6, 15)))

# ---- SK: both branches of stdnum/sk/dph.py `validate` (a valid RC, or the official rule) --------------
def sk_rc_on(year, month, day, libs_rejects=False):
    for _ in range(100000):
        n = "%02d%02d%02d%s" % (year % 100, month, day, D(4))
        if sk_rc.is_valid(n) and vat.is_valid("SK" + n) and (policy_reason("SK" + n) is not None) == libs_rejects:
            return "SK" + n
    raise SystemExit("no SK RC")


# third digit 0/1/5/6 is a month digit of an RC, so these are valid ONLY through the RC branch
emit("SK RC man 1984", "valid", sk_rc_on(1984, 3, 12))
emit("SK RC woman 1991", "valid", sk_rc_on(1991, 55, 20))
emit("SK RC 2005", "valid", sk_rc_on(2005, 6, 15))
emit("SK RC 2005 remainder 10", "policy-invalid", sk_rc_on(2005, 6, 16, libs_rejects=True))
while True:  # RC-shaped, third digit 5, not divisible by 11: neither branch accepts it
    n = "840" + "5" + "12" + D(4)
    if not vat.is_valid("SK" + n):
        emit("SK RC shape, bad check", "invalid", "SK" + n)
        break

# ---- XI: the 12-digit form checks its first nine digits, so the zero guard must read the same nine ----
for v in ("XI000000000123", "XI000000000999", "XI000000000"):
    assert vat.is_valid(v), v
    emit("XI zero base " + str(len(v) - 2) + " digits", "policy-invalid", v)

# ---- LV: a 7th digit above 2 decodes (stdnum year += 1800 + digit*100) to 2100 or later ------------------
while True:
    body = "150667" + "3" + D(3)
    for c in string.digits:
        if vat.is_valid("LV" + body + c):
            emit("LV 7th digit 3", "policy-invalid", "LV" + body + c)
            break
    else:
        continue
    break

# ---- RO: first digit 7, 8, 9 carries no century; stdnum/ro/cnp.py `get_birth_date` assumes 1900 ------------
for first in "789":
    pair_v = None
    for _ in range(100000):
        v = "RO%s%02d%02d%02d%02d%s" % (first, R.randint(30, 99), R.randint(1, 12), R.randint(1, 28), R.choice([1, 2, 12, 40]), D(4))
        if vat.is_valid(v):
            pair_v = v
            break
    emit("RO CNP foreign " + first, "valid", pair_v)
for _ in range(100000):
    v = "RO7%02d0230%02d%s" % (R.randint(30, 99), R.choice([1, 2, 12, 40]), D(4))  # 30 February
    if not vat.is_valid(v):
        emit("RO CNP foreign 7, 30 February", "invalid", v)
        break

# ---- NL: the shape is the published one (NL + 9 digits + B + 2 digits); MOD 97-10 alone is not enough ----
nl_bad = "NL" + D(9) + "B" + D(3)
assert not vat.is_valid(nl_bad)
emit("NL 3-digit suffix", "invalid", nl_bad)


# ---- boundary dates for the per-state civil-date tests (2026-07-15 summer, 2026-01-15 winter) ---------
for day in (datetime.date(2026, 7, 15), datetime.date(2026, 7, 16), datetime.date(2026, 1, 15), datetime.date(2026, 1, 16)):
    for label, make in (("RO CNP", lambda d: cnp("5", d)), ("LV", lv_person_on), ("BG EGN", egn_on)):
        emit("%s born %s" % (label, day.isoformat()), "clock-" + ("valid" if day.day == 15 else "invalid"), make(day))

def check(path):
    text = pathlib.Path(path).read_text(encoding="utf-8")
    generated = {v for _, _, v in OUT}
    listed = set()
    for name in ("valid", "invalid", "policyInvalid"):
        m = re.search(r"private val %s = listOf\(\n(.*?)\n    \)\n" % name, text, re.S)
        listed |= set(re.findall(r'^\s+"([A-Z]{2}[0-9A-Z]+)",', m.group(1), re.M))
    missing = sorted(v for v in generated if '"%s"' % v not in text)
    stale = sorted(listed - generated)
    for v in missing:
        print("missing from the test file:", v)
    for v in stale:
        print("in the test file but not generated:", v)
    return 1 if missing or stale else 0


if len(sys.argv) > 1 and sys.argv[1] == "--check":
    default = pathlib.Path(__file__).resolve().parents[2] / "openbank-kyb-service/src/test/kotlin/com/openbank/kyb/domain/EuVatChecksumsTest.kt"
    sys.exit(check(sys.argv[2] if len(sys.argv) > 2 else default))
for tag, kind, v in OUT:
    print("%s\t%s\t%s" % (kind, v, tag))
