#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""The compliance trace (ADR-0379) must resolve end to end: obligation -> control -> evidence.

Why
---
The admin-ui compliance hub (`/docs/compliance`) joins three authored sources at request time:
the regulatory obligations register, the control catalogue and the CI gate manifest. Each join
is a string id written by hand in a YAML file. A misspelt id does not fail anything at runtime:
the hub would quietly drop the edge and show a control with no evidence, or an obligation with
no control. A reader can't tell that from an honest gap. So every edge is resolved here, where a
broken one fails the PR instead.

This gate replaces `compliance-matrix` and `compliance-page-evidence`. Both guarded the
hand-written `COMPLIANCE_AREAS` literal that the hub deletes (#2370, #2667, #3240). Check 4 below
keeps the literal from returning.

What it checks
--------------
1. Every control in `compliance-controls.yaml` declares at least one `evidence_refs` entry, each
   `{kind, ref}` with kind in gate | workflow | test | adr | path.
2. Every ref resolves: a gate id in `.github/gates/gates.yaml`, a file in `.github/workflows/`,
   an existing test file under a test source set, a `docs/adr/NNNN-*.md`, or an existing repo path.
   A `max_evidence_age_days`, where declared, is a positive integer on a control that cites at
   least one gate, the only kind whose run age the hub can observe.
3. Every `controls:` id on an obligation in `regulatory-obligations.yaml` names a real control.
4. The hub page carries no hand-written requirement literal (`COMPLIANCE_AREAS` or a
   `{ req: [...] }` row).
Both YAML files are loaded with duplicate keys rejected. A duplicated `evidence_refs:` key would
otherwise silently keep only the last list (see the YAML rule in CLAUDE.md).

What it deliberately does NOT check: whether a cited gate actually proves the control. That
judgement belongs to the reviewer of the YAML change, and a mechanical verdict on it would be
fiction. An obligation with no control is a gap the hub shows to its readers. It is not a CI
failure, because most reporting duties have no security control to point at.

Usage:  check-compliance-trace.py [--enforce] [--self-test] [--repo <path>]
"""
from __future__ import annotations

import argparse
import pathlib
import re
import sys
import tempfile

import yaml

REPO = pathlib.Path(__file__).resolve().parents[2]
CONTROLS = "openbank-libs/governance/compliance-controls.yaml"
OBLIGATIONS = "openbank-libs/governance/regulatory-obligations.yaml"
GATES = ".github/gates/gates.yaml"
PAGE = "openbank-admin-ui/src/app/docs/compliance/page.tsx"
KINDS = ("gate", "workflow", "test", "adr", "path")
TEST_DIRS = ("/src/test/", "/src/androidTest/")
# A test ref is CI-level evidence, so it must name a test, not just any file in a test tree.
TEST_FILE = re.compile(r"(Test|IT|Spec)\.kt$|\.(test|spec)\.(ts|tsx|mjs)$")


class _UniqueKeyLoader(yaml.SafeLoader):
    pass


def _construct_mapping(loader, node, deep=False):
    seen = set()
    for key_node, _ in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if key in seen:
            raise yaml.constructor.ConstructorError(
                None, None, f"duplicate key {key!r}", key_node.start_mark)
        seen.add(key)
    return yaml.SafeLoader.construct_mapping(loader, node, deep)


_UniqueKeyLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, _construct_mapping)


def _load(path: pathlib.Path):
    return yaml.load(path.read_text(encoding="utf-8"), Loader=_UniqueKeyLoader)


def _resolves(repo: pathlib.Path, kind: str, ref: str, gate_ids: set[str]) -> bool:
    if kind == "gate":
        return ref in gate_ids
    if kind == "workflow":
        return "/" not in ref and (repo / ".github" / "workflows" / ref).is_file()
    if kind == "test":
        parts = pathlib.PurePosixPath(ref).parts
        in_test_tree = any(d in f"/{ref}" for d in TEST_DIRS) or (len(parts) > 2 and parts[1] == "e2e")
        return (not ref.startswith("/") and ".." not in parts and in_test_tree
                and bool(TEST_FILE.search(ref)) and (repo / ref).is_file())
    if kind == "adr":
        return bool(re.fullmatch(r"\d{4}", ref)) and any((repo / "docs" / "adr").glob(f"{ref}-*.md"))
    if kind == "path":
        return not ref.startswith("/") and ".." not in pathlib.PurePosixPath(ref).parts and (repo / ref).exists()
    return False


def check(repo: pathlib.Path) -> tuple[list[str], int]:
    errs: list[str] = []
    try:
        catalogue = _load(repo / CONTROLS) or {}
        register = _load(repo / OBLIGATIONS) or {}
        manifest = _load(repo / GATES) or {}
    except (OSError, yaml.YAMLError) as exc:
        return [f"cannot load a trace source: {exc}"], 0

    gate_ids = {g.get("id") for g in manifest.get("gates") or [] if isinstance(g, dict)}
    controls = catalogue.get("controls") or []
    control_ids = {c.get("id") for c in controls if isinstance(c, dict)}
    subjects = 0

    for c in controls:
        cid = c.get("id", "<no id>")
        refs = c.get("evidence_refs")
        if not isinstance(refs, list) or not refs:
            errs.append(f"{CONTROLS}: control {cid} declares no evidence_refs")
            continue
        for r in refs:
            subjects += 1
            kind = r.get("kind") if isinstance(r, dict) else None
            ref = str(r.get("ref", "")) if isinstance(r, dict) else ""
            if kind not in KINDS:
                errs.append(f"{CONTROLS}: control {cid} evidence kind {kind!r} is not one of {', '.join(KINDS)}")
            elif not _resolves(repo, kind, ref, gate_ids):
                errs.append(f"{CONTROLS}: control {cid} evidence {kind}:{ref} does not resolve")
        if "max_evidence_age_days" in c:
            age = c["max_evidence_age_days"]
            if not isinstance(age, int) or isinstance(age, bool) or age < 1:
                errs.append(f"{CONTROLS}: control {cid} max_evidence_age_days must be a positive integer")
            elif not any(isinstance(r, dict) and r.get("kind") == "gate" for r in refs):
                errs.append(f"{CONTROLS}: control {cid} declares max_evidence_age_days but cites no gate whose age could be observed")

    for o in register.get("obligations") or []:
        linked = o.get("controls")
        if linked is None:
            continue
        if not isinstance(linked, list):
            errs.append(f"{OBLIGATIONS}: obligation {o.get('id')} controls must be a list")
            continue
        for cid in linked:
            subjects += 1
            if cid not in control_ids:
                errs.append(f"{OBLIGATIONS}: obligation {o.get('id')} names unknown control {cid!r}")

    page = repo / PAGE
    if page.is_file():
        text = page.read_text(encoding="utf-8")
        if "COMPLIANCE_AREAS" in text or re.search(r"\{\s*req:\s*\[", text):
            errs.append(f"{PAGE}: hand-written requirement literal is back; author obligations and controls in YAML instead")
    else:
        errs.append(f"{PAGE}: hub page not found")

    return errs, subjects


def _fixture(root: pathlib.Path) -> None:
    (root / ".github/gates").mkdir(parents=True)
    (root / ".github/workflows").mkdir(parents=True)
    (root / "docs/adr").mkdir(parents=True)
    (root / "openbank-libs/governance").mkdir(parents=True)
    (root / "openbank-admin-ui/src/app/docs/compliance").mkdir(parents=True)
    (root / GATES).write_text("gates:\n  - id: g1\n    mode: enforced\n")
    (root / ".github/workflows/w.yml").write_text("on: push\n")
    (root / "docs/adr/0001-x.md").write_text("# x\n")
    (root / "svc/src/test").mkdir(parents=True)
    (root / "svc/src/test/TTest.kt").write_text("class TTest\n")
    (root / "svc/src/test/Helper.kt").write_text("class Helper\n")
    (root / "svc/src/main").mkdir(parents=True)
    (root / "svc/src/main/M.kt").write_text("class M\n")
    (root / CONTROLS).write_text(
        "controls:\n"
        "  - id: c1\n"
        "    evidence_refs:\n"
        "      - { kind: gate, ref: g1 }\n"
        "      - { kind: workflow, ref: w.yml }\n"
        "      - { kind: adr, ref: '0001' }\n"
        "      - { kind: path, ref: docs/adr }\n"
        "      - { kind: test, ref: svc/src/test/TTest.kt }\n"
        "    max_evidence_age_days: 7\n")
    (root / OBLIGATIONS).write_text("obligations:\n  - id: o1\n    controls: [c1]\n  - id: o2\n")
    (root / PAGE).write_text("export default function Page() { return null }\n")


def self_test() -> int:
    def mutate(root: pathlib.Path, rel: str, old: str, new: str) -> None:
        p = root / rel
        text = p.read_text()
        assert old in text, (rel, old)
        p.write_text(text.replace(old, new, 1))

    negatives = {
        "unknown gate": (CONTROLS, "ref: g1", "ref: nope"),
        "missing workflow": (CONTROLS, "ref: w.yml", "ref: gone.yml"),
        "missing adr": (CONTROLS, "ref: '0001'", "ref: '0999'"),
        "path escapes repo": (CONTROLS, "ref: docs/adr", "ref: ../etc"),
        "test is main code": (CONTROLS, "ref: svc/src/test/TTest.kt", "ref: svc/src/main/M.kt"),
        "test tree non-test file": (CONTROLS, "ref: svc/src/test/TTest.kt", "ref: svc/src/test/Helper.kt"),
        "absolute test path": (CONTROLS, "ref: svc/src/test/TTest.kt", "ref: /etc/src/test/XTest.kt"),
        "absolute path ref": (CONTROLS, "ref: docs/adr", "ref: /etc"),
        "missing test": (CONTROLS, "ref: svc/src/test/TTest.kt", "ref: svc/src/test/GoneTest.kt"),
        "age not positive": (CONTROLS, "max_evidence_age_days: 7", "max_evidence_age_days: 0"),
        "age without gate": (CONTROLS, "      - { kind: gate, ref: g1 }\n", ""),
        "bad kind": (CONTROLS, "kind: path", "kind: vibes"),
        "no evidence": (CONTROLS, "    evidence_refs:\n", "    evidence_refs: []\n    x:\n"),
        "duplicate key": (CONTROLS, "  - id: c1\n", "  - id: c1\n    id: c2\n"),
        "unknown control": (OBLIGATIONS, "[c1]", "[c9]"),
        "literal returns": (PAGE, "export", "const COMPLIANCE_AREAS = []\nexport"),
        "req row returns": (PAGE, "export", "const x = [{ req: ['a', 'b'] }]\nexport"),
    }
    failures = 0
    with tempfile.TemporaryDirectory() as tmp:
        base = pathlib.Path(tmp) / "ok"
        _fixture(base)
        errs, n = check(base)
        if errs or n != 6:
            print(f"SELF-TEST FAIL: clean fixture -> {errs} subjects={n}")
            failures += 1
        for name, (rel, old, new) in negatives.items():
            root = pathlib.Path(tmp) / name.replace(" ", "-")
            _fixture(root)
            mutate(root, rel, old, new)
            errs, _ = check(root)
            if not errs:
                print(f"SELF-TEST FAIL: {name} was not detected")
                failures += 1
    print("self-test: " + ("FAIL" if failures else f"ok ({len(negatives)} negatives, 1 positive)"))
    return 1 if failures else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--repo", type=pathlib.Path, default=REPO)
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    errs, subjects = check(args.repo)
    for e in errs:
        print(f"::error::{e}")
    print(f"compliance-trace: {subjects} edges checked, {len(errs)} broken")
    print(f"SUBJECTS={subjects}")
    return 1 if errs and args.enforce else 0


if __name__ == "__main__":
    sys.exit(main())
