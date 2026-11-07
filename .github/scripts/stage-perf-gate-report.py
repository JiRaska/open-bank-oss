#!/usr/bin/env python3
"""Stage the perf report contract without unbounded diagnostic logs."""
import argparse
import importlib.util
import os
from pathlib import Path
import re
import stat
import tempfile

MAX_JSON_BYTES = 4 * 1024 * 1024
MAX_LOG_BYTES = 1024 * 1024
HERE = Path(__file__).resolve().parent


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, HERE / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def regular(path):
    if not stat.S_ISREG(path.lstat().st_mode):
        raise ValueError("perf report must be a regular file")


def read_json(path):
    regular(path)
    with path.open("rb") as stream:
        raw = stream.read(MAX_JSON_BYTES + 1)
    if len(raw) > MAX_JSON_BYTES:
        raise ValueError("perf JSON exceeds existing consumer limit")
    return raw


def diagnostic_tail(path):
    regular(path)
    with path.open("rb") as stream:
        size = stream.seek(0, os.SEEK_END)
        if size <= MAX_LOG_BYTES:
            stream.seek(0)
            return stream.read(size)
        header = f"[truncated diagnostic: source_bytes={size}; retained tail follows]\n".encode()
        keep = MAX_LOG_BYTES - len(header)
        stream.seek(size - keep)
        return header + stream.read(keep)


def stage(service, reports, destination):
    if not re.fullmatch(r"openbank-[a-z0-9]+(?:-[a-z0-9]+)*", service):
        raise ValueError("invalid perf service")
    reports, destination = Path(reports), Path(destination)
    if reports.is_symlink() or not reports.is_dir() or destination.exists() or destination.is_symlink():
        raise ValueError("perf report input must exist and output must be fresh")
    collector = load("perf_report_envelope", "collect-test-run-evidence.py")
    consumer = load("perf_report_validation", "stage-admin-ui-performance.py")
    run_name, summary_name = service + "-run.json", service + "-summary.json"
    payloads = {run_name: read_json(reports / run_name)}
    envelope = consumer._json(payloads[run_name])
    collector.validate_envelope(envelope)
    observations = [row for row in envelope.get("specializedEvidence", []) if row.get("kind") == "performance"]
    if envelope.get("component") != service or len(observations) != 1:
        raise ValueError("perf envelope component or observation mismatch")
    summary = reports / summary_name
    if summary.exists() or summary.is_symlink():
        payloads[summary_name] = read_json(summary)
        consumer._validate_summary(consumer._json(payloads[summary_name]), observations[0])
    elif observations[0].get("state") != "not-run":
        raise ValueError("measured perf evidence requires its summary")
    for suffix in ("-boot.log", "-k6.log"):
        path = reports / (service + suffix)
        if path.exists() or path.is_symlink():
            payloads[path.name] = diagnostic_tail(path)
    with tempfile.TemporaryDirectory(prefix="perf-report-", dir=destination.parent) as temporary:
        ready = Path(temporary) / "ready"
        ready.mkdir()
        for name, raw in payloads.items():
            (ready / name).write_bytes(raw)
        ready.rename(destination)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--service", required=True)
    parser.add_argument("--reports", default="perf-reports")
    parser.add_argument("--out", default="perf-upload")
    args = parser.parse_args()
    stage(args.service, args.reports, args.out)


if __name__ == "__main__":
    main()
