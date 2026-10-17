# SPDX-License-Identifier: Apache-2.0
"""Canonical required asset names for release production and fleet inventory."""

REQUIRED_ASSET_SUFFIXES = (
    ".cdx.json",
    ".cdx.json.sig",
    ".cdx.json.intoto.jsonl",
    ".slsa.json",
    ".slsa.json.sig",
    ".vex.json",
    ".vex.json.sig",
    ".evidence.json",
    ".evidence.json.sig",
)


def expected_release_assets(tag: str) -> set[str]:
    return {f"{tag}{suffix}" for suffix in REQUIRED_ASSET_SUFFIXES}


def missing_release_assets(tag: str, actual: set[str]) -> list[str]:
    return sorted(expected_release_assets(tag) - actual)

