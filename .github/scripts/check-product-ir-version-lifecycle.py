#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
"""ADR-0352 §9 phase 6 `product-ir-version-lifecycle`: the supported `irVersion` set is bounded and
shrinking it is a declared breaking change.

Three rules, read from `IrVersion.SUPPORTED` in ProductIr.kt (the set the parser accepts):

  1. AT MOST TWO versions are supported at once — the old one consumers still pin, and its successor.
  2. Every supported version has its immutable schema, `product-ir/v<N>.schema.json` (ADR-0352 §1).
  3. With --base: a version supported on the base and missing here is a REMOVAL. A published revision
     pinned to it would stop loading, so the PR must carry a Conventional Commits breaking marker —
     `!:` in the title, or `BREAKING CHANGE` in the body — which also makes release-please cut a major.
     Adding a version is not breaking and needs nothing.

Prints SUBJECTS=<number of supported versions>.

On a pull_request (--event pull_request) an empty --base FAILS: the removal rule would otherwise be
skipped silently on exactly the event it exists for. On a push to main there is no PR and no base.

Usage: check-product-ir-version-lifecycle.py [--root DIR] [--base REF] [--title T] [--body B] [--event E] | --self-test
"""

from __future__ import annotations

import argparse
import contextlib
import io
import pathlib
import re
import subprocess
import sys
import tempfile

IR_KT = "openbank-libs-domain/src/main/kotlin/com/openbank/libs/product/ir/ProductIr.kt"
SCHEMA_DIR = "openbank-libs-domain/src/main/resources/product-ir"
MAX_SUPPORTED = 2

OBJECT_RE = re.compile(r"object\s+IrVersion\s*\{(?P<body>.*?)\n\}", re.S)
CONST_RE = re.compile(r"const\s+val\s+(V\d+)\s*:\s*Int\s*=\s*(\d+)")
SUPPORTED_RE = re.compile(r"val\s+SUPPORTED\s*:\s*Set<Int>\s*=\s*setOf\(([^)]*)\)")


def strip_comments(text: str) -> str:
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def supported(text: str) -> set[int]:
    m = OBJECT_RE.search(strip_comments(text))
    if not m:
        raise ValueError("object IrVersion not found")
    body = m.group("body")
    consts = {name: int(v) for name, v in CONST_RE.findall(body)}
    s = SUPPORTED_RE.search(body)
    if not s:
        raise ValueError("IrVersion.SUPPORTED is not a literal setOf(...)")
    out = set()
    for tok in filter(None, (t.strip() for t in s.group(1).split(","))):
        if tok.isdigit():
            out.add(int(tok))
        elif tok in consts:
            out.add(consts[tok])
        else:
            raise ValueError(f"cannot resolve SUPPORTED element {tok!r}")
    return out


def breaking(title: str, body: str) -> bool:
    return bool(re.match(r"^\w+(\([^)]*\))?!:", title or "")) or "BREAKING CHANGE" in (body or "")


def base_text(root: pathlib.Path, base: str) -> str | None:
    r = subprocess.run(["git", "-C", str(root), "show", f"{base}:{IR_KT}"], capture_output=True, text=True)
    return r.stdout if r.returncode == 0 else None


def run(root: pathlib.Path, base: str, title: str, body: str, base_reader=base_text, event: str = "") -> int:
    path = root / IR_KT
    if not path.is_file():
        print(f"::error::{IR_KT} not found")
        return 1
    try:
        now = supported(path.read_text(encoding="utf-8"))
    except ValueError as e:
        print(f"::error::{IR_KT}: {e}")
        return 1
    print(f"SUBJECTS={len(now)}")
    findings = []
    if not now:
        findings.append("IrVersion.SUPPORTED is empty: no revision could load")
    if len(now) > MAX_SUPPORTED:
        findings.append(f"{len(now)} irVersions supported {sorted(now)}; at most {MAX_SUPPORTED} (ADR-0352 §9 phase 6)")
    for v in sorted(now):
        if not (root / SCHEMA_DIR / f"v{v}.schema.json").is_file():
            findings.append(f"irVersion {v} is supported but {SCHEMA_DIR}/v{v}.schema.json does not exist")
    if base:
        old_text = base_reader(root, base)
        if old_text is None:
            print(f"base {base}: {IR_KT} absent, nothing to compare")
        else:
            removed = sorted(supported(old_text) - now)
            if removed and not breaking(title, body):
                findings.append(
                    f"irVersion {removed} removed from SUPPORTED without a breaking marker; a revision pinned to it "
                    "would stop loading — mark the PR breaking (`<type>(<scope>)!:` title or `BREAKING CHANGE` in the body)"
                )
            elif removed:
                print(f"irVersion {removed} removed, declared breaking")
    elif event == "pull_request":
        findings.append("pull_request with no --base: refusing to skip the removal rule (PR_DIFF_BASE unset)")
    else:
        print("no --base: the removal rule is not evaluated on this run (not a pull_request)")
    for f in findings:
        print(f"::error::{f}")
    if not findings:
        print(f"OK: supported irVersions {sorted(now)}")
    return 1 if findings else 0


