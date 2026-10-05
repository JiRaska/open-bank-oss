#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
"""Keep stock PostgreSQL Testcontainers on the version deployed by CNPG (#11317)."""

import argparse
from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[2]
SHARED = Path("openbank-libs-testing/src/main/kotlin/com/openbank/libs/testing/containers/PostgresBase.kt")
IMAGE = re.compile(r'"(?:docker\.io/library/)?postgres:(\d+(?:\.\d+)?)[^"\n]*"')
CNPG = re.compile(r"^\s*imageName:\s*\S*/postgresql:(\d+(?:\.\d+)?)[^\s]*\s*$", re.MULTILINE)
SHARED_IMAGE = re.compile(r'const val POSTGRES_IMAGE = "postgres:(\d+\.\d+)-alpine"')
CNPG_EXCEPTION = Path("openbank-infra/gitops/components/pact-broker/postgres.yaml")


def check(root: Path) -> tuple[list[str], int]:
    problems: list[str] = []
    shared = SHARED_IMAGE.search((root / SHARED).read_text())
    if shared is None:
        return [f"{SHARED}: cannot read the shared stock PostgreSQL image version"], 0
    expected = shared.group(1)

    clusters = 0
    for path in sorted((root / "openbank-infra/gitops/components").rglob("*.yaml")):
        relative = path.relative_to(root)
        if relative == CNPG_EXCEPTION:
            continue  # Pact Broker deliberately retains PostgreSQL 16.
        for version in CNPG.findall(path.read_text()):
            clusters += 1
            if version != expected:
                problems.append(f"{relative}: CNPG {version} differs from stock Testcontainers {expected}")
    if clusters == 0:
        problems.append("No production CNPG imageName found; the parity check would be vacuous")

    containers = 0
    for path in sorted(root.glob("openbank-*/src/test/**/*.kt")):
        source = path.read_text()
        if "PostgreSQLContainer" not in source:
            continue
        versions = IMAGE.findall(source)
        if versions or "PostgresBase.POSTGRES_IMAGE" in source:
            containers += 1
        for version in versions:
            if version != expected:
                problems.append(f"{path.relative_to(root)}: stock Testcontainers {version} differs from {expected}")
    if containers == 0:
        problems.append("No stock PostgreSQLContainer image found; the parity check would be vacuous")
    return problems, containers


def self_test() -> None:
    from tempfile import TemporaryDirectory

    with TemporaryDirectory() as directory:
        root = Path(directory)
        shared = root / SHARED
        shared.parent.mkdir(parents=True)
        shared.write_text('const val POSTGRES_IMAGE = "postgres:18.6-alpine"\n')
        prod = root / "openbank-infra/gitops/components/payments/postgres.yaml"
        prod.parent.mkdir(parents=True)
        prod.write_text("imageName: ghcr.io/cloudnative-pg/postgresql:18.6\n")
        test = root / "openbank-card-processing-service/src/test/kotlin/PostgresTestResource.kt"
        test.parent.mkdir(parents=True)
        test.write_text('PostgreSQLContainer("postgres:16.3-alpine")\n')
        assert any("Testcontainers 16.3" in issue for issue in check(root)[0])
        test.write_text('PostgreSQLContainer("postgres:18.6-alpine")\n')
        assert check(root) == ([], 1)
        test.write_text('PostgreSQLContainer("postgres:16-alpine")\n')
        assert any("Testcontainers 16" in issue for issue in check(root)[0])
        test.write_text('PostgreSQLContainer("postgres:18.6-alpine")\n')
        prod.write_text("imageName: ghcr.io/cloudnative-pg/postgresql:19.1\n")
        assert any("CNPG 19.1" in issue for issue in check(root)[0])
    print("Testcontainers PostgreSQL parity self-test passed")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return 0
    problems, containers = check(ROOT)
    if problems:
        for issue in problems:
            print(issue, file=sys.stderr)
        return 1
    print(f"SUBJECTS={containers}")
    print("Stock PostgreSQL Testcontainers and production CNPG major.minor versions match")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
