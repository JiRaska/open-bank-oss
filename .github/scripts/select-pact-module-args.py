#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Select the Gradle argument groups of the `modules` Pact scope from the complete derived scope.

Input is `derive-pact-drift-scope.sh`'s output (four-line groups, validated here exactly as
`shard-pact-gradle-args.py` does) and the classifier's comma-separated module list. Output is the
subset of groups whose module is listed; a listed module with no consumer-pact test contributes
nothing, and an output that selects NO group is a legitimate "nothing to regenerate" result, which
the workflow handles by archiving an empty pact set rather than running Gradle.
"""

import argparse
import pathlib
import re
import sys

_MODULE = re.compile(r"openbank-[a-z0-9-]+")


def select_args(lines: list[str], modules: list[str]) -> list[str]:
    if any(not _MODULE.fullmatch(module) for module in modules):
        raise ValueError("invalid module name in selection")
    if len(lines) % 4:
        raise ValueError("derived Gradle arguments are not complete four-line groups")
    groups = [lines[index : index + 4] for index in range(0, len(lines), 4)]
    if not groups:
        raise ValueError("derived Gradle scope is empty")
    seen: set[str] = set()
    # derive-pact-drift-scope.sh emits the dedicated :consumerPactTest task (it disables
    # up-to-date/cache reuse so direct invocations regenerate contracts — #2291, #2309);
    # shard-pact-gradle-args.py validates the same shape.
    wanted = {f":{module}:consumerPactTest" for module in modules}
    selected: list[str] = []
    for group in groups:
        task, rerun, tests, pattern = group
        if not task.startswith(":") or not task.endswith(":consumerPactTest"):
            raise ValueError(f"unexpected Gradle task: {task}")
        if (rerun, tests, pattern) != ("--rerun", "--tests", "*.contract.*PactConsumerTest"):
            raise ValueError(f"unexpected Gradle test arguments: {group}")
        if task in seen:
            raise ValueError(f"duplicate Pact consumer module: {task}")
        seen.add(task)
        if task in wanted:
            selected.extend(group)
    return selected


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("args_file", type=pathlib.Path)
    parser.add_argument("modules", help="comma-separated module directory names")
    args = parser.parse_args()
    modules = [module for module in args.modules.split(",") if module]
    try:
        selected = select_args(args.args_file.read_text().splitlines(), modules)
    except ValueError as exc:
        parser.error(str(exc))
    if selected:
        print("\n".join(selected))
    return 0


if __name__ == "__main__":
    sys.exit(main())
