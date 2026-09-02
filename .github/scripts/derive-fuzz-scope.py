#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""The api-fuzz lane's service scope, derived once and consumed by everything that cares.

WHY THIS FILE EXISTS (#5849)
----------------------------
`api-fuzz.yml` derived this set inline, and `openbank-libs/governance/fuzz-coverage.yaml`
restated it by hand. That is this repo's `pact-drift-check.yml` shape all over again: a gate
whose SCOPE is a hand-kept list of the thing it checks reads as PASSING when the list is
short, never as UNCHECKED. Drop a service from fuzz-coverage.yaml and the coverage gate finds
nothing wrong -- it only ever validated the entries that were there -- while the lane keeps
fuzzing a service nobody records.

So the set is computed HERE, from the same inputs the lane actually uses, and both consumers
call this module:

  * .github/workflows/api-fuzz.yml            -- builds its matrix from `--json`
  * .github/scripts/check-readiness-attestations.py -- reconciles fuzz-coverage.yaml against
    `scope()` in BOTH directions, so a missing entry AND a stale one are errors

The candidate set is `rules.yaml: money_path_services` plus the LEGACY list below, filtered
to what the harness can actually stand up. The filters are not cosmetic: without an
application.yaml the port is underivable, and without a postgresql:// URL the harness cannot
provision a database, so the job could not run at all.
"""

from __future__ import annotations

import argparse
import json
import os
import pathlib
import re
import sys

import yaml

REPO = pathlib.Path(__file__).resolve().parents[2]
RULES_REL = "openbank-libs/governance/rules.yaml"

# Services fuzzed before the scope was tied to money_path_services. They are not money-path
# but the lane has covered them since it existed, and silently dropping coverage is exactly
# the failure this module exists to prevent. Removing one is a deliberate act: it changes the
# derived scope, so fuzz-coverage.yaml must lose its entry in the same commit or the gate
# fails.
LEGACY = [
    "openbank-dispute-service",
    "openbank-party-service",
    "openbank-kyc-service",
    "openbank-notification-service",
    "openbank-consent-service",
]

PORT_RE = re.compile(r"^  http:\n(?:.*\n)?\s*port:\s*(\d+)", re.M)
# Reactive-only services carry no jdbc: URL (their Agroal datasource is supplied at boot).
# The DB name is in the reactive URL all the same.
DB_RE = re.compile(r"(?:jdbc:)?(?:vertx-reactive:)?postgresql://[^/]+/([A-Za-z0-9_]+)")


def short_name(module: str) -> str:
    """`openbank-domestic-payment-service` -> `domestic-payment`.

    fuzz-coverage.yaml and attestations.yaml both key on the SHORT name; keying on the module
    name is what made consent's attestation dead from birth (#2365).
    """
    name = module[len("openbank-"):] if module.startswith("openbank-") else module
    return name[: -len("-service")] if name.endswith("-service") else name


def candidates(repo: pathlib.Path = REPO) -> list[str]:
    money = yaml.safe_load((repo / RULES_REL).read_text(encoding="utf-8"))["money_path_services"]
    return list(dict.fromkeys(list(money) + LEGACY))


def scope(repo: pathlib.Path = REPO, override: list[str] | None = None) -> tuple[list[str], list[tuple[str, str]]]:
    """Return (fuzzable module names, [(module, why-excluded)])."""
    keep: list[str] = []
    skipped: list[tuple[str, str]] = []
    for svc in (override or candidates(repo)):
        app = repo / svc / "src/main/resources/application.yaml"
        spec = repo / svc / "src/main/resources/openapi.yaml"
        if not app.is_file() or not spec.is_file():
            skipped.append((svc, "no application.yaml or openapi.yaml"))
            continue
        raw = app.read_text(encoding="utf-8")
        if not PORT_RE.search(raw):
            skipped.append((svc, "no derivable quarkus.http.port"))
            continue
        if not DB_RE.search(raw):
            skipped.append((svc, "no postgresql:// URL -- the harness cannot provision its DB"))
            continue
        keep.append(svc)
    return keep, skipped


def short_scope(repo: pathlib.Path = REPO) -> set[str]:
    """The scope as fuzz-coverage.yaml keys it."""
    keep, _ = scope(repo)
    return {short_name(s) for s in keep}


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--json", action="store_true", help="emit the module list as JSON")
    ap.add_argument("--short", action="store_true", help="emit short names instead of modules")
    ap.add_argument("--repo", default=str(REPO), help="repository root")
    ap.add_argument("--override", nargs="*", default=None, help="explicit module list")
    ap.add_argument(
        "--github-output",
        action="store_true",
        help="append `services=<json>` to $GITHUB_OUTPUT and print the exclusions as "
        "::warning:: annotations (how api-fuzz.yml builds its matrix)",
    )
    args = ap.parse_args()

    repo = pathlib.Path(args.repo).resolve()
    keep, skipped = scope(repo, override=args.override or None)
    names = [short_name(s) for s in keep] if args.short else keep

    if args.github_output:
        # ::warning:: is only read from stdout, so the exclusions go there -- a service the
        # harness cannot stand up must be VISIBLE, not quietly absent from the matrix.
        for svc, why in skipped:
            print(f"::warning::api-fuzz: {svc} EXCLUDED -- {why}")
        print(f"fuzzing {len(keep)} service(s); {len(skipped)} excluded")
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as fh:
            fh.write("services=" + json.dumps(names) + "\n")
        return 0

    if args.json:
        print(json.dumps(names))
        return 0

    for svc, why in skipped:
        print(f"api-fuzz: {svc} EXCLUDED -- {why}", file=sys.stderr)
    print(f"fuzzing {len(keep)} service(s); {len(skipped)} excluded", file=sys.stderr)
    for n in names:
        print(n)
    return 0


if __name__ == "__main__":
    sys.exit(main())
