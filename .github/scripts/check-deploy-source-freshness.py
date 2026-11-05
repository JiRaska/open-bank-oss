#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Verify a GitOps deploy PR's image pins against immutable base/head commits."""
from __future__ import annotations

import argparse
import importlib.util
import re
import subprocess
import sys
from pathlib import Path
from typing import Callable


_DEPLOY_WINDOW_PATH = Path(__file__).with_name("deploy-window.py")
_SPEC = importlib.util.spec_from_file_location("deploy_window", _DEPLOY_WINDOW_PATH)
if _SPEC is None or _SPEC.loader is None:
    raise RuntimeError(f"cannot load deploy verifier: {_DEPLOY_WINDOW_PATH}")
_DEPLOY_WINDOW = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(_DEPLOY_WINDOW)

verify_pins = _DEPLOY_WINDOW.verify_pins
image_source_is_current = _DEPLOY_WINDOW.image_source_is_current
PIN_RE = _DEPLOY_WINDOW.PIN_RE
SHA = re.compile(r"[0-9a-f]{40}")
GITOPS_ROOT = "openbank-infra/gitops/"


def _git(root: Path, *args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(["git", *args], cwd=root, capture_output=True, text=True, check=check)


def verify(root: Path, base: str, head: str,
           fresh: Callable[[str, str], bool] | None = None) -> list[str]:
    """Return failures for any candidate that is not a fresh GitOps-only descendant.

    `root` must be checked out at `base`; the PR diff itself is always read from the
    immutable `base` and `head` commit objects, never from a mutable pull-request URL.
    `fresh` is injectable for small unit tests; production uses the deploy-window proof.
    """
    if not SHA.fullmatch(base) or not SHA.fullmatch(head):
        return ["base and head must be full lowercase commit SHAs"]

    current = _git(root, "rev-parse", "--verify", "HEAD^{commit}", check=False)
    if current.returncode or current.stdout.strip() != base:
        return ["checkout HEAD does not equal the selected base SHA"]

    for label, sha in (("base", base), ("head", head)):
        exists = _git(root, "cat-file", "-e", f"{sha}^{{commit}}", check=False)
        if exists.returncode:
            return [f"selected {label} commit is unavailable"]

    ancestor = _git(root, "merge-base", "--is-ancestor", base, head, check=False)
    if ancestor.returncode:
        return ["selected base is not an ancestor of selected head"]

    changed = _git(root, "diff", "--name-only", "-z", "--no-ext-diff", base, head)
    paths = [p for p in changed.stdout.split("\0") if p]
    if not paths:
        return ["selected commits contain no changed GitOps image pins"]
    outside = [p for p in paths if not p.startswith(GITOPS_ROOT)]
    if outside:
        return [f"deploy candidate changes a path outside {GITOPS_ROOT}: {outside[0]}"]

    patch = _git(root, "diff", "--no-ext-diff", "--no-color", base, head,
                 "--", GITOPS_ROOT).stdout
    source_is_current = fresh or (
        lambda image, tag: image_source_is_current(str(root), image, tag)
    )
    freshness_by_pin: dict[tuple[str, str], bool] = {}

    def check_fresh(image: str, tag: str) -> bool:
        key = (image, tag)
        if key not in freshness_by_pin:
            freshness_by_pin[key] = source_is_current(image, tag)
        return freshness_by_pin[key]

    added_pins = [match.group("image", "tag") for line in patch.splitlines()
                  if line.startswith("+") and not line.startswith("+++")
                  for match in PIN_RE.finditer(line)]
    if not added_pins:
        return ["no added GitOps image pins found"]

    # Keep the transition check for replaced pins, then separately verify every newly added
    # pin. The existing parser pairs additions with a removed pin for the same image; by itself
    # it misses a stale second pin added beside an otherwise fresh replacement.
    transition_failures = verify_pins(patch, check_fresh)
    failures = [] if transition_failures == ["no image pins found in the GitOps diff"] else [
        f"stale or unverifiable GitOps image pin: {pin}" for pin in transition_failures
    ]
    for image, tag in added_pins:
        if not check_fresh(image, tag):
            service = image.rsplit("/", 1)[-1]
            failures.append(
                f"stale or unverifiable added GitOps image pin: {service}:{tag}"
            )
    return failures


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument("--base", required=True, help="full immutable base commit SHA")
    parser.add_argument("--head", required=True, help="full immutable PR head commit SHA")
    args = parser.parse_args()
    root = args.root.resolve()
    failures = verify(root, args.base, args.head)
    for failure in failures:
        print(f"::error::{failure}", file=sys.stderr)
    if failures:
        return 1
    print(f"GitOps image pins are current for base {args.base} and head {args.head}.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
