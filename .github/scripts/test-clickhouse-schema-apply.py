# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Real ClickHouse replay proof for the staged #7645 migration Job.

Run with Docker available: python3 .github/scripts/test-clickhouse-schema-apply.py
No ports are published and every disposable container is removed on exit.
"""

from __future__ import annotations

import argparse
import hashlib
import os
import shutil
import subprocess
import tempfile
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
IMAGE = "clickhouse/clickhouse-server:26.8-alpine"
JOB = ROOT / "openbank-infra/gitops/components/analytics/clickhouse-schema-apply-job.yaml"
CONFIGMAP = ROOT / "openbank-infra/gitops/components/analytics/clickhouse-init-configmap.yaml"
DEPLOY = ROOT / "openbank-infra/gitops/components/analytics/clickhouse.yaml"
RELEVANT = {
    ".github/scripts/test-clickhouse-schema-apply.py",
    "openbank-infra/gitops/components/analytics/clickhouse-schema-apply-job.yaml",
    "openbank-infra/gitops/components/analytics/clickhouse-init-configmap.yaml",
    "openbank-infra/gitops/components/analytics/clickhouse.yaml",
}
MIGRATION_PREFIX = "openbank-analytics-sink/src/main/resources/clickhouse/"


def relevant(paths: list[str]) -> bool:
    return any(path in RELEVANT or path.startswith(MIGRATION_PREFIX) for path in paths)


def self_test() -> None:
    assert relevant(["openbank-infra/gitops/components/analytics/clickhouse.yaml"])
    assert relevant(["openbank-analytics-sink/src/main/resources/clickhouse/V18__future.sql"])
    assert not relevant(["openbank-admin-ui/src/app/page.tsx"])
    print("ClickHouse migration path-scope self-test PASS")


def docker(*args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["docker", *args], capture_output=True, text=True, check=check, timeout=90,
    )


def query(container: str, sql: str) -> str:
    return docker("exec", container, "clickhouse-client", "--query", sql).stdout.strip()


def wait_ready(container: str) -> None:
    for _ in range(50):
        if docker("exec", container, "clickhouse-client", "--query", "SELECT 1", check=False).returncode == 0:
            # The image briefly uses a bootstrap server. Wait for the persistent server to own
            # the native port before driving migrations; Kubernetes readiness provides this gap.
            time.sleep(2)
            if docker("exec", container, "clickhouse-client", "--query", "SELECT 1", check=False).returncode == 0:
                return
        time.sleep(0.2)
    raise AssertionError(f"ClickHouse {container} did not become ready")


def apply(container: str, sql_dir: Path, enabled: bool = True) -> subprocess.CompletedProcess[str]:
    return docker(
        "run", "--rm", "--read-only", "--user", "101:101", "--network", f"container:{container}",
        "-e", "CLICKHOUSE_HOST=127.0.0.1", "-e", "CLICKHOUSE_USER=default",
        "-e", "CLICKHOUSE_PASSWORD=", "-e", f"CLICKHOUSE_SCHEMA_APPLY_ENABLED={str(enabled).lower()}",
        "-e", "POD_NAME=local-test", "-v", f"{sql_dir}:/sql:ro", "--entrypoint", "sh", IMAGE,
        "/sql/apply.sh", check=False,
    )


def start(name: str, sql_dir: Path, boot_dir: Path | None = None) -> None:
    args = ["run", "-d", "--name", name, "-e", "CLICKHOUSE_SKIP_USER_SETUP=1", "-v", f"{sql_dir}:/sql:ro"]
    if boot_dir is not None:
        args += ["-e", "CLICKHOUSE_DB=openbank_analytics", "-v", f"{boot_dir}:/docker-entrypoint-initdb.d:ro"]
    docker(*args, IMAGE)
    wait_ready(name)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--changed-only", metavar="BASE")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return
    if args.changed_only:
        changed = subprocess.run(
            ["git", "diff", "--name-only", f"{args.changed_only}...HEAD"],
            cwd=ROOT, capture_output=True, text=True, check=True,
        ).stdout.splitlines()
        if not relevant(changed):
            print("ClickHouse migration inputs unchanged; Docker replay skipped")
            return
    if shutil.which("docker") is None:
        raise SystemExit("Docker is required for the ClickHouse migration integration proof")
    job = yaml.safe_load(JOB.read_text())
    container = job["spec"]["template"]["spec"]["containers"][0]
    deployed = next(doc for doc in yaml.safe_load_all(DEPLOY.read_text()) if doc.get("kind") == "StatefulSet")
    server_image = deployed["spec"]["template"]["spec"]["containers"][0]["image"]
    assert server_image == container["image"] == f"docker.io/{IMAGE}", "server/client image versions differ"
    assert job["metadata"]["annotations"]["argocd.argoproj.io/hook"] == "Skip"
    assert next(e["value"] for e in container["env"] if e["name"] == "CLICKHOUSE_SCHEMA_APPLY_ENABLED") == "false"
    sql = yaml.safe_load(CONFIGMAP.read_text())["data"]
    assert len(sql) >= 17
    latest = max(int(name.split("-", 1)[0]) for name in sql)
    tmp_base = Path("/private/tmp") if Path("/private/tmp").is_dir() else None
    with tempfile.TemporaryDirectory(prefix="ob-ch-schema-", dir=tmp_base) as raw:
        base = Path(raw)
        full, boot = base / "full", base / "boot"
        full.mkdir()
        boot.mkdir()
        for name, body in sql.items():
            (full / name).write_text(body)
            if int(name.split("-", 1)[0]) <= 8:
                (boot / name).write_text(body)
        (full / "apply.sh").write_text(container["command"][2])
        fresh = f"ob-ch-schema-fresh-{os.getpid()}"
        legacy = f"ob-ch-schema-legacy-{os.getpid()}"
        try:
            start(fresh, full)
            disabled = apply(fresh, full, enabled=False)
            assert disabled.returncode == 0, disabled.stdout + disabled.stderr
            assert query(fresh, "SELECT count() FROM system.tables WHERE database='openbank_analytics'") == "0"
            first = apply(fresh, full)
            assert first.returncode == 0, first.stdout + first.stderr
            again = apply(fresh, full)
            assert again.returncode == 0 and f"V{latest} already applied" in again.stdout, again.stdout + again.stderr
            assert query(fresh, "SELECT count() FROM openbank_analytics.schema_migrations") == str(len(sql))
            assert query(fresh, "SELECT count() FROM system.tables WHERE database='openbank_analytics' AND name='schema_migration_lock'") == "0"

            # An old unledgered volume must reject before even creating the ledger or a lock.
            start(legacy, full, boot)
            assert int(query(legacy, "SELECT count() FROM system.tables WHERE database='openbank_analytics'")) > 0
            rejected = apply(legacy, full)
            assert rejected.returncode != 0 and "audit and baseline" in rejected.stderr
            assert query(legacy, "SELECT count() FROM system.tables WHERE database='openbank_analytics' AND name='schema_migrations'") == "0"

            # This fixture models an operator who has ALREADY audited and accepted V1–V8 on
            # this disposable volume. Inserting the matching checksums here tests the upgrade
            # path; it does not establish equivalence of any real warehouse to those versions.
            query(legacy, "INSERT INTO openbank_analytics.bronze_events "
                  "(event_id, aggregate_type, aggregate_id, aggregate_version, event_type, "
                  "occurred_at, source_service, schema_version, payload) VALUES "
                  "('550e8400-e29b-41d4-a716-446655440000', 'ACCOUNT', 'upgrade-proof', "
                  "1, 'Created', now64(3), 'test', 1, '{}')")
            query(legacy, "CREATE TABLE openbank_analytics.schema_migrations "
                  "(version UInt16, checksum FixedString(64), "
                  "applied_at DateTime64(3, 'UTC') DEFAULT now64(3)) "
                  "ENGINE=MergeTree ORDER BY version")
            baseline = ", ".join(
                f"({int(name.split('-', 1)[0])}, '{hashlib.sha256((full / name).read_bytes()).hexdigest()}')"
                for name in sorted(sql)
                if int(name.split("-", 1)[0]) <= 8
            )
            query(legacy, "INSERT INTO openbank_analytics.schema_migrations "
                  f"(version, checksum) VALUES {baseline}")
            upgraded = apply(legacy, full)
            assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
            assert "Applying V9" in upgraded.stdout
            assert query(legacy, "SELECT count() FROM openbank_analytics.schema_migrations") == str(len(sql))
            assert query(legacy, "SELECT count() FROM openbank_analytics.bronze_events "
                   "WHERE aggregate_id='upgrade-proof'") == "1"
            assert query(legacy, "SELECT count() FROM system.columns WHERE database='openbank_analytics' "
                   "AND table='bronze_events' AND name='synthetic'") == "1"

            # A changed applied V9 must stop before the next new version's DDL.
            v9 = full / "09-synthetic-provenance.sql"
            original = v9.read_text()
            proof = full / f"{latest + 1:02d}-preflight-proof.sql"
            try:
                v9.write_text(original + "\n-- changed historical body\n")
                proof.write_text(
                    "CREATE TABLE openbank_analytics.preflight_proof (id UInt8) ENGINE=Memory;\n"
                )
                drift = apply(fresh, full)
                assert drift.returncode != 0 and "differs" in drift.stderr
                assert query(fresh, "SELECT count() FROM system.tables WHERE database='openbank_analytics' AND name='preflight_proof'") == "0"
            finally:
                v9.write_text(original)
                proof.unlink()

            # Failed preflight retains the catalog lock. A replacement hook cannot race ahead.
            assert query(fresh, "SELECT count() FROM system.tables WHERE database='openbank_analytics' AND name='schema_migration_lock'") == "1"
            locked = apply(fresh, full)
            assert locked.returncode != 0, locked.stdout + locked.stderr
            docker("exec", fresh, "clickhouse-client", "--query", "DROP TABLE openbank_analytics.schema_migration_lock")
            recovered = apply(fresh, full)
            assert recovered.returncode == 0, recovered.stdout + recovered.stderr

            # A failed *partial* migration leaves its first idempotent DDL and the lock, but
            # no version receipt. After inspecting that state and clearing the stale lock, a
            # corrected forward script can replay the first statement and finish the version.
            partial = full / f"{latest + 1:02d}-partial-proof.sql"
            create_partial = (
                "CREATE TABLE IF NOT EXISTS openbank_analytics.partial_proof "
                "(id UInt8) ENGINE=Memory;\n"
            )
            try:
                partial.write_text(create_partial + "SELECT definitely_not_a_function();\n")
                interrupted = apply(fresh, full)
                assert interrupted.returncode != 0, interrupted.stdout + interrupted.stderr
                assert query(fresh, "SELECT count() FROM system.tables WHERE database='openbank_analytics' "
                       "AND name='partial_proof'") == "1"
                assert query(fresh, "SELECT count() FROM openbank_analytics.schema_migrations") == str(len(sql))
                assert query(fresh, "SELECT count() FROM system.tables WHERE database='openbank_analytics' "
                       "AND name='schema_migration_lock'") == "1"
                partial.write_text(
                    create_partial + "CREATE OR REPLACE VIEW openbank_analytics.partial_proof_view "
                    "AS SELECT 1 AS id;\n"
                )
                blocked = apply(fresh, full)
                assert blocked.returncode != 0, blocked.stdout + blocked.stderr
                # Manual reconciliation in this disposable test; production requires owner review.
                query(fresh, "DROP TABLE openbank_analytics.schema_migration_lock")
                finished = apply(fresh, full)
                assert finished.returncode == 0, finished.stdout + finished.stderr
                assert f"Applying V{latest + 1}" in finished.stdout
                assert query(fresh, "SELECT count() FROM system.tables WHERE database='openbank_analytics' "
                       "AND name='partial_proof_view'") == "1"
                assert query(fresh, "SELECT count() FROM openbank_analytics.schema_migrations") == str(len(sql) + 1)
                replay = apply(fresh, full)
                assert replay.returncode == 0 and f"V{latest + 1} already applied" in replay.stdout
            finally:
                partial.unlink(missing_ok=True)

            # ClickHouse's catalog CREATE is exclusive even when two writers start together.
            sql_lock = "CREATE TABLE openbank_analytics.schema_migration_lock (holder String) ENGINE=Memory"
            with ThreadPoolExecutor(max_workers=2) as pool:
                outcomes = list(pool.map(
                    lambda _: docker("exec", fresh, "clickhouse-client", "--query", sql_lock, check=False),
                    range(2),
                ))
            assert sorted(item.returncode == 0 for item in outcomes) == [False, True]
            docker("exec", fresh, "clickhouse-client", "--query", "DROP TABLE openbank_analytics.schema_migration_lock")
        finally:
            docker("rm", "-f", fresh, legacy, check=False)
    print("ClickHouse migration integration proof: disabled, fresh/replay, audited fixture upgrade, "
          "unledgered reject, drift, partial retry, lock recovery PASS")


if __name__ == "__main__":
    main()
