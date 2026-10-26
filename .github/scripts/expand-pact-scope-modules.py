#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Expand the classifier's `modules` list into the Gradle modules whose consumer-pact tests run.

`openbank-admin-ui` is not a Gradle module: its JVM side is the set of modules that hold a
`@Pact(consumer = "openbank-admin-ui", ...)` test, derived by `derive-pact-drift-scope.sh
--admin-ui-selected-scope` (the same closure the `ui-only` scope runs). Every other entry is a
service module and passes through. Output is one module per line, sorted, unique.
"""

import argparse
import json
import pathlib
import re
import subprocess
import sys

ADMIN_UI = "openbank-admin-ui"
_MODULE = re.compile(r"openbank-[a-z0-9-]+")


def expand(modules: list[str], repo: pathlib.Path) -> list[str]:
    if any(not _MODULE.fullmatch(module) for module in modules):
        raise ValueError("invalid module name in selection")
    expanded = {module for module in modules if module != ADMIN_UI}
    if ADMIN_UI in modules:
        derive = repo / ".github/scripts/derive-pact-drift-scope.sh"
        result = subprocess.run(
            [str(derive), "--admin-ui-selected-scope"], cwd=repo, check=True,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
        )
        scope = json.loads(result.stdout)
        owners = {test[0] for test in scope["tests"]}
        if not owners or any(not _MODULE.fullmatch(owner) for owner in owners):
            raise ValueError("derived Admin UI JVM producer set is empty or malformed")
        expanded |= owners
    return sorted(expanded)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("modules", help="comma-separated module list from the classifier")
    parser.add_argument("--repo", type=pathlib.Path, default=pathlib.Path.cwd())
    args = parser.parse_args()
    modules = [module for module in args.modules.split(",") if module]
    if not modules:
        parser.error("empty module list")
    try:
        expanded = expand(modules, args.repo)
    except (ValueError, subprocess.CalledProcessError, json.JSONDecodeError, KeyError) as exc:
        parser.error(f"cannot expand module scope: {exc}")
    print("\n".join(expanded))
    return 0


if __name__ == "__main__":
    sys.exit(main())
