#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Refuse an OPA bundle whose embedded policy OPA cannot load.

THE FAILURE THIS EXISTS TO PREVENT

Every gate this repo had over OPA policy looked at the SOURCE `.rego` files. The thing the
sidecar actually mounts is the generated ConfigMap, and nothing read that. On 2026-08-07 the
difference cost sca-service its entire authorization layer.

`sca_rest_ext.rego` (added by #3748) ended without a trailing newline. Its generator inlines
sources with

    sed 's/^/    /' "$FILE" | sed 's/[[:space:]]*$//'
    echo "  agents.rego: |"

and `sed` on a file with no final newline emits none, so the `echo` landed on the same line.
The committed bundle contained

    }  agents.rego: |

which did two things at once: it DROPPED the `agents.rego` data key (the deployed bundle held
only agents-data.yaml, manifest.json, rest.rego, rules-data.yaml, sca_rest_ext.rego), and it
made the embedded ext policy unparseable —

    sca_rest_ext.rego:92: rego_parse_error: unexpected : token

sca-service runs `AUTHZ_ENFORCE=true`. A PDP that cannot load its bundle fails closed, so every
`@Authorize` endpoint denies: the SCA challenge path that gates payments and document signing.

WHY NOTHING CAUGHT IT, WHICH IS THE POINT

  - `opa test` loads the SOURCE `.rego` files. Individually they were valid. The corruption
    exists only in the generated artifact.
  - The bundle-drift check regenerates and diffs against what is committed. It ran the SAME
    broken generator, so committed and regenerated agreed and the check was green about a file
    that could not be loaded.
  - `yaml.safe_load` parses the broken bundle happily — the swallowed key is legal YAML, just
    the wrong shape. So a YAML-validity gate cannot see it either.

The lesson generalises past this one bug: a generated artifact needs a gate that reads the
ARTIFACT with the consumer's own parser. Regenerating it with the producer only proves the
producer is deterministic.

WHAT THIS CHECKS

For every `openbank-infra/gitops/components/*/*opa-bundle*.yaml`:

  1. STRUCTURAL (always runs, no external tooling). No `data` value may contain a line that
     looks like another ConfigMap key — `<name>.rego: |` or `<name>.yaml: |` — indented deeper
     than column 0. That is the signature of a swallowed key, and it is exactly the shape the
     sca bundle shipped.
  2. PARSE (runs when `opa` is on PATH). Every `*.rego` data key is written out and fed to
     `opa check`. A `rego_parse_error` fails the gate.

If `opa` is absent the parse half is SKIPPED AND SAID SO, loudly, rather than passing silently
— a gate that degrades to "could not check" while reporting success is the failure mode this
repo keeps rediscovering. The structural half alone already catches the observed bug, so the
gate retains teeth on a runner without `opa`.

