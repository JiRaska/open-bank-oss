#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Local synthetic replay benchmark. Uses separate files for banking and reporting."""
import argparse
import hashlib
import json
from pathlib import Path
import random
import sqlite3
import subprocess
import sys
import tempfile
import time


CONTEXTS = [
    dict(contextId="bank-a-domestic", legalEntityId="bank-a", mode="DOMESTIC",
         homeCountry="CZ", hostCountry="CZ", establishmentId=None, perimeterVersionId="solo-v1"),
    dict(contextId="bank-b-fos", legalEntityId="bank-b", mode="FOS",
         homeCountry="AT", hostCountry="CZ", establishmentId=None, perimeterVersionId="fos-v1"),
    dict(contextId="bank-b-branch", legalEntityId="bank-b", mode="BRANCH",
         homeCountry="AT", hostCountry="CZ", establishmentId="branch-b-cz", perimeterVersionId="branch-v1"),
]


def seed(root, clients):
    with sqlite3.connect(root / "bank.sqlite") as bank:
        bank.execute("CREATE TABLE balances (id INTEGER PRIMARY KEY, cents INTEGER NOT NULL)")
        bank.executemany("INSERT INTO balances VALUES (?, 100)", ((i,) for i in range(1, clients + 1)))
    return write_synthetic_export(root, clients)


def write_synthetic_export(root, clients):
    controls = [dict(scope=scope, closeEvidenceId="synthetic-close", records=0, debitCents=0, creditCents=0)
                for scope in CONTEXTS]
    patterns = [
        (("1510", "ASSET", 100), ("2100", "LIABILITY", -100)),
        (("1510", "ASSET", 200), ("2100", "LIABILITY", -200)),
        (("1510", "ASSET", 300), ("2100", "LIABILITY", -300)),
    ]
    digest = hashlib.sha256()
    with (root / "journal.ndjson").open("wb") as journal:
        for i in range(clients):
            case = i % len(CONTEXTS)
            record = controls[case]
            for offset, (code, account_type, amount) in enumerate(patterns[case], 1):
                line = (f'{{"sequence":{i * 2 + offset},"contextId":"{record["scope"]["contextId"]}",'
                        f'"code":"{code}","accountType":"{account_type}","currency":"CZK","netCents":{amount}}}\n').encode()
                journal.write(line)
                digest.update(line)
                record["records"] += 1
                record["debitCents"] += max(amount, 0)
                record["creditCents"] += max(-amount, 0)
    manifest = dict(version=1, asOf="2026-09-30", contexts=controls, journalSha256=digest.hexdigest())
    raw = json.dumps(manifest).encode()
    (root / "manifest.json").write_bytes(raw)
    return hashlib.sha256(raw).hexdigest()


def traffic(root, clients, rate, operations=None, worker=None):
    rng = random.Random(16356)
    samples = []
    with sqlite3.connect(root / "bank.sqlite") as db:
        db.execute("PRAGMA journal_mode=WAL")
        start = time.perf_counter()
        operation = 0
        while (operation < operations if operations is not None else worker.poll() is None):
            scheduled = start + operation / rate
            time.sleep(max(0, scheduled - time.perf_counter()))
            client = rng.randint(1, clients)
            if operation % 10 == 0:
                db.execute("UPDATE balances SET cents = cents + 1 WHERE id = ?", (client,))
                db.commit()
            else:
                assert db.execute("SELECT cents FROM balances WHERE id = ?", (client,)).fetchone()
            samples.append((time.perf_counter() - scheduled) * 1000)
            operation += 1
    if not samples:
        raise RuntimeError("No client requests were sampled")
    ordered = sorted(samples)
    return dict(operations=len(ordered), **{
        f"p{p}_ms": round(ordered[(len(ordered) * p + 99) // 100 - 1], 3) for p in (50, 95, 99)
    })


def measure_export(root, clients, rate):
    with tempfile.TemporaryDirectory(dir=root) as output:
        command = [
            sys.executable, "-c",
            "from benchmark_scoped_reporting import write_synthetic_export; "
            "from pathlib import Path; import sys; write_synthetic_export(Path(sys.argv[1]), int(sys.argv[2]))",
            str(Path(output).resolve()), str(clients),
        ]
        started = time.perf_counter()
        with subprocess.Popen(command, cwd=Path(__file__).resolve().parent) as worker:
            try:
                samples = traffic(root, clients, rate, worker=worker)
                if worker.wait() != 0:
                    raise RuntimeError("Synthetic source export failed")
            finally:
                if worker.poll() is None:
                    worker.terminate()
        return dict(samples, elapsed_seconds=round(time.perf_counter() - started, 3))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--clients", type=int, default=3_000_000)
    parser.add_argument("--rate", type=int, default=500)
    parser.add_argument("--seconds", type=int, default=12)
    parser.add_argument("--reuse-input", action="store_true")
    parser.add_argument("--measure-export-only", action="store_true")
    parser.add_argument("--replay-rate", type=int, default=50_000)
    args = parser.parse_args()
    if min(args.clients, args.rate, args.seconds, args.replay_rate) < 1:
        parser.error("Clients, rate and duration must be positive")
    root = args.directory
    if args.measure_export_only:
        if not args.reuse_input:
            parser.error("Export measurement requires an existing bank fixture (--reuse-input)")
        print(json.dumps(measure_export(root, args.clients, args.rate), indent=2))
        return
    if args.reuse_input:
        raw = (root / "manifest.json").read_bytes()
        records = sum(item["records"] for item in json.loads(raw)["contexts"])
        if records != args.clients * 2:
            parser.error("--clients must match the existing synthetic input")
        approved = hashlib.sha256(raw).hexdigest()
    else:
        root.mkdir(mode=0o700)
        approved = seed(root, args.clients)
    command = [sys.executable, str(Path(__file__).with_name("scoped_trial_balance.py")),
               str(root / "manifest.json"), approved, str(root / "journal.ndjson"), "--max-records-per-second", str(args.replay_rate),
               str(root / f"interrupted-{args.replay_rate}")]
    with subprocess.Popen(command) as worker:
        time.sleep(0.25)
        if worker.poll() is not None:
            raise RuntimeError("Worker finished before the simulated outage")
        worker.terminate()
    if Path(command[-1]).exists():
        raise RuntimeError("Interrupted worker published a result")
    outage = traffic(root, args.clients, args.rate, operations=args.rate * args.seconds)
    started = time.perf_counter()
    command[-1] = str(root / f"full-replay-{args.replay_rate}")
    with subprocess.Popen(command) as worker:
        try:
            during = traffic(root, args.clients, args.rate, worker=worker)
            if worker.wait() != 0:
                raise RuntimeError("Reporting catch-up failed")
        finally:
            if worker.poll() is None:
                worker.terminate()
    replay_elapsed = time.perf_counter() - started
    baseline = traffic(root, args.clients, args.rate, operations=during["operations"])
    print(json.dumps(dict(clients=args.clients, journal_records=args.clients * 2,
                          target_operations_per_second=args.rate, outage_seconds=args.seconds,
                          replay_records_per_second_cap=args.replay_rate,
                          baseline=baseline, reporting_down=outage, catch_up=during,
                          replay_and_sample_seconds=round(replay_elapsed, 3),
                          reporting_output=str(root / f"full-replay-{args.replay_rate}")), indent=2))


if __name__ == "__main__":
    main()
