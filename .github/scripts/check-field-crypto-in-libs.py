#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""ADR-0320 P3: field encryption belongs in libs (com.openbank.libs.security.FieldProtector).

Flags a service's src/main Kotlin/Java that holds key material through JCA (javax.crypto Cipher,
Mac, SecretKeySpec, KeyGenerator), BouncyCastle or Tink, sends X-Vault-Token, or names an
OpenBao/Vault Transit encrypt/decrypt/rewrap/datakey path (literal or concatenated), plus SQL that
calls pgp_sym_encrypt/pgp_sym_decrypt — outside the shrink-only baseline. Comment lines are ignored so
a KDoc describing Transit does not count. openbank-libs* modules are exempt: that is where the
primitive lives.

Exit 1 on a NEW offending file or a STALE baseline entry (both directions, so the list cannot rot).
Runs `advisory` until card-issuance has migrated (ADR-0320: advisory first, enforced after one
migrated consumer).
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

# Signals, each a way a service grows its own copy of key handling. MessageDigest/Signature are
# deliberately absent: hashing and signature verification are widely legitimate and are not field
# encryption.
KOTLIN_PATTERNS = [
    # JCA primitives that hold key material: Cipher, Mac, SecretKeySpec, KeyGenerator (+ wildcard).
    re.compile(r"\bjavax\.crypto\.(spec\.)?(Cipher|Mac|KeyGenerator|SecretKeySpec|\*)"),
    re.compile(r"\b(Cipher|Mac|KeyGenerator)\.getInstance\s*\("),
    re.compile(r"\bSecretKeySpec\s*\("),
    # Third-party crypto stacks.
    re.compile(r"\borg\.bouncycastle\."),
    re.compile(r"\bcom\.google\.crypto\.tink\."),
    # Talking to OpenBao/Vault directly.
    re.compile(r"X-Vault-Token", re.IGNORECASE),
    # A Transit operation path, literal: "transit/encrypt/...", "/v1/transit/rewrap".
    re.compile(r"transit/+(encrypt|decrypt|rewrap|datakey)\b", re.IGNORECASE),
    # Any /v1/<mount>/<op> shape with an interpolated or custom mount: "/v1/$mount/decrypt/$key".
    # `(?<!api)` keeps an application route such as "/api/v1/cards/encrypt/..." out.
    re.compile(r"(?<!api)/v1/[^\"\s/]+/(encrypt|decrypt|rewrap|datakey)/"),
]
# Concatenated forms — `"transit" + "/encrypt/"`, `"${transitMount}/rewrap/$k"` — split the
# literal, so the op fragment counts only on a line that also names transit or a mount.
OP_FRAGMENT = re.compile(r"\"[^\"]*\b(encrypt|decrypt|rewrap|datakey)/[^\"]*\"")
TRANSIT_CONTEXT = re.compile(r"transit|mount", re.IGNORECASE)
SQL_PATTERNS = [re.compile(r"\bpgp_sym_(en|de)crypt\s*\(", re.IGNORECASE)]
COMMENT = re.compile(r"^\s*(//|\*|/\*)")
SQL_COMMENT = re.compile(r"^\s*--")
BASELINE = Path(".github/scripts/field-crypto-in-libs-baseline.txt")
SUFFIXES = (".kt", ".java", ".sql")


def _code_hit(code: str) -> bool:
    if any(p.search(code) for p in KOTLIN_PATTERNS):
        return True
    return bool(OP_FRAGMENT.search(code) and TRANSIT_CONTEXT.search(code))


def offending_lines(text: str, sql: bool = False) -> list[int]:
    hits, in_block = [], False
    for n, line in enumerate(text.splitlines(), 1):
        stripped = line.strip()
        if in_block:
            if "*/" in stripped:
                in_block = False
            continue
        if stripped.startswith("/*") and "*/" not in stripped:
            in_block = True
            continue
        if sql:
            if SQL_COMMENT.match(line):
                continue
            code = line.split("--", 1)[0]
            if any(p.search(code) for p in SQL_PATTERNS):
                hits.append(n)
            continue
        if COMMENT.match(line):
            continue
        code = line.split("//", 1)[0] if '"' not in line else line
        if _code_hit(code):
            hits.append(n)
    return hits