def _fixture(root: pathlib.Path, consts: str, members: str, schemas: list[int]) -> None:
    kt = root / IR_KT
    kt.parent.mkdir(parents=True, exist_ok=True)
    kt.write_text(
        "package x\n\nobject IrVersion {\n" + consts + "\n    // val SUPPORTED: Set<Int> = setOf(V9)\n"
        f"    val SUPPORTED: Set<Int> = setOf({members})\n}}\n",
        encoding="utf-8",
    )
    d = root / SCHEMA_DIR
    d.mkdir(parents=True, exist_ok=True)
    for v in schemas:
        (d / f"v{v}.schema.json").write_text("{}", encoding="utf-8")


def self_test() -> int:
    failures = []
    v12 = "    const val V1: Int = 1\n    const val V2: Int = 2"
    v123 = v12 + "\n    const val V3: Int = 3"

    ran = []

    def case(label, want, text, consts, members, schemas, base_members=None, title="", body="", event=""):
        ran.append(label)
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            _fixture(root, consts, members, schemas)
            reader = None
            if base_members is not None:
                with tempfile.TemporaryDirectory() as btmp:
                    _fixture(pathlib.Path(btmp), v123, base_members, [])
                    old = (pathlib.Path(btmp) / IR_KT).read_text()
                reader = lambda _r, _b: old  # noqa: E731
            buf = io.StringIO()
            with contextlib.redirect_stdout(buf):
                rc = run(root, "base" if reader else "", title, body, reader or base_text, event=event)
            out = buf.getvalue()
            if rc != want or text not in out:
                failures.append(f"{label}: exit {rc} (want {want}), output:\n{out}")

    case("one version is green", 0, "SUBJECTS=1", v12, "V1", [1])
    case("two versions are green", 0, "OK: supported irVersions [1, 2]", v12, "V1, V2", [1, 2])
    case("three versions are red", 1, "at most 2", v123, "V1, V2, V3", [1, 2, 3])
    case("a commented-out member is not counted", 0, "SUBJECTS=2", v12, "V1, V2", [1, 2])
    case("a supported version without a schema is red", 1, "v2.schema.json does not exist", v12, "V1, V2", [1])
    case("an empty set is red", 1, "is empty", v12, "", [])
    case("an unresolvable member is red", 1, "cannot resolve", v12, "V1, V7", [1])
    case("removal without a marker is red", 1, "without a breaking marker", v12, "V2", [2], "V1, V2", "feat(libs): drop v1")
    case("removal with a ! title is green", 0, "declared breaking", v12, "V2", [2], "V1, V2", "feat(libs)!: drop v1")
    case("removal with BREAKING CHANGE body is green", 0, "declared breaking", v12, "V2", [2], "V1, V2", "feat(libs): x",
         "BREAKING CHANGE: v1 revisions must be migrated")
    case("a pull_request without a base is red", 1, "refusing to skip the removal rule", v12, "V1", [1], event="pull_request")
    case("a push without a base is green", 0, "not evaluated", v12, "V1", [1], event="push")
    case("adding a version needs no marker", 0, "OK", v12, "V1, V2", [1, 2], "V1", "feat(libs): add v2")
    for f in failures:
        print(f"SELF-TEST FAIL {f}")
    print(f"self-test: {len(ran) - len(failures)}/{len(ran)} passed")
    return 1 if failures else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--base", default="")
    ap.add_argument("--title", default="")
    ap.add_argument("--body", default="")
    ap.add_argument("--event", default="", help="GITHUB_EVENT_NAME; on pull_request an empty --base is a failure")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    return run(pathlib.Path(args.root), args.base, args.title, args.body, event=args.event)


if __name__ == "__main__":
    sys.exit(main())
