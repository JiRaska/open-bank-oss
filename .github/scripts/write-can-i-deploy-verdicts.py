#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Write the selected services' can-i-deploy verdicts without copying broker output.

This is the producer for #9898's subject watch. It deliberately exports only
allowlisted fields: raw Pact CLI output may contain credentials or private URLs.
The eventual watch must treat a missing artifact or a null ``blocked_on`` as
unknown, never as a successful deployment or a licence to close an issue.
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path


SERVICE = re.compile(r"^openbank-[a-z0-9-]+$")
SHA = re.compile(r"^[a-f0-9]{40}$")
BLOCK_CLASSES = {
    "PENDING_BUILD", "UNVERIFIED", "PROVIDER_UNVERIFIED", "UNVERIFIABLE",
    "REGRESSION", "NOT_ASKED", "NO_CONTRACTS", "UNKNOWN",
}


def produce(services: list[str], deployable: list[str], classes: dict[str, str],
            money_path: set[str], head_sha: str, run_id: int) -> dict:
    if not SHA.fullmatch(head_sha) or run_id <= 0:
        raise ValueError("invalid head SHA or run ID")
    if len(services) != len(set(services)) or not all(SERVICE.fullmatch(s) for s in services):
        raise ValueError("invalid or duplicate selected service")
    if not set(deployable) <= set(services) or not set(classes) <= set(services):
        raise ValueError("verdict references a service outside the selected set")
    if set(deployable) & set(classes):
        raise ValueError("service marked both deployable and blocked")
    if set(services) != set(deployable) | set(classes):
        raise ValueError("missing per-service verdict")
    if not set(classes.values()) <= BLOCK_CLASSES:
        raise ValueError("unrecognized block class")
    return {
        "schema_version": 1,
        "head_sha": head_sha,
        "run_id": run_id,
        "services": [
            {
                "service": service,
                "class": "DEPLOYABLE" if service in deployable else classes[service],
                "blocked_on": None,
                "money_path": service in money_path,
                "head_sha": head_sha,
                "run_id": run_id,
            }
            for service in services
        ],
    }


def self_test() -> None:
    sha = "a" * 40
    got = produce(["openbank-account-service", "openbank-card-service"],
                  ["openbank-card-service"], {"openbank-account-service": "REGRESSION"},
                  {"openbank-account-service"}, sha, 42)
    assert got["services"][0]["money_path"] is True
    assert got["services"][0]["class"] == "REGRESSION"
    assert got["services"][1]["class"] == "DEPLOYABLE"
    for selected, passed, blocked in [
        (["openbank-account-service"], [], {}),
        (["openbank-account-service"], ["openbank-account-service"],
         {"openbank-account-service": "REGRESSION"}),
        (["openbank-account-service"], [], {"openbank-account-service": "secret"}),
    ]:
        try:
            produce(selected, passed, blocked, set(), sha, 42)
        except ValueError:
            pass
        else:
            raise AssertionError("unsafe verdict accepted")
    print("write-can-i-deploy-verdicts: self-test PASS")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--services")
    parser.add_argument("--deployable")
    parser.add_argument("--classes-file", type=Path)
    parser.add_argument("--money-path-file", type=Path)
    parser.add_argument("--head-sha")
    parser.add_argument("--run-id", type=int)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return
    classes = {}
    for line in args.classes_file.read_text().splitlines():
        service, cls = line.split("\t")
        if service in classes:
            raise ValueError("duplicate class for service")
        classes[service] = cls
    result = produce(json.loads(args.services), json.loads(args.deployable), classes,
                     set(args.money_path_file.read_text().splitlines()),
                     args.head_sha, args.run_id)
    args.output.write_text(json.dumps(result, sort_keys=True, separators=(",", ":")) + "\n")


if __name__ == "__main__":
    main()
