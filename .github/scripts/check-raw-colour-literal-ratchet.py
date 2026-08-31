#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Ratchet on raw hexadecimal colour literals in openbank-admin-ui's app/ and components/ trees
# (issue #7648). The admin UI is migrating hardcoded hex colours (`#1a2b3c`, `#fff`) to the shared
# semantic colour tokens in globals.css; this gate does not force the migration, it only forbids
# the count from going UP again once someone has done the work to bring it down.
#
# This is a CI-wide mirror of openbank-admin-ui/src/test/raw-colour-literal-ratchet.guard.test.ts
# (#7649). That Vitest test already runs on every admin-ui-touching PR via the `ui-build` job
# (`changes-ui` gates it — see ci.yml), so unlike the mermaid-parses gate this is NOT closing an
# absent-job gap: the subject here (app/**/*.tsx, components/**/*.tsx, *.css) is itself scoped to
# openbank-admin-ui/, so there is no PR shape that touches the count and misses `changes-ui`. This
# script exists for the OTHER reason a gate gets registered here rather than left as a bare test:
# a `.github/gates/gates.yaml` entry gets rationale/review_after/min_subjects tracking and a
# `--self-test` this repo's own tooling (run-gates.py, check-gate-script-registration.py) can see,
# where a lone Vitest assertion cannot. Keep the two thresholds in sync by hand; they check the
# same corpus with the same regex on purpose.
#
# EXIT CODES
#   0  count is at or under the ratchet ceiling
#   1  count exceeds the ceiling — a new raw hex colour literal was added
#   2  the check could not run, or the self-test failed. Never conflated with 0.
#
# Run:  python3 .github/scripts/check-raw-colour-literal-ratchet.py [--root .] [--self-test] [--list]

import argparse
import pathlib
import re
import sys

# Mirrors raw-colour-literal-ratchet.guard.test.ts exactly: three- and six-digit hex colours only,
# so an issue reference such as `#5904` is never mistaken for a colour.
HEX_LITERAL_RE = re.compile(r"#[0-9a-fA-F]{6}\b|#[0-9a-fA-F]{3}(?![0-9a-fA-F])")

SOURCE_SUBDIRS = ("app", "components")
SOURCE_EXTENSIONS = {".tsx", ".css"}

# Lowering this is always safe; raising it needs an intentional token decision, same rule as the
# Vitest test's own comment. Keep this number equal to that test's ceiling.
MAX_RAW_COLOUR_LITERALS = 1782


def source_files(root: pathlib.Path):
    for subdir in SOURCE_SUBDIRS:
        base = root / subdir
        if not base.is_dir():
            continue
        for p in sorted(base.rglob("*")):
            if not p.is_file():
                continue
            if p.name == "globals.css":
                continue
            if p.suffix in SOURCE_EXTENSIONS:
                yield p


def scan(root: str):
    base = pathlib.Path(root) / "openbank-admin-ui" / "src"
    findings = []
    for p in source_files(base):
        raw = p.read_text(encoding="utf-8", errors="replace")
        for m in HEX_LITERAL_RE.finditer(raw):
            findings.append({
                "file": str(p),
                "line": raw.count("\n", 0, m.start()) + 1,
                "literal": m.group(0),
            })
    return findings


# --- self-test ---------------------------------------------------------------------------------
def self_test() -> int:
    import tempfile

    failures = 0
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        src = root / "openbank-admin-ui" / "src"
        app = src / "app"
        components = src / "components"
        app.mkdir(parents=True)
        components.mkdir(parents=True)

        (app / "page.tsx").write_text(
            'const bg = "#1a2b3c" // flagged: six-digit hex\n'
            'const fg = "#fff" // flagged: three-digit hex\n'
            'const issue = "see #5904 for context" // must NOT match: not a colour, four hex digits\n',
        )
        (components / "Widget.css").write_text("body { color: #abcabc; }\n")
        # globals.css is the token stylesheet itself — excluded, same as the Vitest test.
        (src / "app" / "globals.css").write_text("--token: #123456;\n")
        (src / "README.md").write_text("#123456 in a non-source extension must not match\n")

        found = scan(str(root))
        literals = [f["literal"] for f in found]

        checks = [
            ("#1a2b3c" in literals, "must flag six-digit hex in .tsx"),
            (literals.count("#fff") == 1, "must flag three-digit hex in .tsx"),
            (literals.count("#abcabc") == 1, "must flag hex in .css under components/"),
            (len(found) == 3, "globals.css and non-source extensions must be excluded"),
        ]
        for ok, label in checks:
            print(f"{'pass' if ok else 'FAIL'}  {label}")
            failures += 0 if ok else 1

    print(f"\nself-test: {len(checks) - failures} passed, {failures} failed")
    return 0 if failures == 0 else 2


def main() -> int:
    ap = argparse.ArgumentParser(
        description="Ratchet raw hex colour literals in openbank-admin-ui (#7648)",
    )
    ap.add_argument("--root", default=".")
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--list", action="store_true", help="print every finding")
    ap.add_argument("--enforce", action="store_true", help="no-op; enforcement is the default")
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    root = pathlib.Path(args.root)
    if not root.is_dir():
        print(f"::error::--root {args.root} is not a directory — the check could not run. NOT a pass.")
        return 2

    findings = scan(args.root)
    count = len(findings)

    if args.list:
        for f in sorted(findings, key=lambda x: (x["file"], x["line"])):
            print(f"{f['file']}:{f['line']} {f['literal']}")
        print(f"\n{count} raw hex colour literal(s)")
        return 0

    if count > MAX_RAW_COLOUR_LITERALS:
        print(
            f"::error::{count} raw hex colour literal(s) in openbank-admin-ui app/ and "
            f"components/ — above the ratchet ceiling of {MAX_RAW_COLOUR_LITERALS}. Use a "
            f"semantic colour token instead of a new raw hex literal, or lower the ceiling in "
            f"both this script and raw-colour-literal-ratchet.guard.test.ts if you migrated "
            f"existing ones. See issue #7648.",
        )
        return 1

    print(
        f"raw colour literal ratchet: OK — {count} literal(s), ceiling {MAX_RAW_COLOUR_LITERALS}.",
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
