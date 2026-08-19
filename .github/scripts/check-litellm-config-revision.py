#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Editing the LiteLLM config without bumping the pod-roll annotation changes nothing, and ArgoCD stays green.

WHY THIS EXISTS
---------------
LiteLLM parses `--config` ONCE at startup and the ConfigMap is a plain volume mount. So a config
edit reaches the pod's filesystem and the proxy keeps serving the model list it booted with. The
manifest has warned about this in prose since #1919:

    # BUMP THIS whenever litellm-config.yaml changes; that is what rolls the pod.

Prose is not a control. The failure is the invisible kind — ArgoCD reports Synced and Healthy, the
ConfigMap in the cluster genuinely contains the new route, and the caller gets `model not found`
from a pod that never re-read the file. Three routes were added to that config in one week
(llama-3.3, llama-guard, bge-m3); each depended on somebody remembering a comment.

WHAT IT CHECKS
--------------
If `litellm-config.yaml` changed against the PR's merge base, then the
`openbank.tech/litellm-config-revision` annotation in `litellm.yaml` must have changed too. It does
not check the DIRECTION or the numbering — any different value rolls the pod, and inventing a
monotonicity rule would fail a legitimate revert.

Needs a diff base, so it is a pull_request-only gate: with no base it refuses to run rather than
reporting a vacuous pass.

Usage:  check-litellm-config-revision.py [--enforce] [--self-test]
"""

from __future__ import annotations

import argparse
import os
import pathlib
import re
import subprocess
import sys

REPO = pathlib.Path(__file__).resolve().parents[2]
CONFIG = "openbank-infra/gitops/components/ai-platform/litellm-config.yaml"
DEPLOY = "openbank-infra/gitops/components/ai-platform/litellm.yaml"
ANNOTATION = "openbank.tech/litellm-config-revision"
REVISION_RE = re.compile(rf"{re.escape(ANNOTATION)}:\s*\"?([^\"\s]+)\"?")


def git_show(ref: str, path: str) -> str | None:
    """File content at `ref`, or None when it does not exist there."""
    proc = subprocess.run(
        ["git", "-C", str(REPO), "show", f"{ref}:{path}"],
        capture_output=True, text=True, check=False,
    )
    return proc.stdout if proc.returncode == 0 else None


def revision(text: str | None) -> str | None:
    if text is None:
        return None
    m = REVISION_RE.search(text)
    return m.group(1) if m else None


def evaluate(base_config: str | None, head_config: str, base_rev: str | None, head_rev: str | None) -> list[str]:
    """Pure decision, so the self-test drives the real rule rather than a re-derivation of it."""
    if head_rev is None:
        return [
            f"::error file={DEPLOY}::the {ANNOTATION} annotation is missing. It is the only thing "
            f"that rolls the LiteLLM pod when its config changes; without it a config edit is a no-op "
            f"against a green, in-sync ArgoCD."
        ]
    if base_config is None or base_config == head_config:
        return []
    if base_rev == head_rev:
        return [
            f"::error file={DEPLOY}::{CONFIG} changed but {ANNOTATION} is still \"{head_rev}\". LiteLLM "
            f"parses --config once at startup, so the pod keeps serving the model list it booted with: "
            f"the new route answers 'model not found' while ArgoCD reports Synced and Healthy. Bump the "
            f"annotation in the same commit."
        ]
    return []


def findings(base_arg: str = "") -> tuple[list[str], int]:
    # Taken from the flag first so the gate command names $PR_DIFF_BASE explicitly — the manifest
    # linter reads the command text to decide whether a gate needs a base, and a script that only
    # reads the environment would be classified as needing none.
    base = (base_arg or os.environ.get("PR_DIFF_BASE", "")).strip()
    if not base:
        print("::error::PR_DIFF_BASE is empty but this gate requires it — refusing to run vacuously")
        sys.exit(1)
    head_config = (REPO / CONFIG).read_text(errors="ignore")
    head_deploy = (REPO / DEPLOY).read_text(errors="ignore")
    return evaluate(
        base_config=git_show(base, CONFIG),
        head_config=head_config,
        base_rev=revision(git_show(base, DEPLOY)),
        head_rev=revision(head_deploy),
    ), 1


def self_test() -> int:
    ok = True

    def case(label: str, base_cfg, head_cfg, base_rev, head_rev, expected) -> None:
        nonlocal ok
        got = len(evaluate(base_cfg, head_cfg, base_rev, head_rev))
        if got != expected:
            ok = False
        print(f"  [{'ok ' if got == expected else 'FAIL'}] {label}: found={got} expected={expected}")

    case("config changed, revision NOT bumped — MUST flag", "a", "b", "6", "6", 1)
    case("config changed, revision bumped — must not flag", "a", "b", "6", "7", 0)
    case("config unchanged, revision unchanged — must not flag", "a", "a", "6", "6", 0)
    case("config unchanged but revision bumped anyway — must not flag (rolling is always allowed)",
         "a", "a", "6", "7", 0)
    case("a revert to a lower revision still rolls the pod — must not flag", "a", "b", "7", "6", 0)
    case("annotation missing entirely — MUST flag even with no config change", "a", "a", None, None, 1)
    case("config is new in this PR (absent at base) — must not flag", None, "b", None, "1", 0)

    # The regex must find the annotation in the REAL manifest, or every verdict above is about a
    # value this script can never read — the "gate that passes because it never reached its subject"
    # shape. Asserted against the file on disk, not a fixture.
    real = revision((REPO / DEPLOY).read_text(errors="ignore"))
    if real is None:
        print(f"  [FAIL] the annotation regex does not match {DEPLOY} as committed")
        ok = False
    else:
        print(f"  [ok ] annotation found in the real manifest: {real}")

    print("self-test: PASS" if ok else "self-test: FAIL")
    return 0 if ok else 1


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--base", default="", help="merge base to diff against (PR_DIFF_BASE)")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()

    messages, checked = findings(args.base)
    for line in messages:
        print(line if args.enforce else line.replace("::error", "::warning", 1))
    print(f"SUBJECTS={checked}")
    print(f"check-litellm-config-revision: {'clean.' if not messages else str(len(messages)) + ' finding(s) above.'}")
    return 1 if messages and args.enforce else 0


if __name__ == "__main__":
    sys.exit(main())
