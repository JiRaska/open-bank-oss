#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Report admin-ui routes with literal navigation in an axe Playwright spec.

This is a conservative source-level inventory, not a WCAG conformance assertion. Dynamic
navigation and UI states cannot be established from these literals; the report says which
page routes have an observable axe spec and which still need review (#11603).
"""
from __future__ import annotations

import argparse
import pathlib
import re
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
GOTO_LITERAL = re.compile(r"\bpage\.goto\(\s*(['\"`])(/[^'\"`$]*)\1")
ARRAY = re.compile(r"\bconst\s+(\w+)\s*=\s*\[([^]]*)\]", re.DOTALL)
STRING = re.compile(r"(['\"`])(/[^'\"`$]*)\1")
TEST_CALL = re.compile(r"\btest\s*\(")


def routes(ui: pathlib.Path) -> list[str]:
    out = []
    for page in (ui / "src" / "app").rglob("page.tsx"):
        parts = [p for p in page.parent.relative_to(ui / "src" / "app").parts
                 if not (p.startswith("(") and p.endswith(")"))]
        out.append("/" + "/".join(parts))
    return sorted(set(out))


def test_bodies(spec: str) -> list[str]:
    """Keep navigation and axe analysis in the same test call, not merely the same file."""
    bodies = []
    for call in TEST_CALL.finditer(spec):
        start = call.end()
        depth, quote, escaped = 1, None, False
        line_comment = block_comment = False
        for index in range(start, len(spec)):
            char = spec[index]
            next_char = spec[index + 1] if index + 1 < len(spec) else ""
            if line_comment:
                if char == "\n":
                    line_comment = False
                continue
            if block_comment:
                if char == "*" and next_char == "/":
                    block_comment = False
                continue
            if quote:
                if escaped:
                    escaped = False
                elif char == "\\":
                    escaped = True
                elif char == quote:
                    quote = None
                continue
            if char == "/" and next_char == "/":
                line_comment = True
                continue
            if char == "/" and next_char == "*":
                block_comment = True
                continue
            if char in ("'", '"', "`"):
                quote = char
            elif char == "(":
                depth += 1
            elif char == ")":
                depth -= 1
                if depth == 0:
                    bodies.append(spec[start:index])
                    break
    return bodies


def navigations(spec: str) -> set[str]:
    found: set[str] = set()
    arrays = {name: {m.group(2) for m in STRING.finditer(body)}
              for name, body in ARRAY.findall(spec)}
    loops = re.findall(r"\bfor\s*\(\s*const\s+(\w+)\s+of\s+(\w+)\s*\)", spec)
    for body in test_bodies(spec):
        if "new AxeBuilder" not in body or ".analyze()" not in body:
            continue
        found.update(m.group(2) for m in GOTO_LITERAL.finditer(body))
        for var, name in loops:
            if re.search(r"\bpage\.goto\(\s*" + re.escape(var) + r"\s*\)", body):
                found.update(arrays.get(name, set()))
    return found


def matches(route: str, navigation: str) -> bool:
    parts = route.strip("/").split("/") if route != "/" else []
    target = navigation.split("?", 1)[0].strip("/").split("/") if navigation != "/" else []
    for index, part in enumerate(parts):
        if part.startswith("[[...") and part.endswith("]]"):
            return index == len(parts) - 1
        if index >= len(target):
            return False
        if part.startswith("[...") and part.endswith("]"):
            return index == len(parts) - 1 and bool(target[index])
        if part.startswith("[") and part.endswith("]"):
            if not target[index]:
                return False
        elif part != target[index]:
            return False
    return len(parts) == len(target)


def coverage(ui: pathlib.Path) -> tuple[list[str], dict[str, list[str]]]:
    pages = routes(ui)
    evidence: dict[str, list[str]] = {route: [] for route in pages}
    for spec in sorted((ui / "e2e").glob("*.spec.ts")):
        for navigation in navigations(spec.read_text(encoding="utf-8")):
            path = navigation.split("?", 1)[0].rstrip("/") or "/"
            # Next.js prefers a concrete sibling (/campaigns/new) over [id]. Count
            # that navigation for the concrete page alone, never both routes.
            candidates = [path] if path in evidence else [r for r in pages if matches(r, path)]
            for route in candidates:
                evidence[route].append(f"{spec.name}:{navigation}")
    return pages, evidence


def self_test() -> None:
    with tempfile.TemporaryDirectory() as directory:
        ui = pathlib.Path(directory)
        for name in ("", "accounts", "accounts/[id]", "accounts/new", "hidden"):
            page = ui / "src" / "app" / name / "page.tsx"
            page.parent.mkdir(parents=True, exist_ok=True)
            page.write_text("export default function Page() {}")
        specs = ui / "e2e"
        specs.mkdir()
        (specs / "scan.spec.ts").write_text(
            "const ROUTES = ['/', '/accounts'] as const; "
            "for (const route of ROUTES) { test('axe', async ({ page }) => { "
            "await page.goto(route); await new AxeBuilder({ page }).analyze() }) } "
            "test('detail', async ({ page }) => { await page.goto('/accounts/example'); "
            "await new AxeBuilder({ page }).analyze() }) "
            "test('new', async ({ page }) => { await page.goto('/accounts/new'); "
            "await new AxeBuilder({ page }).analyze() }) "
            "test('no scan', async ({ page }) => { await page.goto('/hidden') })")
        (specs / "no-axe.spec.ts").write_text("await page.goto('/hidden')")
        pages, evidence = coverage(ui)
        assert pages == ["/", "/accounts", "/accounts/[id]", "/accounts/new", "/hidden"], pages
        assert all(evidence[route] for route in pages[:-1]), evidence
        assert not evidence["/hidden"], evidence
        assert evidence["/accounts/[id]"] == ["scan.spec.ts:/accounts/example"], evidence
        assert not matches("/accounts/[id]", "/accounts"), "dynamic route matched its parent"
    print("self-test: ok")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=pathlib.Path, default=ROOT)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return
    pages, evidence = coverage(args.root / "openbank-admin-ui")
    assert pages, "no Next.js page routes found"
    covered = [route for route in pages if evidence[route]]
    print(f"Admin UI axe route inventory: {len(covered)}/{len(pages)} routes have literal "
          "navigation in an axe Playwright spec")
    print("This source inventory does not prove every state or WCAG criterion was checked.")
    for route in pages:
        print(f"{'COVERED' if evidence[route] else 'UNCOVERED'} {route}"
              + (f" <- {', '.join(sorted(evidence[route]))}" if evidence[route] else ""))


if __name__ == "__main__":
    main()
