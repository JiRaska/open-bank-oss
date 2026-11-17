#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Every money-path service is either mutation-tested weekly, or carries a written exclusion.

WHY THIS EXISTS (ADR-0063, ADR-0030 D3)
---------------------------------------
The weekly pitest matrix in `.github/workflows/pitest.yml` is a hand-kept list of the thing it
should cover. `rules.yaml: money_path_services` grew from 13 to 28 and the matrix was extended by
audits, not by a control: pension (392 branch sites over 31 domain files) and pension-fund
(98 over 8) were money-path for weeks and never measured, while the exclusion prose in rules.yaml
still described lending as "1 domain file, 0 branches" long after it had 7 and 28. A list nobody
is forced to reconcile reads as complete, never as unchecked.

THE RULE
--------
Derive both sets and require them to partition `money_path_services`:
  * IN the matrix       -> the module's build.gradle.kts applies the pitest plugin (otherwise the
                           weekly job runs `:svc:pitest` against nothing and reports no mutations);
  * NOT in the matrix   -> it must appear in EXCLUDED below with a reason, and the measured number
                           of branch sites in its domain layer must not exceed THIN_DOMAIN_BRANCHES
                           (the exclusion is a claim about the domain being thin, so it is checked);
  * a matrix entry that is not money-path, or an exclusion that is now in the matrix, is stale.

Usage:  check-pitest-money-path-coverage.py [--repo PATH]
        check-pitest-money-path-coverage.py --self-test
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys
import tempfile

import yaml

REPO = pathlib.Path(__file__).resolve().parents[2]
BRANCH = re.compile(r"\b(?:if|when|require|check)\s*\(")
# Above this many branch sites a domain layer is not "thin"; adding it to the matrix is the fix.
THIN_DOMAIN_BRANCHES = 12

# Money-path modules deliberately outside the weekly mutation matrix. Reasons must stay true:
# the check re-measures the branch count and fails when an exclusion outgrows the thin-domain bar.
EXCLUDED = {
    "openbank-clearing-service": "domain is plain data models (measured 1 branch site)",
    "openbank-billing-service": "domain layer does not branch; fee logic lives in application/usecase, outside domain.* (ADR-0143)",
    "openbank-settlement-service": "single data-model domain file, 0 branch sites",
    "openbank-psd2-service": "single domain file, 0 branch sites",
    "openbank-card-processing-service": "10 branch sites over 7 domain files, input validation and state legality, no arithmetic",
    "openbank-swift-service": "branches are message validation and state legality, not money arithmetic",
    "openbank-lending-service": "recorded exclusion pending its own measurement (28 branch sites over 7 files on 2026-11-10)",
}
# Exclusions that already exceed the thin bar and are tracked as debt instead of failing the build.
# Shrink-only: delete an entry when the service enters the matrix.
OVER_THIN_BASELINE = {"openbank-swift-service", "openbank-lending-service", "openbank-card-processing-service"}


def matrix(workflow: str) -> set[str]:
    head = workflow.split("steps:")[0]
    return set(re.findall(r"^\s+- (openbank-[a-z0-9-]+)\s*(?:#.*)?$", head, re.M))


def branches(repo: pathlib.Path, module: str) -> int:
    n = 0
    for f in (repo / module / "src/main/kotlin").rglob("*.kt"):
        if "/domain/" in f.as_posix():
            n += len(BRANCH.findall(f.read_text(errors="replace")))
    return n


