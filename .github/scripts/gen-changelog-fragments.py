#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# gen-changelog-fragments.py — assemble a document's change-log section from one file per entry.
#
# WHY THIS EXISTS
#   A threat model's change log is written newest-first, so every PR that records a change inserts
#   its entry at the SAME line: directly under the section heading. Two open PRs on one service
#   therefore always conflict with each other, and a stack of slices conflicts with main every time
#   one of them lands. The content never overlaps — the position does.
#
#   The fix is the one release tooling uses (towncrier, changesets): each entry is its own file,
#   `docs/threat-models/<doc>.d/<YYYY-MM-DD>-<slug>.md`, and the section between two markers is
#   DERIVED from the directory. Adding an entry is adding a file, which git never conflicts on.
#
# THE CONTRACT
#   * A document opts in by carrying exactly one pair of markers:
#         <!-- changelog-fragments:begin (generated from <doc>.d/ — do not edit between markers) -->
#         <!-- changelog-fragments:end -->
#     Everything outside the markers stays hand-written; entries recorded before the document
#     opted in stay below the block, untouched.
#   * A fragment directory `<doc>.d/` with no opted-in `<doc>.md` is an error, and so is an
#     opted-in document with no directory: either would mean an entry that never renders.
#   * Fragment name `YYYY-MM-DD-<slug>.md` (slug: lower-case, digits, hyphens). Rendered newest
#     date first; within one date, by slug — deterministic, so two branches regenerate identically.
#   * A fragment is one top-level list item whose first line starts `- **<the same date>`, the
#     shape the hand-written entries already use. A date that disagrees with the file name is an
#     error: the file name is what orders the entry, the text is what the reader trusts.
#
# Usage:
#   python3 .github/scripts/gen-changelog-fragments.py              # rewrite every opted-in doc
#   python3 .github/scripts/gen-changelog-fragments.py --check      # exit 1 on drift, write nothing
#   python3 .github/scripts/gen-changelog-fragments.py --self-test  # prove --check can go red

from __future__ import annotations

import argparse
import pathlib
import re
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
# The directories whose documents may opt in. Generic by construction: any threat model can adopt
# the scheme by adding the markers and a `.d/` directory, with no edit here.
DOC_DIRS = ("docs/threat-models",)

BEGIN_RE = re.compile(r"^<!-- changelog-fragments:begin\b.*-->$")
END_LINE = "<!-- changelog-fragments:end -->"
FRAGMENT_RE = re.compile(r"^(\d{4}-\d{2}-\d{2})-([a-z0-9][a-z0-9-]*)\.md$")


class FragmentError(Exception):
    pass


def fragments(directory: pathlib.Path) -> list[pathlib.Path]:
    found = []
    for path in directory.iterdir():
        if path.name.startswith("."):
            continue
        m = FRAGMENT_RE.match(path.name)
        if not m or not path.is_file():
            raise FragmentError(f"{path}: fragment names must be YYYY-MM-DD-<slug>.md")
        found.append((m.group(1), m.group(2), path))
    # Newest date first; slug ascending within a date. Two sorts, because the keys go in
    # opposite directions and a negated date string is not a thing.
    found.sort(key=lambda t: t[1])
    found.sort(key=lambda t: t[0], reverse=True)
    return [p for _, _, p in found]


def render_fragment(path: pathlib.Path) -> str:
    date = FRAGMENT_RE.match(path.name).group(1)
    text = path.read_text(encoding="utf-8").strip("\n")
    lines = text.splitlines()
    if not lines or not lines[0].startswith(f"- **{date}"):
        raise FragmentError(f"{path}: first line must start with '- **{date}' (the date in its name)")
    for line in lines[1:]:
        if line.startswith("- "):
            raise FragmentError(f"{path}: one entry per fragment — a second top-level item starts: {line[:60]!r}")
        if line.startswith("#"):
            raise FragmentError(f"{path}: a fragment may not contain a heading: {line[:60]!r}")
    return "\n".join(line.rstrip() for line in lines)


def assemble(doc_text: str, doc: pathlib.Path, frag_dir: pathlib.Path) -> str:
    lines = doc_text.split("\n")
    begins = [i for i, line in enumerate(lines) if BEGIN_RE.match(line.strip())]
    ends = [i for i, line in enumerate(lines) if line.strip() == END_LINE]
    if len(begins) != 1 or len(ends) != 1 or ends[0] < begins[0]:
        raise FragmentError(f"{doc}: needs exactly one begin marker followed by one end marker "
                            f"(found {len(begins)} begin, {len(ends)} end)")
    rendered = [render_fragment(p) for p in fragments(frag_dir)]
    body = "\n\n".join(rendered)
    block = [lines[begins[0]], ""] + ([body, ""] if body else []) + [END_LINE]
    return "\n".join(lines[: begins[0]] + block + lines[ends[0] + 1:])


