#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Extract every ADR's `## Delivery check` ```bash block into docs/adr/checks/<nnnn>.sh.

WHY THIS EXISTS
---------------
An ADR's "Delivery check" block is the falsifier of its own `delivery-status`, and as prose
nobody runs it: ADR-0344 kept `delivery-status: planned` while code for several of its decisions
was landing. Extracting the block into a file makes it executable by a gate
(check-adr-delivery-scripts.sh), and makes the ADR the single source: the .sh is DERIVED, never
hand-edited — edit the ADR and regenerate.

Only fenced ```bash blocks inside the section are extracted; a section written as a table or as
prose has no machine-readable form and is skipped (reported by the checker as unmeasured).

Usage:  gen-adr-delivery-checks.py [--root .]           # write / refresh the derived files
        gen-adr-delivery-checks.py [--root .] --check   # exit 1 if any derived file is stale
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

HEADER = (
    "# DERIVED from docs/adr/{name} `## Delivery check` by\n"
    "# .github/scripts/gen-adr-delivery-checks.py — never hand-edit; edit the ADR and regenerate.\n"
    "# Each command line's expectation is its trailing `# ...` comment, or the comment line above it.\n"
)


def extract(text: str) -> str | None:
    m = re.search(r"^## Delivery check[^\n]*\n(.*?)(?=^## |\Z)", text, re.M | re.S)
    if not m:
        return None
    blocks = re.findall(r"^```bash\n(.*?)^```", m.group(1), re.M | re.S)
    if not blocks:
        return None
    return "".join(blocks)


def expected(root: pathlib.Path) -> dict[pathlib.Path, str]:
    out: dict[pathlib.Path, str] = {}
    for adr in sorted((root / "docs/adr").glob("[0-9][0-9][0-9][0-9]-*.md")):
        body = extract(adr.read_text(encoding="utf-8"))
        if body is not None:
            out[root / "docs/adr/checks" / f"{adr.name[:4]}.sh"] = HEADER.format(name=adr.name) + body
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--check", action="store_true")
    a = ap.parse_args()
    root = pathlib.Path(a.root)
    want = expected(root)
    checks_dir = root / "docs/adr/checks"
    stale = []
    for path, content in want.items():
        have = path.read_text(encoding="utf-8") if path.exists() else None
        if have != content:
            stale.append(path)
            if not a.check:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content, encoding="utf-8")
    orphans = [p for p in sorted(checks_dir.glob("*.sh")) if p not in want] if checks_dir.is_dir() else []
    for p in orphans:
        stale.append(p)
        if not a.check:
            p.unlink()
    if a.check:
        for p in stale:
            print(f"stale derived file: {p.relative_to(root)} (run gen-adr-delivery-checks.py)")
        return 1 if stale else 0
    print(f"gen-adr-delivery-checks: {len(want)} delivery-check scripts, {len(stale)} rewritten/removed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
