#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Summarize a k6 JSON stream against independently logged UTC phase windows."""
import argparse
from collections import defaultdict
from datetime import datetime
import json
import math


ENDPOINTS = ("account", "balance", "payment")
PHASES = ("baseline", "export", "outage", "catch_up")


def instant(value):
    timestamp = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if timestamp.utcoffset() is None:
        raise ValueError("Timestamps require an explicit timezone")
    return timestamp


def summarize(stream, marks):
    windows = [(phase, instant(marks[phase][0]), instant(marks[phase][1])) for phase in PHASES]
    if any(start >= end for _, start, end in windows) or any(
        left[2] > right[1] for left, right in zip(windows, windows[1:])
    ):
        raise ValueError("Phase windows must be ordered, positive and disjoint")
    samples = defaultdict(list)
    for line in stream:
        point = json.loads(line)
        metric = point.get("metric")
        if point["type"] != "Point" or metric not in {
            "http_req_duration", "http_req_failed", "dropped_iterations",
        }:
            continue
        data = point["data"]
        scenario = data["tags"].get("scenario")
        if scenario not in ENDPOINTS:
            continue
        when = instant(data["time"])
        # k6 timestamps HTTP metrics when the request ends. Attribute in-flight
        # requests crossing a phase boundary by their completion, consistently.
        for phase, start, end in windows:
            if start <= when < end:
                samples[phase, scenario, metric].append(data["value"])
                break

    result = {}
    for phase, _, _ in windows:
        result[phase] = {}
        for endpoint in ENDPOINTS:
            latency = sorted(samples[phase, endpoint, "http_req_duration"])
            errors = samples[phase, endpoint, "http_req_failed"]
            if not latency or len(latency) != len(errors):
                raise ValueError(f"Missing or incomplete samples in {phase}/{endpoint}")
            dropped = sum(samples[phase, endpoint, "dropped_iterations"])
            result[phase][endpoint] = {
                "requests": len(latency),
                "errors": int(sum(errors)),
                "error_rate": sum(errors) / len(errors),
                "dropped_iterations": int(dropped),
                "p50_ms": latency[math.ceil(len(latency) * 0.50) - 1],
                "p95_ms": latency[math.ceil(len(latency) * 0.95) - 1],
                "p99_ms": latency[math.ceil(len(latency) * 0.99) - 1],
            }
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("k6_json", type=argparse.FileType())
    parser.add_argument("phase_windows", type=argparse.FileType())
    args = parser.parse_args()
    print(json.dumps(summarize(args.k6_json, json.load(args.phase_windows)), indent=2))


if __name__ == "__main__":
    main()