def managed(root: pathlib.Path) -> list[tuple[pathlib.Path, pathlib.Path]]:
    """Every (document, fragment-directory) pair, cross-checked in both directions."""
    pairs = []
    for rel in DOC_DIRS:
        base = root / rel
        if not base.is_dir():
            continue
        for frag_dir in sorted(p for p in base.iterdir() if p.is_dir() and p.name.endswith(".d")):
            doc = base / (frag_dir.name[:-2] + ".md")
            if not doc.is_file():
                raise FragmentError(f"{frag_dir}: no {doc.name} next to it — these entries would never render")
            pairs.append((doc, frag_dir))
        for doc in sorted(base.glob("*.md")):
            text = doc.read_text(encoding="utf-8")
            opted_in = any(BEGIN_RE.match(line.strip()) for line in text.splitlines())
            if opted_in and not (base / (doc.stem + ".d")).is_dir():
                raise FragmentError(f"{doc}: carries changelog markers but {doc.stem}.d/ does not exist")
            if not opted_in and (base / (doc.stem + ".d")).is_dir():
                raise FragmentError(f"{doc}: {doc.stem}.d/ exists but the document has no changelog markers")
    return pairs


def run(root: pathlib.Path, check: bool) -> int:
    try:
        pairs = managed(root)
        drift = []
        for doc, frag_dir in pairs:
            current = doc.read_text(encoding="utf-8")
            wanted = assemble(current, doc, frag_dir)
            if wanted == current:
                continue
            if check:
                drift.append(doc)
            else:
                doc.write_text(wanted, encoding="utf-8")
                print(f"  wrote {doc.relative_to(root)}")
    except FragmentError as err:
        print(f"::error::changelog-fragments: {err}")
        print("SUBJECTS=UNRESOLVED")
        return 1
    print(f"SUBJECTS={len(pairs)}  # documents assembled from changelog fragments")
    for doc in drift:
        print(f"::error file={doc.relative_to(root)}::changelog section is stale — run "
              "`python3 .github/scripts/gen-changelog-fragments.py` (or regen-derived.sh) and commit; "
              "never edit between the markers")
    return 1 if drift else 0


def self_test() -> int:
    """Known-positive and known-negative cases against a throwaway tree."""
    failures = 0

    def expect(label: str, got: int, want: int) -> None:
        nonlocal failures
        ok = got == want
        failures += 0 if ok else 1
        print(f"  [{'ok' if ok else 'FAIL'}] {label}: exit {got}, expected {want}")

    doc_text = ("# T\n\n## Change log\n\n"
                "<!-- changelog-fragments:begin (generated from t.d/ — do not edit between markers) -->\n\n"
                "<!-- changelog-fragments:end -->\n\n- **2026-01-01** — legacy entry.\n")
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        tm = root / "docs/threat-models"
        (tm / "t.d").mkdir(parents=True)
        doc = tm / "t.md"
        doc.write_text(doc_text, encoding="utf-8")
        expect("empty fragment dir, fresh doc", run(root, check=True), 0)

        (tm / "t.d/2026-02-01-b.md").write_text("- **2026-02-01** — second.\n  - detail\n", encoding="utf-8")
        (tm / "t.d/2026-02-01-a.md").write_text("- **2026-02-01** — first.\n", encoding="utf-8")
        (tm / "t.d/2026-03-01-z.md").write_text("- **2026-03-01** — newest.\n", encoding="utf-8")
        expect("fragment added, doc not regenerated (known-positive)", run(root, check=True), 1)
        expect("regenerate", run(root, check=False), 0)
        expect("after regenerate", run(root, check=True), 0)
        out = doc.read_text(encoding="utf-8")
        order = [out.find("newest"), out.find("first"), out.find("second"), out.find("legacy")]
        ok = -1 not in order and order == sorted(order)
        failures += 0 if ok else 1
        print(f"  [{'ok' if ok else 'FAIL'}] order newest-date first, slug within date, legacy below")
        expect("idempotent", run(root, check=False) or int(doc.read_text(encoding="utf-8") != out), 0)

        doc.write_text(out.replace("newest.", "hand-edited."), encoding="utf-8")
        expect("hand edit between markers (known-positive)", run(root, check=True), 1)
        doc.write_text(out, encoding="utf-8")

        (tm / "t.d/2026-04-01-bad.md").write_text("- **2026-05-01** — wrong date.\n", encoding="utf-8")
        expect("date disagrees with file name", run(root, check=True), 1)
        (tm / "t.d/2026-04-01-bad.md").write_text("- **2026-04-01** — a.\n- **2026-04-01** — b.\n", encoding="utf-8")
        expect("two entries in one fragment", run(root, check=True), 1)
        (tm / "t.d/2026-04-01-bad.md").unlink()
        (tm / "t.d/notes.md").write_text("- x\n", encoding="utf-8")
        expect("badly named fragment", run(root, check=True), 1)
        (tm / "t.d/notes.md").unlink()

        (tm / "orphan.d").mkdir()
        expect("fragment dir with no document", run(root, check=True), 1)
        (tm / "orphan.d").rmdir()
        doc.write_text(out.replace(END_LINE, ""), encoding="utf-8")
        expect("missing end marker", run(root, check=True), 1)

    if failures:
        print(f"SELF-TEST FAILED: {failures} case(s).")
        return 1
    print("SELF-TEST OK: drift and malformed fragments go red; fresh docs stay green.")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--check", action="store_true")
    group.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    return run(ROOT, check=args.check)


if __name__ == "__main__":
    sys.exit(main())
