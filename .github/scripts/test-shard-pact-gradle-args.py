# SPDX-License-Identifier: Apache-2.0
"""Focused tests for Pact scope partitioning and fail-closed parsing."""

import importlib.util
import pathlib
import unittest

PATH = pathlib.Path(__file__).with_name("shard-pact-gradle-args.py")
SPEC = importlib.util.spec_from_file_location("shard_pact_gradle_args", PATH)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def group(name: str) -> list[str]:
    return [f":{name}:test", "--rerun", "--tests", "*.contract.*PactConsumerTest"]


class ShardPactGradleArgsTest(unittest.TestCase):
    def test_every_module_appears_exactly_once(self) -> None:
        original = [arg for index in range(13) for arg in group(f"openbank-{index}")]
        shards = [MODULE.shard_args(original, index, 4) for index in range(4)]
        selected = [shard[index] for shard in shards for index in range(0, len(shard), 4)]
        expected = [original[index] for index in range(0, len(original), 4)]
        self.assertCountEqual(selected, expected)
        self.assertEqual(len(selected), len(expected))

    def test_rejects_incomplete_or_unexpected_scope(self) -> None:
        for malformed in ([], group("x")[:-1], [*group("x"), *group("x")]):
            with self.subTest(malformed=malformed), self.assertRaises(ValueError):
                MODULE.shard_args(malformed, 0, 4)
        malformed = group("x")
        malformed[1] = "--rerun-tasks"
        with self.assertRaises(ValueError):
            MODULE.shard_args(malformed, 0, 4)

    def test_rejects_invalid_shard(self) -> None:
        with self.assertRaises(ValueError):
            MODULE.shard_args(group("x"), 4, 4)


if __name__ == "__main__":
    unittest.main()
