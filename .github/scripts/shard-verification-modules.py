#!/usr/bin/env python3
"""Group the verification-metadata module set into a few balanced CI shards.

Input: the output of derive-verification-modules.py on stdin; only its
`verification-modules-json=` line is read, so this composes with however that
script decides the module set. Output: `verification-shards-json=[...]` for the
`verification-metadata` matrix in services-ci.yml.

Why: a fleet-wide change produced 83 one-module jobs at max-parallel 4, each
paying checkout + Java + Gradle setup. The checker already runs one Gradle
invocation per module (bounded heap), so several modules per job is safe.

Invariants (held by --self-test, which must fail if any is broken):
  * the union of all shards is exactly the input set, with no duplicates;
  * an empty input yields no shards (the job's `if:` then skips it);
  * a single module yields a single shard labelled with that module, so a
    one-module PR keeps its familiar `verification-metadata (<module>)` name.
"""

import json
import math
import sys

MAX_SHARDS = 12
MODULES_PER_SHARD = 7
PREFIX = "verification-modules-json="


def shards(modules: list[str]) -> list[dict[str, str]]:
    ordered = sorted(set(modules))
    if len(ordered) != len(modules):
        raise ValueError(f"duplicate modules in input: {modules}")
    if not ordered:
        return []
    count = min(MAX_SHARDS, math.ceil(len(ordered) / MODULES_PER_SHARD))
    # Strided, so sizes differ by at most one and alphabetical neighbours
    # (e.g. the libs-* modules) spread across shards.
    groups = [ordered[i::count] for i in range(count)]
    return [{"label": group[0] if len(group) == 1 else f"shard {i}/{count}",
             "modules": ",".join(group)}
            for i, group in enumerate(groups, start=1)]


def parse(text: str) -> list[str]:
    lines = [line for line in text.splitlines() if line.startswith(PREFIX)]
    if len(lines) != 1:
        raise SystemExit(f"expected exactly one {PREFIX} line, got {len(lines)}")
    modules = json.loads(lines[0][len(PREFIX):])
    if not isinstance(modules, list) or not all(isinstance(m, str) and m for m in modules):
        raise SystemExit(f"{PREFIX} is not a list of module names")
    return modules


def flatten(result: list[dict[str, str]]) -> list[str]:
    return [m for shard in result for m in shard["modules"].split(",")]


def self_test() -> None:
    failures = 0

    def check(name: str, cond: bool) -> None:
        nonlocal failures
        print(f"  {'ok' if cond else 'FAIL'}: {name}")
        failures += not cond

    def covers(result: list[dict[str, str]], modules: list[str]) -> bool:
        flat = flatten(result)
        return len(flat) == len(set(flat)) and set(flat) == set(modules)

    fleet = [f"openbank-m{i:02d}" for i in range(83)]
    result = shards(fleet)
    check("83 modules -> 12 shards", len(result) == 12)
    check("83 modules: union == input, no duplicates", covers(result, fleet))
    sizes = [len(s["modules"].split(",")) for s in result]
    check("shards balanced (sizes differ by <= 1)", max(sizes) - min(sizes) <= 1)
    check("no shard exceeds MODULES_PER_SHARD", max(sizes) <= MODULES_PER_SHARD)
    check("empty input -> no shards", shards([]) == [])
    check("one module -> one shard labelled by module",
          shards(["openbank-x"]) == [{"label": "openbank-x", "modules": "openbank-x"}])
    for n in range(1, 120):
        mods = [f"openbank-n{i:03d}" for i in range(n)]
        if not covers(shards(mods), mods):
            check(f"coverage for n={n}", False)
    # Negative cases: the coverage predicate must reject a dropped or duplicated module.
    dropped = [dict(s) for s in result]
    dropped[0]["modules"] = ",".join(dropped[0]["modules"].split(",")[1:])
    check("a dropped module is detected", not covers(dropped, fleet))
    duplicated = [dict(s) for s in result]
    duplicated[1]["modules"] += "," + fleet[0]
    check("a duplicated module is detected", not covers(duplicated, fleet))
    try:
        shards(["openbank-a", "openbank-a"])
        check("duplicate input rejected", False)
    except ValueError:
        check("duplicate input rejected", True)
    check("parses derive output",
          parse('verification-modules=a,b\nverification-modules-json=["a","b"]') == ["a", "b"])
    check("parses empty derive output",
          parse("verification-modules=\nverification-modules-json=[]") == [])
    if failures:
        raise SystemExit(f"shard-verification-modules: self-test FAILED ({failures})")
    print("shard-verification-modules: self-test OK")


def main() -> None:
    if sys.argv[1:] == ["--self-test"]:
        self_test()
        return
    if len(sys.argv) != 1:
        raise SystemExit("usage: shard-verification-modules.py [--self-test] < derive-output")
    modules = parse(sys.stdin.read())
    result = shards(modules)
    if sorted(flatten(result)) != sorted(modules):
        raise SystemExit("shard union does not equal the module set")
    print("verification-shards-json=" + json.dumps(result, separators=(",", ":")))


if __name__ == "__main__":
    main()