def check(repo: pathlib.Path, excluded=EXCLUDED, baseline=OVER_THIN_BASELINE) -> list[str]:
    rules = yaml.safe_load((repo / "openbank-libs/governance/rules.yaml").read_text())
    money = list(rules["money_path_services"])
    in_matrix = matrix((repo / ".github/workflows/pitest.yml").read_text())
    errs: list[str] = []
    for svc in money:
        if svc in in_matrix:
            gradle = repo / svc / "build.gradle.kts"
            if not gradle.exists() or "info.solidsoft.pitest" not in gradle.read_text():
                errs.append(f"{svc}: in the pitest matrix but build.gradle.kts does not apply the pitest plugin")
            if svc in excluded:
                errs.append(f"{svc}: in the pitest matrix AND in EXCLUDED; delete the stale exclusion")
        elif svc not in excluded:
            errs.append(f"{svc}: money-path but neither in the pitest.yml matrix nor in EXCLUDED with a reason")
        elif branches(repo, svc) > THIN_DOMAIN_BRANCHES and svc not in baseline:
            errs.append(f"{svc}: excluded as thin but has {branches(repo, svc)} domain branch sites (> {THIN_DOMAIN_BRANCHES}); add it to the matrix")
    for svc in sorted(in_matrix - set(money)):
        errs.append(f"{svc}: in the pitest matrix but not in rules.yaml money_path_services (the matrix is NOT the source of truth)")
    for svc in sorted(set(excluded) - set(money)):
        errs.append(f"{svc}: in EXCLUDED but not money-path; delete the entry")
    for svc in sorted(baseline - set(excluded)):
        errs.append(f"{svc}: in OVER_THIN_BASELINE but not excluded; delete the entry")
    return errs


def _fixture(root: pathlib.Path, money, mx, plugin_for, branchy=None):
    (root / "openbank-libs/governance").mkdir(parents=True)
    (root / "openbank-libs/governance/rules.yaml").write_text(yaml.safe_dump({"money_path_services": money}))
    (root / ".github/workflows").mkdir(parents=True)
    lines = "\n".join(f"          - {m}" for m in mx)
    (root / ".github/workflows/pitest.yml").write_text(f"matrix:\n  service:\n{lines}\n    steps:\n")
    for m in money:
        d = root / m
        d.mkdir(parents=True, exist_ok=True)
        (d / "build.gradle.kts").write_text('id("info.solidsoft.pitest")' if m in plugin_for else "")
        dom = d / "src/main/kotlin/x/domain"
        dom.mkdir(parents=True)
        (dom / "A.kt").write_text("if (a) {}\n" * (30 if m == branchy else 1))


def self_test() -> int:
    ok = True

    def run(name, expect_err, **kw):
        nonlocal ok
        with tempfile.TemporaryDirectory() as t:
            root = pathlib.Path(t)
            _fixture(root, kw["money"], kw["mx"], kw.get("plugin", kw["mx"]), kw.get("branchy"))
            errs = check(root, kw.get("excluded", {}), kw.get("baseline", set()))
        good = bool(errs) == expect_err
        print(("PASS " if good else "FAIL ") + name + ("" if good else f" -> {errs}"))
        ok &= good

    run("clean partition", False, money=["openbank-a", "openbank-b"], mx=["openbank-a"], excluded={"openbank-b": "thin"})
    run("money-path service in neither set", True, money=["openbank-a", "openbank-b"], mx=["openbank-a"])
    run("matrix entry without plugin", True, money=["openbank-a"], mx=["openbank-a"], plugin=[])
    run("matrix entry not money-path", True, money=["openbank-a"], mx=["openbank-a", "openbank-z"])
    run("stale exclusion also in matrix", True, money=["openbank-a"], mx=["openbank-a"], excluded={"openbank-a": "x"})
    run("exclusion outgrew thin bar", True, money=["openbank-a", "openbank-b"], mx=["openbank-a"],
        excluded={"openbank-b": "thin"}, branchy="openbank-b")
    run("outgrown exclusion baselined", False, money=["openbank-a", "openbank-b"], mx=["openbank-a"],
        excluded={"openbank-b": "thin"}, branchy="openbank-b", baseline={"openbank-b"})
    return 0 if ok else 1


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", default=str(REPO))
    ap.add_argument("--self-test", action="store_true")
    a = ap.parse_args()
    if a.self_test:
        return self_test()
    repo = pathlib.Path(a.repo)
    errs = check(repo)
    mx = matrix((repo / ".github/workflows/pitest.yml").read_text())
    money = yaml.safe_load((repo / "openbank-libs/governance/rules.yaml").read_text())["money_path_services"]
    print(f"SUBJECTS={len(money)}  # money_path_services examined")
    print(f"pitest matrix: {len(mx)} modules; excluded with reason: {len(EXCLUDED)}")
    for e in errs:
        print("FAIL " + e)
    return 1 if errs else 0


if __name__ == "__main__":
    sys.exit(main())
