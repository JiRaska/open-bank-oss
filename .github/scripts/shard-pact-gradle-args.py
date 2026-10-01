# SPDX-License-Identifier: Apache-2.0
"""Partition the complete derived Pact Gradle scope without maintaining module lists."""

import argparse
import pathlib
import sys


def shard_args(lines: list[str], shard: int, count: int) -> list[str]:
    if count < 1 or not 0 <= shard < count:
        raise ValueError("shard must be in [0, count)")
    if len(lines) % 4:
        raise ValueError("derived Gradle arguments are not complete four-line groups")
    groups = [lines[index : index + 4] for index in range(0, len(lines), 4)]
    if not groups:
        raise ValueError("derived Gradle scope is empty")
    modules: set[str] = set()
    for group in groups:
        module, rerun, tests, pattern = group
        if not module.startswith(":") or not module.endswith(":test"):
            raise ValueError(f"unexpected Gradle task: {module}")
        if (rerun, tests, pattern) != (
            "--rerun",
            "--tests",
            "*.contract.*PactConsumerTest",
        ):
            raise ValueError(f"unexpected Gradle test arguments: {group}")
        if module in modules:
            raise ValueError(f"duplicate Pact consumer module: {module}")
        modules.add(module)
    return [arg for index, group in enumerate(groups) if index % count == shard for arg in group]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("args_file", type=pathlib.Path)
    parser.add_argument("shard", type=int)
    parser.add_argument("count", type=int)
    args = parser.parse_args()
    try:
        selected = shard_args(args.args_file.read_text().splitlines(), args.shard, args.count)
    except ValueError as exc:
        parser.error(str(exc))
    print("\n".join(selected))
    return 0


if __name__ == "__main__":
    sys.exit(main())
