#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# check-realm-json-canonical.py — hold machine-edited Keycloak realm templates to ONE serialisation.
#
# WHY THIS EXISTS
#   The customers realm template is edited by hand AND rewritten by a generator that parses the
#   JSON and serialises it again. When the file on disk is hand-formatted (short arrays inline,
#   aligned values) the first generator run reflows hundreds of lines nobody changed, and every
#   branch that edited anything near them conflicts — the content is identical, the bytes are not.
#   One canonical form, enforced, makes a generator run a no-op on untouched content and makes a
#   hand edit and a regeneration produce the same bytes.
#
# THE CANONICAL FORM (`serialise` below — import it, do not re-implement it)
#   json.dumps(doc, indent=2, ensure_ascii=False) + "\n": two-space indent, every array and object
#   expanded one element per line, non-ASCII kept literal, one trailing newline. Key order is the
#   document's own, NOT sorted: the realm export groups related keys and Keycloak ignores order, so
#   sorting would buy a one-time 500-line churn and nothing else. Order is stable because a parse
#   and re-serialise preserves it. Duplicate keys are refused — json.loads keeps only the last one,
#   silently, which is the SnakeYAML trap in JSON form.
#
# Usage:
#   python3 .github/scripts/check-realm-json-canonical.py            # exit 1 if any file is not canonical
#   python3 .github/scripts/check-realm-json-canonical.py --write    # rewrite into canonical form
#   python3 .github/scripts/check-realm-json-canonical.py --self-test

from __future__ import annotations

import argparse
import json
import pathlib
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
# Templates a generator rewrites. Add a file here when a generator starts writing it.
CANONICAL_FILES = (
    "openbank-infra/gitops/components/keycloak/customers-realm-template.json",
)


class NotCanonical(Exception):
    pass


def serialise(doc: object) -> str:
    """The one serialisation. Generators writing a file listed above must call this."""
    return json.dumps(doc, indent=2, ensure_ascii=False) + "\n"


def _no_duplicates(pairs: list[tuple[str, object]]) -> dict:
    keys = [k for k, _ in pairs]
    dupes = sorted({k for k in keys if keys.count(k) > 1})
    if dupes:
        raise NotCanonical(f"duplicate key(s) {dupes} — json.loads would keep only the last")
    return dict(pairs)


def parse(text: str) -> object:
    return json.loads(text, object_pairs_hook=_no_duplicates)


def canonical(text: str) -> str:
    out = serialise(parse(text))
    # Idempotence is the property every caller relies on; assert it rather than assume it.
    if serialise(parse(out)) != out:
        raise NotCanonical("serialisation is not idempotent")
    return out


def run(root: pathlib.Path, files: tuple[str, ...], write: bool) -> int:
    bad = 0
    seen = 0
    for rel in files:
        path = root / rel
        if not path.is_file():
            print(f"::error::{rel}: listed as canonical but does not exist")
            bad += 1
            continue
        seen += 1
        text = path.read_text(encoding="utf-8")
        try:
            want = canonical(text)
        except (NotCanonical, json.JSONDecodeError) as err:
            print(f"::error file={rel}::{err}")
            bad += 1
            continue
        if want == text:
            continue
        if write:
            path.write_text(want, encoding="utf-8")
            print(f"  wrote {rel}")
        else:
            print(f"::error file={rel}::not in canonical form — run "
                  "`python3 .github/scripts/check-realm-json-canonical.py --write` and commit")
            bad += 1
    print(f"SUBJECTS={seen}  # realm templates held to the canonical serialisation")
    return 1 if bad else 0


def self_test() -> int:
    failures = 0

    def expect(label: str, got: int, want: int) -> None:
        nonlocal failures
        failures += 0 if got == want else 1
        print(f"  [{'ok' if got == want else 'FAIL'}] {label}: exit {got}, expected {want}")

    doc = {"realm": "r", "clients": [{"clientId": "a", "redirectUris": ["x", "y"]}], "displayName": "Bankovní"}
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        f = root / "t.json"
        files = ("t.json",)
        f.write_text(serialise(doc), encoding="utf-8")
        expect("canonical file", run(root, files, write=False), 0)
        f.write_text('{\n  "realm": "r",\n  "clients": [{"clientId": "a", "redirectUris": ["x", "y"]}],\n'
                     '  "displayName": "Bankovní"\n}\n', encoding="utf-8")
        expect("inline arrays (known-positive)", run(root, files, write=False), 1)
        expect("--write", run(root, files, write=True), 0)
        expect("after --write", run(root, files, write=False), 0)
        ok = f.read_text(encoding="utf-8") == serialise(doc)
        failures += 0 if ok else 1
        print(f"  [{'ok' if ok else 'FAIL'}] --write produces exactly serialise(doc), key order kept")
        f.write_text(json.dumps(doc, indent=4, ensure_ascii=False) + "\n", encoding="utf-8")
        expect("4-space indent (known-positive)", run(root, files, write=False), 1)
        f.write_text(json.dumps(doc, indent=2) + "\n", encoding="utf-8")
        expect("escaped non-ASCII (known-positive)", run(root, files, write=False), 1)
        f.write_text(serialise(doc).rstrip("\n"), encoding="utf-8")
        expect("missing trailing newline (known-positive)", run(root, files, write=False), 1)
        f.write_text('{\n  "realm": "a",\n  "realm": "b"\n}\n', encoding="utf-8")
        expect("duplicate key (known-positive)", run(root, files, write=True), 1)
        expect("listed file missing", run(root, ("absent.json",), write=False), 1)
    if failures:
        print(f"SELF-TEST FAILED: {failures} case(s).")
        return 1
    print("SELF-TEST OK: every non-canonical shape goes red; canonical stays green.")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0] if __doc__ else None)
    group = ap.add_mutually_exclusive_group()
    group.add_argument("--write", action="store_true")
    group.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    return run(ROOT, CANONICAL_FILES, write=args.write)


if __name__ == "__main__":
    sys.exit(main())