def scan(root: Path) -> tuple[dict[str, list[int]], int]:
    found: dict[str, list[int]] = {}
    scanned = 0
    for f in sorted(root.glob("openbank-*/src/main/**/*")):
        if f.suffix not in SUFFIXES or not f.is_file():
            continue
        rel = f.relative_to(root).as_posix()
        if rel.startswith("openbank-libs"):
            continue
        scanned += 1
        lines = offending_lines(f.read_text(encoding="utf-8", errors="replace"), sql=f.suffix == ".sql")
        if lines:
            found[rel] = lines
    return found, scanned


def load_baseline(path: Path) -> set[str]:
    if not path.exists():
        return set()
    return {l.strip() for l in path.read_text().splitlines() if l.strip() and not l.startswith("#")}


def evaluate(found: dict[str, list[int]], baseline: set[str]) -> tuple[list[str], list[str]]:
    new = sorted(set(found) - baseline)
    stale = sorted(baseline - set(found))
    return new, stale


def self_test() -> int:
    must_flag = [
        "import javax.crypto.Cipher",
        "import javax.crypto.*",
        "import javax.crypto.Mac",
        "import javax.crypto.KeyGenerator",
        "import javax.crypto.spec.SecretKeySpec",
        "import javax.crypto.spec.*",
        "val c = Cipher.getInstance(\"AES/GCM/NoPadding\")",
        "val m = Mac.getInstance(\"HmacSHA256\")",
        "val k = SecretKeySpec(bytes, \"AES\")",
        "import org.bouncycastle.crypto.engines.AESEngine",
        "import com.google.crypto.tink.Aead",
        "import javax.crypto.Cipher;",  # Java
        '.header("X-Vault-Token", token)',
        '.uri(URI.create("$addr/v1/$mount/decrypt/$key"))',
        'post("/v1/transit/encrypt/pan")',
        'val p = "transit/rewrap/" + key',
        'val p = "transit" + "/encrypt/" + key',
        'val p = "${transitMount}/decrypt/$key"',
        'val p = "v1/transit/datakey/plaintext/k"',
    ]
    must_not_flag = [
        "// Transit /v1/transit/encrypt/<key> wraps the DEK",
        " * `vault write transit/decrypt/card-pan`",
        "import java.security.MessageDigest",
        "import java.security.Signature",
        "import com.openbank.libs.security.FieldProtector",
        "val x = protector.encrypt(bytes)",
        '@Path("/api/v1/cards/{id}/encrypt/preview")',  # an app route, not Transit
        'val route = "/documents/decrypt/status"',
    ]
    ok = True
    for s in must_flag:
        if not offending_lines(s):
            print(f"SELFTEST FAIL: did not flag: {s}")
            ok = False
    for s in must_not_flag:
        if offending_lines(s):
            print(f"SELFTEST FAIL: flagged: {s}")
            ok = False
    if offending_lines("/*\n Cipher.getInstance(x)\n*/"):
        print("SELFTEST FAIL: flagged inside a block comment")
        ok = False
    if not offending_lines("SELECT pgp_sym_encrypt(pan, key) FROM t;", sql=True):
        print("SELFTEST FAIL: did not flag pgp_sym_encrypt in SQL")
        ok = False
    if offending_lines("-- pgp_sym_encrypt(pan, key) was the old scheme", sql=True):
        print("SELFTEST FAIL: flagged a SQL comment")
        ok = False
    new, stale = evaluate({"a.kt": [1], "b.kt": [2]}, {"b.kt", "c.kt"})
    if new != ["a.kt"] or stale != ["c.kt"]:
        print(f"SELFTEST FAIL: evaluate new={new} stale={stale}")
        ok = False
    print("self-test " + ("passed" if ok else "FAILED"))
    return 0 if ok else 1


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--root", default=".")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    root = Path(args.root)
    found, scanned = scan(root)
    print(f"SUBJECTS={scanned}")
    new, stale = evaluate(found, load_baseline(root / BASELINE))
    print(f"field-crypto-in-libs: {len(found)} service file(s) with local field crypto "
          f"({len(found) - len(new)} baselined)")
    for f in new:
        print(f"::error file={f},line={found[f][0]}::service-local field crypto (JCA/BouncyCastle/Tink/"
              f"Transit/pgp_sym) — use com.openbank.libs.security.FieldProtector (ADR-0320 P3)")
    for f in stale:
        print(f"::error file={f}::baseline entry no longer matches — remove it from {BASELINE}")
    return 1 if new or stale else 0


if __name__ == "__main__":
    sys.exit(main())