DELIBERATELY NOT CHECKED: whether the policy is CORRECT, or whether it matches the source
`.rego`. Correctness is `opa test`'s job and drift is the drift check's job. This gate answers
one question the others cannot: can the thing we are about to deploy be loaded at all.
"""

from __future__ import annotations

import argparse
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import yaml

# A ConfigMap key line that ended up INSIDE another key's block scalar. Column 0 is where a
# real top-level key lives; anything indented is either legitimate content or this bug. We
# require the `: |` block-scalar introducer, which ordinary rego/yaml content does not carry.
#
# Two things the self-test had to teach me before this pattern was right, both of which would
# have shipped a gate that passed its own tests and missed the real bug:
#
#   1. The swallowed key trails the previous file's LAST LINE — the observed corruption is
#      `}  agents.rego: |`, not a line that starts with the key name.
#   2. More importantly, we are matching against the value AFTER yaml parsed it, and parsing a
#      block scalar STRIPS its indentation. So requiring leading whitespace matches nothing,
#      ever — the very first version of this regex was green against a corrupt bundle.
#
# So: anchor on the `: |` tail, which is the actual signature, and allow anything before it.
SWALLOWED_KEY = re.compile(r"^.*?[\w.-]+\.(rego|yaml|json):[ \t]*\|[ \t]*$", re.MULTILINE)


def bundles(root: Path) -> list[Path]:
    """Every generated OPA bundle, derived from the tree — never a hand-kept list."""
    return sorted((root / "openbank-infra" / "gitops" / "components").glob("*/*opa-bundle*.yaml"))


def check_structure(path: Path) -> list[str]:
    """Flag a data value that has swallowed what should have been a sibling key."""
    problems: list[str] = []
    try:
        doc = yaml.safe_load(path.read_text())
    except yaml.YAMLError as exc:
        return [f"{path}: not valid YAML: {exc}"]
    if not isinstance(doc, dict):
        return [f"{path}: top level is not a mapping"]
    for key, value in (doc.get("data") or {}).items():
        if not isinstance(value, str):
            continue
        for hit in SWALLOWED_KEY.finditer(value):
            line = hit.group(0).strip()
            problems.append(
                f"{path}: data key '{key}' has swallowed what looks like a sibling key "
                f"({line!r}). The generator almost certainly inlined a source file that does "
                f"not end in a newline, so the next `echo` landed on the same line. Fix the "
                f"source file's trailing newline AND make the generator pipe through `awk 1`."
            )
    return problems


def check_parses(path: Path) -> list[str]:
    """Feed every embedded .rego to `opa check`. Requires opa on PATH."""
    try:
        doc = yaml.safe_load(path.read_text())
    except yaml.YAMLError:
        return []  # already reported by check_structure
    data = (doc or {}).get("data") or {}
    regos = {k: v for k, v in data.items() if k.endswith(".rego") and isinstance(v, str)}
    if not regos:
        return [f"{path}: contains no .rego data key — a policy bundle with no policy"]
    with tempfile.TemporaryDirectory() as tmp:
        for name, body in regos.items():
            (Path(tmp) / name).write_text(body)
        proc = subprocess.run(
            ["opa", "check", *[str(Path(tmp) / n) for n in regos]],
            capture_output=True,
            text=True,
        )
    if proc.returncode != 0:
        detail = (proc.stderr or proc.stdout).strip().replace(tmp, "<bundle>")
        return [f"{path}: OPA cannot load the embedded policy:\n    {detail}"]
    return []


def run(root: Path) -> int:
    found = bundles(root)
    if not found:
        print("::error::no OPA bundles found — this gate's scope is empty, which is itself a bug")
        return 1

    have_opa = shutil.which("opa") is not None
    problems: list[str] = []
    for bundle in found:
        problems.extend(check_structure(bundle))
        if have_opa:
            problems.extend(check_parses(bundle))

    for p in problems:
        print(f"::error::{p}")

    if not have_opa:
        # Loud on purpose. Reporting success while having skipped half the gate is how a
        # check becomes decorative.
        print(
            "::warning::`opa` is not on PATH, so the PARSE half of this gate did not run; "
            "only the structural check did. Install opa on this runner to get full coverage."
        )

    scope = f"{len(found)} bundle(s), parse-check {'ON' if have_opa else 'SKIPPED'}"
    if problems:
        print(f"FAIL: {len(problems)} problem(s) across {scope}")
        return 1
    print(f"OK: {scope}, no unloadable policy")
    return 0


def self_test() -> int:
    """Feed the checker the exact corruption it exists to catch, and a clean control.

    A gate that has only ever passed is unfalsified. Both directions are asserted here: the
    broken bundle MUST be flagged, and the clean one MUST NOT be — a checker that fails
    everything is as useless as one that fails nothing.
    """
    failures = 0
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        comp = root / "openbank-infra" / "gitops" / "components" / "svc"
        comp.mkdir(parents=True)

        clean = comp / "svc-opa-bundle.yaml"
        clean.write_text(
            "apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: svc-opa-bundle\ndata:\n"
            "  rest.rego: |\n    package openbank.rest\n    default allow := false\n"
            "  agents.rego: |\n    package openbank.agents\n    default charter := {}\n"
        )
        if check_structure(clean):
            print("::error::self-test: a well-formed bundle was flagged")
            failures += 1

        # The #3748 shape: `agents.rego: |` swallowed into the previous key's block scalar.
        broken = comp / "broken-opa-bundle.yaml"
        broken.write_text(
            "apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: broken-opa-bundle\ndata:\n"
            "  rest.rego: |\n    package openbank.rest\n    default allow := false\n"
            "    }  agents.rego: |\n    package openbank.agents\n"
        )
        if not check_structure(broken):
            print("::error::self-test: the swallowed-key corruption was NOT flagged")
            failures += 1

        # And end to end, so the runner path is exercised too, not just the predicate.
        if run(root) == 0:
            print("::error::self-test: run() passed a tree containing a corrupt bundle")
            failures += 1

    if failures:
        print(f"self-test FAILED ({failures})")
        return 1
    print("self-test OK: corruption flagged, clean bundle untouched, runner fails on it")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--root", default=".", help="repository root")
    ap.add_argument("--self-test", action="store_true", help="falsify the checker itself")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    return run(Path(args.root))


if __name__ == "__main__":
    sys.exit(main())
