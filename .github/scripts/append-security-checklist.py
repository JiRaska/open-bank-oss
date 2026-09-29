#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Append the PR template's `## Security checklist` section to a PR body FILE (issue #8757).

WHY. CLAUDE.md mandates `gh pr create --body-file <file>` (never `--body`, #2890). A body passed
explicitly means GitHub never applies `.github/PULL_REQUEST_TEMPLATE.md`, and the template is the
only place the Security checklist exists. So following the mandated flag guarantees the section is
absent, and the enforced `security-checklist-money-path` gate then fails every money-path PR. The
two rules were each correct and could not both be followed without a third step nothing performed;
people added the block by hand after CI reddened (50 of 67 open PRs lacked it on 2026-09-05).

This is that third step, tracked so it is reviewable here rather than living in each machine's
gitignored skills:

    python3 .github/scripts/append-security-checklist.py /tmp/body.md
    gh pr create --body-file /tmp/body.md ...

WHAT IT DOES
  * copies the section VERBATIM from the template — never a second copy that can drift from it;
  * leaves every box UNTICKED. The gate's point is the pause; ticking stays the author's act;
  * is idempotent: a body that already has the section is left byte-identical;
  * inserts before the trailing `🤖 Generated with` attribution line when there is one, so the
    attribution stays last; otherwise appends.

It appends regardless of which paths the PR touches: the section is harmless off the money path,
and deciding scope here would duplicate the gate's diff logic and could disagree with it.

Exit 0 = body now contains the section. Exit 1 = template or body unreadable, or the template has
no such section (fail loudly: silently appending nothing is the defect this script exists to end).
"""

from __future__ import annotations

import pathlib
import re
import sys
import tempfile

TEMPLATE_REL = ".github/PULL_REQUEST_TEMPLATE.md"
SECTION = re.compile(r"^##\s+Security checklist\s*$", re.MULTILINE | re.IGNORECASE)
NEXT_HEADING = re.compile(r"^##\s+", re.MULTILINE)
ATTRIBUTION = re.compile(r"^🤖 Generated with ", re.MULTILINE)


def template_section(template_text: str) -> str | None:
    m = SECTION.search(template_text)
    if not m:
        return None
    rest = template_text[m.end():]
    nxt = NEXT_HEADING.search(rest)
    body = rest[: nxt.start()] if nxt else rest
    return (template_text[m.start():m.end()] + body).rstrip() + "\n"


def with_section(body: str, section: str) -> str:
    if SECTION.search(body):
        return body
    m = ATTRIBUTION.search(body)
    if m:
        head = body[: m.start()].rstrip()
        return f"{head}\n\n{section}\n{body[m.start():]}"
    return f"{body.rstrip()}\n\n{section}"


def repo_root() -> pathlib.Path:
    return pathlib.Path(__file__).resolve().parents[2]


def main(argv: list[str]) -> int:
    if len(argv) != 1:
        print("usage: append-security-checklist.py <pr-body-file> | --self-test", file=sys.stderr)
        return 1
    if argv[0] == "--self-test":
        return self_test()
    tpl_path = repo_root() / TEMPLATE_REL
    try:
        section = template_section(tpl_path.read_text(encoding="utf-8"))
        body_path = pathlib.Path(argv[0])
        body = body_path.read_text(encoding="utf-8")
    except OSError as e:
        print(f"::error::append-security-checklist: {e}", file=sys.stderr)
        return 1
    if section is None:
        print(f"::error::{TEMPLATE_REL} has no '## Security checklist' section to copy", file=sys.stderr)
        return 1
    new = with_section(body, section)
    if new == body:
        print("append-security-checklist: body already has the section — unchanged")
    else:
        body_path.write_text(new, encoding="utf-8")
        print("append-security-checklist: appended the template's Security checklist (unticked)")
    return 0


def self_test() -> int:
    tpl = (
        "## Summary\n\n<!-- x -->\n\n## Security checklist\n\n- [ ] No secrets.\n"
        "- [ ] Auth changed? tag it.\n\n## Compliance impact\n\n- [ ] GDPR\n"
    )
    section = template_section(tpl)
    fails = 0

    def check(name: str, cond: bool) -> None:
        nonlocal fails
        print(f"  {'ok  ' if cond else 'FAIL'} {name}")
        if not cond:
            fails += 1

    check("section copied up to the next heading, not beyond",
          section is not None and "No secrets" in section and "Compliance" not in section)
    check("template without the section yields None (caller fails loudly)",
          template_section("## Summary\n") is None)

    plain = "## What\n\nfix\n"
    out = with_section(plain, section or "")
    check("appended to a body lacking it", SECTION.search(out) is not None and out.startswith(plain.rstrip()))
    check("boxes stay unticked", "- [x]" not in out and "- [ ] No secrets." in out)

    attributed = "## What\n\nfix\n\n🤖 Generated with [Claude Code](https://claude.com/claude-code)\n"
    out2 = with_section(attributed, section or "")
    check("inserted BEFORE the attribution line, which stays last",
          out2.rstrip().endswith("(https://claude.com/claude-code)") and out2.index("Security checklist") < out2.index("🤖"))

    ticked = "## What\n\n## Security checklist\n\n- [x] done\n"
    check("idempotent: an existing (even ticked) section is left byte-identical",
          with_section(ticked, section or "") == ticked)

    # End-to-end through main() against the REAL template, so a template edit that drops or
    # renames the section reddens here instead of silently appending nothing.
    real = template_section((repo_root() / TEMPLATE_REL).read_text(encoding="utf-8"))
    check("the real PULL_REQUEST_TEMPLATE.md has a copyable section with boxes",
          real is not None and "- [ ]" in real)
    with tempfile.TemporaryDirectory() as d:
        f = pathlib.Path(d) / "body.md"
        f.write_text(plain, encoding="utf-8")
        rc1 = main([str(f)])
        once = f.read_text(encoding="utf-8")
        rc2 = main([str(f)])
        check("main(): appends once, second run is a no-op", rc1 == 0 and rc2 == 0 and once == f.read_text(encoding="utf-8") and SECTION.search(once) is not None)

    print(f"SUBJECTS={8}")
    print(f"self-test: {'PASS' if fails == 0 else f'FAIL ({fails})'}")
    return 0 if fails == 0 else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
