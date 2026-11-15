#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""ADR-0370 U1: keep the Admin UI e2e mock-contract guard impossible to bypass.

The guard is a Playwright auto fixture in e2e/helpers/test.ts. It only runs for specs that
import `test` from that module, so a spec importing `test` straight from '@playwright/test'
silently opts out and its mocks are never checked. This gate fails on that import, and on a
baseline entry naming a spec file that no longer exists (a baseline that cannot shrink by
deletion is a baseline that only grows).
"""

from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys
import tempfile

UI = "openbank-admin-ui"
BYPASS = re.compile(r"""^import\s*\{[^}]*\btest\b[^}]*\}\s*from\s*['"]@playwright/test['"]""", re.M)


def findings(ui: pathlib.Path) -> list[str]:
    e2e = ui / "e2e"
    out: list[str] = []
    specs = [p for p in e2e.rglob("*.ts") if "helpers" not in p.relative_to(e2e).parts]
    for spec in sorted(specs):
        if BYPASS.search(spec.read_text(encoding="utf-8")):
            out.append(f"{spec.relative_to(ui)}: imports `test` from '@playwright/test'; use './helpers/test'")
    baseline = json.loads((e2e / "contract-baseline.json").read_text(encoding="utf-8"))
    for key in baseline["violations"]:
        spec_file = key.split("|", 1)[0]
        if not (e2e / spec_file).is_file():
            out.append(f"e2e/contract-baseline.json: entry for missing spec {spec_file}; delete it")
    if not specs:
        out.append("no e2e specs found: the gate would pass vacuously")
    return out


def self_test() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        ui = pathlib.Path(tmp)
        (ui / "e2e/helpers").mkdir(parents=True)
        (ui / "e2e/helpers/test.ts").write_text("import { test } from '@playwright/test'\n")
        (ui / "e2e/ok.spec.ts").write_text("import { expect, test } from './helpers/test'\n")
        (ui / "e2e/types.spec.ts").write_text("import type { Page } from '@playwright/test'\n")
        (ui / "e2e/contract-baseline.json").write_text(json.dumps({"violations": ["ok.spec.ts|GET x /a|200|schema"]}))
        assert findings(ui) == [], findings(ui)
        (ui / "e2e/bad.spec.ts").write_text("import { expect, test } from '@playwright/test'\n")
        (ui / "e2e/contract-baseline.json").write_text(json.dumps({"violations": ["gone.spec.ts|GET x /a|200|schema"]}))
        got = findings(ui)
        assert len(got) == 2 and "bad.spec.ts" in got[0] and "gone.spec.ts" in got[1], got
    print("self-test: ok")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default=".")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return
    ui = pathlib.Path(args.root) / UI
    out = findings(ui)
    for line in out:
        print(f"::error title=admin-ui-e2e-contract-guard::{line}")
    specs = sum(1 for p in (ui / "e2e").rglob("*.spec.ts"))
    print(f"SUBJECTS={specs}")
    print(f"admin-ui-e2e-contract-guard: {specs} specs checked, {len(out)} finding(s)")
    sys.exit(1 if out else 0)


if __name__ == "__main__":
    main()
