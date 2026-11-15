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


def traffic(root, clients, rate, seconds):
    rng = random.Random(16356)
    samples = []
    with sqlite3.connect(root / "bank.sqlite") as db:
        db.execute("PRAGMA journal_mode=WAL")
        start = time.perf_counter()
        for operation in range(rate * seconds):
            scheduled = start + operation / rate
            time.sleep(max(0, scheduled - time.perf_counter()))
            client = rng.randint(1, clients)
            if operation % 10 == 0:
                db.execute("UPDATE balances SET cents = cents + 1 WHERE id = ?", (client,))
                db.commit()
            else:
                assert db.execute("SELECT cents FROM balances WHERE id = ?", (client,)).fetchone()
            samples.append((time.perf_counter() - scheduled) * 1000)
    ordered = sorted(samples)
    return {f"p{p}_ms": round(ordered[(len(ordered) * p + 99) // 100 - 1], 3) for p in (50, 95, 99)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--clients", type=int, default=3_000_000)
    parser.add_argument("--rate", type=int, default=500)
    parser.add_argument("--seconds", type=int, default=12)
    parser.add_argument("--reuse-input", action="store_true")
    parser.add_argument("--replay-rate", type=int, default=50_000)
    args = parser.parse_args()
    if min(args.clients, args.rate, args.seconds, args.replay_rate) < 1:
        parser.error("Clients, rate and duration must be positive")
    root = args.directory
    if args.reuse_input:
        raw = (root / "manifest.json").read_bytes()
        records = sum(item["records"] for item in json.loads(raw)["contexts"])
        if records != args.clients * 2:
            parser.error("--clients must match the existing synthetic input")
        approved = hashlib.sha256(raw).hexdigest()
    else:
        root.mkdir(mode=0o700)
        approved = seed(root, args.clients)
    baseline = traffic(root, args.clients, args.rate, args.seconds)
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
    started = time.perf_counter()
    command[-1] = str(root / f"replayed-{args.replay_rate}")
    with subprocess.Popen(command) as worker:
        try:
            during = traffic(root, args.clients, args.rate, args.seconds)
            overlap = worker.poll() is None
            if worker.wait() != 0:
                raise RuntimeError("Reporting catch-up failed")
        finally:
            if worker.poll() is None:
                worker.terminate()
    print(json.dumps(dict(clients=args.clients, journal_records=args.clients * 2,
                          target_operations_per_second=args.rate, sample_seconds=args.seconds,
                          replay_records_per_second_cap=args.replay_rate,
                          baseline=baseline, catch_up=during, full_sample_overlap=overlap,
                          replay_and_sample_seconds=round(time.perf_counter() - started, 3),
                          reporting_output=str(root / f"replayed-{args.replay_rate}")), indent=2))


if __name__ == "__main__":
    main()
