#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Discover PactJS consumer tests and the committed pacts they generate.

The JVM contract gate derives its scope from ``*PactConsumerTest.kt``. PactJS tests
need the same property: adding a test must add its output pact to the gate without a
second hand-maintained list. Each test deliberately owns one consumer/provider pair;
multiple interactions for that pair still belong in the same pact file.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys
import tempfile

TEST_GLOB = "openbank-admin-ui/src/test/**/*-pact.contract.test.ts"
CONSUMER = re.compile(r"\bconsumer\s*:\s*(['\"])([^'\"]+)\1")
PROVIDER = re.compile(r"\bprovider\s*:\s*(['\"])([^'\"]+)\1")


class ContractDiscoveryError(RuntimeError):
    pass


def _one_literal(pattern: re.Pattern[str], source: str, label: str, test: pathlib.Path) -> str:
    values = {match.group(2).strip() for match in pattern.finditer(source)}
    values.discard("")
    if len(values) != 1:
        rendered = ", ".join(sorted(values)) or "none"
        raise ContractDiscoveryError(
            f"{test}: expected exactly one literal PactJS {label}, found {rendered}"
        )
    return values.pop()


def discover(root: pathlib.Path) -> list[tuple[pathlib.Path, pathlib.Path]]:
    root = root.resolve()
    tests = sorted(root.glob(TEST_GLOB))
    if not tests:
        raise ContractDiscoveryError(
            f"no PactJS consumer tests matched {TEST_GLOB}; refusing an empty green scope"
        )

    contracts: list[tuple[pathlib.Path, pathlib.Path]] = []
    outputs: set[pathlib.Path] = set()
    for test in tests:
        source = test.read_text(encoding="utf-8")
        consumer = _one_literal(CONSUMER, source, "consumer", test)
        provider = _one_literal(PROVIDER, source, "provider", test)
        pact = root / "pacts" / f"{consumer}-{provider}.json"
        if pact in outputs:
            raise ContractDiscoveryError(
                f"{test}: {pact.relative_to(root)} is already owned by another PactJS test"
            )
        if not pact.is_file():
            raise ContractDiscoveryError(
                f"{test}: generated pact {pact.relative_to(root)} is not committed"
            )
        try:
            document = json.loads(pact.read_text(encoding="utf-8"))
        except json.JSONDecodeError as exc:
            raise ContractDiscoveryError(f"{pact.relative_to(root)}: invalid JSON: {exc}") from exc
        actual_consumer = ((document.get("consumer") or {}).get("name") or "").strip()
        actual_provider = ((document.get("provider") or {}).get("name") or "").strip()
        if (actual_consumer, actual_provider) != (consumer, provider):
            raise ContractDiscoveryError(
                f"{pact.relative_to(root)} names {actual_consumer!r} -> {actual_provider!r}, "
                f"but {test.relative_to(root)} declares {consumer!r} -> {provider!r}"
            )
        outputs.add(pact)
        contracts.append((test.relative_to(root), pact.relative_to(root)))
    return contracts


def self_test() -> int:
    with tempfile.TemporaryDirectory(prefix="pact-js-contracts-") as raw:
        root = pathlib.Path(raw)
        tests = root / "openbank-admin-ui" / "src" / "test"
        pacts = root / "pacts"
        tests.mkdir(parents=True)
        pacts.mkdir()
        test = tests / "sample-pact.contract.test.ts"
        pact = pacts / "openbank-ui-openbank-provider.json"
        test.write_text(
            "new PactV3({ consumer: 'openbank-ui', provider: 'openbank-provider' })\n",
            encoding="utf-8",
        )
        pact.write_text(
            json.dumps(
                {
                    "consumer": {"name": "openbank-ui"},
                    "provider": {"name": "openbank-provider"},
                    "interactions": [],
                }
            ),
            encoding="utf-8",
        )
        found = discover(root)
        assert found == [
            (
                pathlib.Path("openbank-admin-ui/src/test/sample-pact.contract.test.ts"),
                pathlib.Path("pacts/openbank-ui-openbank-provider.json"),
            )
        ]

        nested = tests / "contracts" / "nested-pact.contract.test.ts"
        nested.parent.mkdir()
        nested.write_text(
            "new PactV3({ consumer: 'nested-ui', provider: 'nested-provider' })\n",
            encoding="utf-8",
        )
        nested_pact = pacts / "nested-ui-nested-provider.json"
        nested_pact.write_text(
            json.dumps(
                {
                    "consumer": {"name": "nested-ui"},
                    "provider": {"name": "nested-provider"},
                }
            ),
            encoding="utf-8",
        )
        assert (
            pathlib.Path("openbank-admin-ui/src/test/contracts/nested-pact.contract.test.ts"),
            pathlib.Path("pacts/nested-ui-nested-provider.json"),
        ) in discover(root)

        duplicate = tests / "duplicate-pact.contract.test.ts"
        duplicate.write_text(test.read_text(encoding="utf-8"), encoding="utf-8")
        try:
            discover(root)
        except ContractDiscoveryError as exc:
            assert "already owned by another PactJS test" in str(exc)
        else:
            raise AssertionError("duplicate PactJS pact ownership unexpectedly passed")
        duplicate.unlink()

        pact.unlink()
        try:
            discover(root)
        except ContractDiscoveryError as exc:
            assert "is not committed" in str(exc)
        else:
            raise AssertionError("missing committed pact unexpectedly passed")

        pact.write_text(
            json.dumps(
                {
                    "consumer": {"name": "wrong-consumer"},
                    "provider": {"name": "openbank-provider"},
                }
            ),
            encoding="utf-8",
        )
        try:
            discover(root)
        except ContractDiscoveryError as exc:
            assert "but" in str(exc) and "declares" in str(exc)
        else:
            raise AssertionError("pact identity mismatch unexpectedly passed")

    print(
        "selftest OK: PactJS tests derive recursive, uniquely owned, "
        "identity-matched committed pact outputs"
    )
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default=".")
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--tests", action="store_true")
    mode.add_argument("--pacts", action="store_true")
    mode.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    try:
        contracts = discover(pathlib.Path(args.root))
    except (ContractDiscoveryError, OSError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    column = 0 if args.tests else 1
    for contract in contracts:
        print(contract[column].as_posix())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
