#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""ADR-0320 P3: field encryption belongs in libs (com.openbank.libs.security.FieldProtector).

Flags a service's src/main Kotlin that uses javax.crypto.Cipher directly or calls an OpenBao/Vault
Transit encrypt/decrypt/rewrap path, outside the shrink-only baseline. Comment lines are ignored so
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

PATTERNS = [
    re.compile(r"\bjavax\.crypto\.(Cipher\b|\*)"),
    re.compile(r"\bCipher\.getInstance\s*\("),
    re.compile(r"/(encrypt|decrypt|rewrap)/"),
]
COMMENT = re.compile(r"^\s*(//|\*|/\*)")
BASELINE = Path(".github/scripts/field-crypto-in-libs-baseline.txt")


def offending_lines(text: str) -> list[int]:
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
        if COMMENT.match(line):
            continue
        code = line.split("//", 1)[0] if '"' not in line else line
        if any(p.search(code) for p in PATTERNS):
            hits.append(n)
    return hits


def scan(root: Path) -> tuple[dict[str, list[int]], int]:
    found: dict[str, list[int]] = {}
    scanned = 0
    for f in sorted(root.glob("openbank-*/src/main/**/*.kt")):
        rel = f.relative_to(root).as_posix()
        if rel.startswith("openbank-libs"):
            continue
        scanned += 1
        lines = offending_lines(f.read_text(encoding="utf-8", errors="replace"))
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
        "val c = Cipher.getInstance(\"AES/GCM/NoPadding\")",
        '.uri(URI.create("$addr/v1/$mount/decrypt/$key"))',
        'post("/v1/transit/encrypt/pan")',
    ]
    must_not_flag = [
        "// Transit /v1/transit/encrypt/<key> wraps the DEK",
        " * `vault write transit/decrypt/card-pan`",
        "import javax.crypto.Mac",
        "import com.openbank.libs.security.FieldProtector",
        "val x = protector.encrypt(bytes)",
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
    if offending_lines("/*\n Cipher.getInstance(x)\n*/") :
        print("SELFTEST FAIL: flagged inside a block comment")
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
        print(f"::error file={f},line={found[f][0]}::service-local javax.crypto.Cipher / Transit "
              f"encrypt|decrypt|rewrap call — use com.openbank.libs.security.FieldProtector (ADR-0320 P3)")
    for f in stale:
        print(f"::error file={f}::baseline entry no longer matches — remove it from {BASELINE}")
    return 1 if new or stale else 0


if __name__ == "__main__":
    sys.exit(main())
