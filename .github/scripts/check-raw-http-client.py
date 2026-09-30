#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Ratchet on raw `java.net.http.HttpClient` construction in service code — ADR-0320 P1.
#
# A service that builds its own HttpClient has no egress allowlist, no private-range denial after
# DNS resolution and no rebinding protection. The shared answer is
# `com.openbank.libs.security.SafeHttpClient` (openbank-libs-runtime). This gate does not force a
# migration; it forbids a NEW construction site in any `<service>/src/main` outside openbank-libs-*,
# and reports a baseline entry that has healed so the list only shrinks.
#
# The baseline is a per-file COUNT (`path<TAB>n`), so a second raw client added to an already
# baselined file still fails. Starts advisory (ADR-0320: flips to enforced once P1 has one migrated
# consumer).
#
# EXIT CODES
#   0  no file exceeds its baseline count
#   1  a new raw construction site, or a stale baseline entry
#   2  could not run / self-test failed
#
# Run: python3 .github/scripts/check-raw-http-client.py [--root .] [--self-test] [--print-baseline]

import argparse
import pathlib
import re
import sys

PATTERN = re.compile(r"\bHttpClient\s*\.\s*(newBuilder|newHttpClient)\s*\(")
BASELINE_REL = ".github/scripts/raw-http-client-baseline.txt"
EXTENSIONS = {".kt", ".java"}


def source_files(root: pathlib.Path):
    for module in sorted(root.iterdir()):
        if not module.is_dir() or not module.name.startswith("openbank-") or module.name.startswith("openbank-libs"):
            continue
        main = module / "src" / "main"
        if not main.is_dir():
            continue
        for p in sorted(main.rglob("*")):
            if p.is_file() and p.suffix in EXTENSIONS:
                yield p


def scan(root: pathlib.Path):
    counts, files = {}, 0
    for p in source_files(root):
        files += 1
        text = p.read_text(encoding="utf-8", errors="replace")
        # ignore line comments so a KDoc mention is not a construction site
        code = "\n".join(line.split("//", 1)[0] for line in text.splitlines())
        n = len(PATTERN.findall(code))
        if n:
            counts[str(p.relative_to(root))] = n
    return counts, files


def load_baseline(path: pathlib.Path):
    out = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        f, n = line.split("\t")
        out[f] = int(n)
    return out


def evaluate(counts, baseline):
    errors = []
    for f, n in sorted(counts.items()):
        allowed = baseline.get(f, 0)
        if n > allowed:
            errors.append(f"NEW raw HttpClient construction in {f}: {n} site(s), baseline {allowed} — use SafeHttpClient (ADR-0320 P1)")
    for f, allowed in sorted(baseline.items()):
        if counts.get(f, 0) < allowed:
            errors.append(f"STALE baseline: {f} now has {counts.get(f, 0)} (baseline {allowed}) — lower or remove the entry")
    return errors


def run(root: pathlib.Path) -> int:
    bpath = root / BASELINE_REL
    if not bpath.is_file():
        print(f"baseline {BASELINE_REL} missing", file=sys.stderr)
        return 2
    counts, files = scan(root)
    errors = evaluate(counts, load_baseline(bpath))
    print(f"SUBJECTS={files}  # service src/main source files scanned")
    print(f"raw HttpClient sites: {sum(counts.values())} in {len(counts)} file(s)")
    for e in errors:
        print(e)
    return 1 if errors else 0


def self_test() -> int:
    import tempfile

    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        svc = root / "openbank-foo-service" / "src" / "main" / "kotlin"
        svc.mkdir(parents=True)
        libs = root / "openbank-libs-runtime" / "src" / "main" / "kotlin"
        libs.mkdir(parents=True)
        tests = root / "openbank-foo-service" / "src" / "test" / "kotlin"
        tests.mkdir(parents=True)
        (svc / "A.kt").write_text("val c = HttpClient.newBuilder().build()\n")
        (svc / "B.kt").write_text("// HttpClient.newHttpClient() in a comment\nval s = SafeHttpClient(p)\n")
        (libs / "L.kt").write_text("val c = HttpClient.newHttpClient()\n")
        (tests / "T.kt").write_text("val c = HttpClient.newHttpClient()\n")
        base = root / BASELINE_REL
        base.parent.mkdir(parents=True)
        a = "openbank-foo-service/src/main/kotlin/A.kt"

        checks = []
        base.write_text(f"{a}\t1\n")
        checks.append((run(root) == 0, "baselined site passes; libs, tests and comments are ignored"))
        (svc / "A.kt").write_text("val c = HttpClient.newBuilder().build()\nval d = HttpClient . newHttpClient()\n")
        checks.append((run(root) == 1, "a second site in a baselined file fails (known-positive)"))
        (svc / "A.kt").write_text("val c = HttpClient.newBuilder().build()\n")
        (svc / "C.java").write_text("var c = HttpClient.newHttpClient();\n")
        checks.append((run(root) == 1, "a new file with a raw client fails"))
        (svc / "C.java").unlink()
        (svc / "A.kt").write_text("val s = SafeHttpClient(p)\n")
        checks.append((run(root) == 1, "a healed baseline entry is reported stale"))
        base.write_text("")
        checks.append((run(root) == 0, "empty baseline over a clean tree passes"))
        base.unlink()
        checks.append((run(root) == 2, "missing baseline cannot run"))

    failed = [msg for ok, msg in checks if not ok]
    for msg in failed:
        print(f"SELF-TEST FAIL: {msg}", file=sys.stderr)
    print(f"self-test: {len(checks) - len(failed)}/{len(checks)} passed")
    return 2 if failed else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--print-baseline", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    root = pathlib.Path(args.root).resolve()
    if args.print_baseline:
        counts, _ = scan(root)
        for f, n in sorted(counts.items()):
            print(f"{f}\t{n}")
        return 0
    return run(root)


if __name__ == "__main__":
    sys.exit(main())
