#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Guard: every Pact PROVIDER name is exactly a module directory in this repo.
#
# WHY THIS EXISTS
#   The verification reconciler
#   .github/scripts/pact-reconcile-verifications.py dispatches verify-provider.yml
#   with the broker's PROVIDER pacticipant name as its `service` input — a workflow
#   input that must name a module directory. That works today (55 of 55 match,
#   checked against the live broker), and it works ONLY because the two vocabularies
#   happen to coincide.
#
#   Nothing was holding them together. Register a provider as `ledger` instead of
#   `openbank-ledger-service`, or rename a module directory, and the reconciler keeps
#   dispatching — it just dispatches a build of a service that does not exist. GitHub
#   accepts the dispatch (the input is a free-form string), the run fails somewhere
#   inside the build, and the pact it was supposed to verify stays unverified. The
#   symptom would be indistinguishable from the problem the reconciler was built to
#   fix, which is the worst possible failure mode for it.
#
#   So the coincidence becomes an invariant, checked offline: this reads the
#   committed pacts, not the broker, so it needs no credentials and no network and
#   runs on every PR.
#
# WHAT IT CHECKS
#   For every pacts/*.json, `provider.name` must be a directory in the repository
#   root that carries a build.gradle.kts — i.e. a real Gradle module
#   verify-provider.yml could build.
#
#   `consumer.name` is NOT checked: the reconciler never dispatches by consumer,
#   and a consumer need not be a Gradle module at all — the first one that is not
#   is openbank-admin-ui (a Next.js web client consuming
#   openbank-case-coordinator-agent, ADR-0246), whose contract is still enforced by
#   the provider-side @PactFolder replay gate (check-pact-provider-replay.py).
#
# Run:  python3 .github/scripts/check-pacticipant-matches-module.py [--root .]

import argparse
import json
import pathlib
import sys

PACTS = "pacts/*.json"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument(
        "--self-test",
        action="store_true",
        help="feed the checker a name it MUST flag, and one it must not",
    )
    args = ap.parse_args()
    root = pathlib.Path(args.root).resolve()

    if args.self_test:
        return self_test(root)

    modules = {
        p.name for p in root.iterdir() if p.is_dir() and (p / "build.gradle.kts").is_file()
    }
    if not modules:
        sys.stderr.write(
            "::error::found no Gradle modules at the repo root — refusing to report success, "
            "because with an empty module set every pacticipant name would 'not match' or, "
            "depending on the comparison, every one would pass vacuously.\n"
        )
        return 2

    pacts = sorted(root.glob(PACTS))
    if not pacts:
        sys.stderr.write(
            "::error::no pacts/*.json found — this guard would then be checking nothing. "
            "If the pacts genuinely moved, update PACTS here in the same commit.\n"
        )
        return 2

    errors = []
    names = set()
    for f in pacts:
        try:
            doc = json.loads(f.read_text())
        except json.JSONDecodeError as e:
            errors.append(f"{f.name}: not valid JSON: {e}")
            continue
        name = ((doc.get("provider") or {}).get("name") or "").strip()
        if not name:
            errors.append(f"{f.name}: provider.name is missing or empty")
            continue
        names.add(name)
        if name not in modules:
            errors.append(
                f"{f.name}: provider '{name}' is not a Gradle module directory. The Pact "
                f"verification reconciler dispatches verify-provider.yml with the provider "
                f"pacticipant name as its `service` input, so a name that is not a module "
                f"directory dispatches a build of nothing and the pact stays unverified. "
                f"Rename the provider pacticipant to match the module, or stop relying on "
                f"the identity in .github/scripts/pact-reconcile-verifications.py."
            )

    if errors:
        for e in errors:
            sys.stderr.write(f"::error title=Pacticipant name::{e}\n")
        return 1

    print(
        f"pacticipant/module identity: {len(names)} distinct provider name(s) across "
        f"{len(pacts)} pact(s), every one a Gradle module directory."
    )
    return 0


def self_test(root: pathlib.Path) -> int:
    """Feed the comparison a name it MUST flag and one it must not.

    Deliberately does not touch the real pacts/ directory: a self-test that mutated the
    tree could leave it dirty and turn an unrelated drift gate red.
    """
    modules = {
        p.name for p in root.iterdir() if p.is_dir() and (p / "build.gradle.kts").is_file()
    }
    if not modules:
        print("::error::self-test: no modules found, cannot exercise the comparison")
        return 1
    good = sorted(modules)[0]
    bad = "definitely-not-a-module-" + good

    failures = []
    if bad in modules:
        failures.append("the known-BAD name is somehow a real module — pick another")
    if good not in modules:
        failures.append("the known-GOOD name is not recognised as a module")

    if failures:
        for f in failures:
            print(f"::error::self-test: {f}")
        return 1
    print(
        f"self-test: '{good}' recognised as a module and "
        f"'{bad}' rejected — the comparison can tell them apart."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
