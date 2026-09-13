#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""A Next.js App Router `route.ts` may export only route fields — anything else fails the build.

WHY THIS EXISTS
---------------
webpack rejects a non-route value export from an App Router route handler:

    "X" is not a valid Route export field

This class has now broken the admin-ui build twice: `infra/status` (#3262) and
`delegations/projection-health` (#3611), the latter being the last blocker to getting source maps
uploaded to GlitchTip at all.

It is now MORE likely to recur, not less. #3611 moved the production build to `next build --webpack`
(Turbopack emits no client source maps, so `productionBrowserSourceMaps` was inert and the upload
had been shipping an empty set with every step green). `npm run dev` still uses Turbopack, which
TOLERATES these exports. So a developer cannot see this failure locally — the first signal is a red
CI build, on a change that looked fine everywhere they could look.

That is the shape worth gating: not a defect someone was careless about, but one the local feedback
loop is structurally unable to show.

WHAT IT CHECKS
--------------
For every `openbank-admin-ui/src/app/**/route.ts`, each `export` of a *value* (`const`, `let`, `var`,
`function`, `class`) must be one of the fields Next.js recognises — the HTTP method handlers plus the
route segment config. Type-only exports (`export type`, `export interface`) are erased at compile
time and are always fine; `export default` is a separate Next error and out of scope here.

The fix for a violation is never to add it to the allow-list below — that list is Next.js', not
ours. Drop the `export` keyword (the value is almost always only used in that file), or move the
constant to a module the route imports.

Usage:  check-route-exports.py [--enforce] [--self-test]
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

import gatelib

REPO = pathlib.Path(__file__).resolve().parents[2]
APP_DIR = REPO / "openbank-admin-ui/src/app"

# Next.js' own vocabulary. Do NOT extend this to make a build pass — see the module docstring.
ALLOWED = {
    # HTTP method handlers
    "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS",
    # Route segment config
    "dynamic", "dynamicParams", "revalidate", "fetchCache", "runtime", "preferredRegion",
    "maxDuration", "generateStaticParams", "generateMetadata", "config",
}

# `export const X`, `export async function X`, `export class X` … but never `export type/interface`
# (erased at compile time) and never `export default` (a different Next error).
VALUE_EXPORT = re.compile(
    r"^[ \t]*export\s+(?!type\b|interface\b|default\b)(?:async\s+)?"
    r"(?:const|let|var|function|class)\s+([A-Za-z_$][\w$]*)",
    re.MULTILINE,
)


def strip_comments(text: str) -> str:
    """Remove block and line comments.

    A comment quoting the error, or documenting why an export was removed, must not be read as an
    export — the code-about-code collision this repo hits repeatedly. Strings are not parsed out:
    a `//` inside a string literal would truncate the line, whose failure direction is a missed
    export, so the self-test pins a URL-in-a-string case.
    """
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.DOTALL)
    return re.sub(r"(?<!:)//.*", "", text)


def route_files(app_dir: pathlib.Path) -> list[pathlib.Path]:
    return sorted(app_dir.rglob("route.ts")) + sorted(app_dir.rglob("route.tsx"))


def findings(app_dir: pathlib.Path = APP_DIR) -> tuple[list[str], int]:
    files = route_files(app_dir)
    out: list[str] = []
    for path in files:
        try:
            text = strip_comments(path.read_text(encoding="utf-8", errors="ignore"))
        except OSError:
            continue
        for match in VALUE_EXPORT.finditer(text):
            name = match.group(1)
            if name in ALLOWED:
                continue
            rel = path.relative_to(REPO) if path.is_relative_to(REPO) else path
            line = text[: match.start()].count("\n") + 1
            out.append(
                f"{rel}:{line}: exports '{name}', which is not a Route export field. webpack fails "
                f"the build with \"{name}\" is not a valid Route export field — and `npm run dev` "
                f"uses Turbopack, which tolerates it, so this is invisible locally (#3262, #3611). "
                f"Drop the `export` keyword, or move the value into a module the route imports. Do "
                f"not add it to the allow-list: that vocabulary is Next.js', not ours.")
    return out, len(files)


def selftest() -> int:
    import tempfile

    cases = [
        ("a plain handler", "export async function GET() {}\n", 0),
        ("route segment config", "export const dynamic = 'force-dynamic'\nexport async function GET() {}\n", 0),
        # The exact #3611 shape.
        ("a stray const export", "export const DELEGATION_TOPIC = 'x'\nexport async function GET() {}\n", 1),
        ("a stray function export", "export function helper() {}\nexport async function POST() {}\n", 1),
        ("a stray class export", "export class Thing {}\nexport async function GET() {}\n", 1),
        # Type-only exports are erased at compile time — flagging them would be a false positive on
        # a shape webpack accepts, and the fix suggested would be wrong.
        ("an exported type", "export type Row = { a: string }\nexport async function GET() {}\n", 0),
        ("an exported interface", "export interface Row { a: string }\nexport async function GET() {}\n", 0),
        # Code-about-code, both directions.
        ("a comment quoting the error",
         "// export const FOO is not a valid Route export field\nexport async function GET() {}\n", 0),
        ("a block comment naming an export",
         "/* export const BAR = 1 */\nexport async function GET() {}\n", 0),
        # A `//` inside a URL must not truncate the line and hide the export after it.
        ("a URL in a string above a stray export",
         "const u = 'https://x/y'\nexport const TOPIC = 'x'\nexport async function GET() {}\n", 1),
        ("a non-exported const is fine", "const TOPIC = 'x'\nexport async function GET() {}\n", 0),
    ]
    for label, body, want in cases:
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d) / "api/thing"
            root.mkdir(parents=True)
            (root / "route.ts").write_text(body, encoding="utf-8")
            got, count = findings(pathlib.Path(d))
        if count != 1:
            print(f"selftest FAIL: {label} — expected to scan 1 route file, scanned {count}")
            return 1
        if len(got) != want:
            print(f"selftest FAIL: {label} — expected {want} finding(s), got {len(got)}: {got}")
            return 1

    # An empty scan must not read as clean: zero routes and zero findings are the same output, and
    # the real tree has 87. This is the shape in which the gate would be green about nothing.
    with tempfile.TemporaryDirectory() as d:
        got, count = findings(pathlib.Path(d))
        if count != 0 or got:
            print("selftest FAIL: the empty-tree case did not behave as expected.")
            return 1
    if len(route_files(APP_DIR)) < 20:
        print(f"selftest FAIL: only {len(route_files(APP_DIR))} route file(s) found in the real "
              f"tree — the scan is broken.")
        return 1

    print(f"selftest OK: {len(cases)} fixtures — stray const/function/class flagged, handlers and "
          f"segment config allowed, type-only exports allowed, comments both ways, "
          f"{len(route_files(APP_DIR))} real route files discovered.")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--self-test", action="store_true", dest="self_test")
    args = ap.parse_args()
    if args.self_test:
        return selftest()

    found, count = findings()
    gatelib.subjects(count, "App Router route files scanned")
    for line in found:
        print(("::error::" if args.enforce else "::warning::") + line)
    print(f"check-route-exports: {count} App Router route file(s) — "
          f"{'clean.' if not found else f'{len(found)} finding(s) above.'}")
    return 1 if found and args.enforce else 0


if __name__ == "__main__":
    sys.exit(main())
