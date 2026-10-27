#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Exercise isolated Collector with six synthetic spans; requires Docker, Python 3.11+.

No application, cluster, Tempo or Prometheus access. Optional --output writes a new
summary JSON outside Git checkouts; raw synthetic evidence is temporary. Collector
logs are shown only on failure. Removes only the two container IDs created here.
"""
import argparse
from collections import Counter
import hashlib
import http.client
import json
import math
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import uuid


IMAGE = ("otel/opentelemetry-collector-contrib:0.161.0@sha256:"
         "fd328de2552466ad78385e1b1289c3f2402b1c45f265b252aab1955b42845ac1")
CLIENT = "synthetic-tail-client"
SERVER = "synthetic-tail-server"
TRACE_IDS = [f"{index:032x}" for index in (1, 2, 3)]
GUARDS = ("refused_spans", "dropped_spans", "dropped_too_early", "dropped_too_large",
          "sampling_policy_evaluation_error", "send_failed_spans", "enqueue_failed_spans")


class VerificationError(Exception):
    pass


def docker(*args, timeout=30):
    result = subprocess.run(["docker", *args], capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        raise VerificationError(f"Docker {args[0]} failed: {result.stderr[-3000:]}")
    return (result.stdout + (result.stderr if args[0] == "logs" else "")).strip()


def request(port, path, data=None):
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=2)
    try:
        body = None if data is None else json.dumps(data).encode()
        connection.request("GET" if data is None else "POST", path, body=body,
                           headers={} if data is None else {"Content-Type": "application/json"})
        response = connection.getresponse()
        payload = response.read(4 * 1024 * 1024 + 1)
        if response.status != 200 or len(payload) > 4 * 1024 * 1024:
            raise VerificationError(f"Local HTTP probe failed with status {response.status}")
        return payload.decode("utf-8")
    finally:
        connection.close()


def resource(service, spans):
    return {"resource": {"attributes": [{"key": "service.name", "value": {"stringValue": service}}]},
            "scopeSpans": [{"scope": {"name": "synthetic-tail-pilot"}, "spans": spans}]}


def fixtures():
    # OTLP JSON: IDs hex, enums integer, 64-bit timestamps decimal strings.
    # https://opentelemetry.io/docs/specs/otlp/#json-protobuf-encoding
    clients, servers = [], []
    start = time.time_ns() - 3_000_000_000
    for index, (trace_id, duration_ms) in enumerate(zip(TRACE_IDS, (50, 50, 1500))):
        client_id, server_id = f"{index * 2 + 1:016x}", f"{index * 2 + 2:016x}"
        clients.append({"traceId": trace_id, "spanId": client_id, "name": "GET /synthetic",
                        "kind": 3, "startTimeUnixNano": str(start),
                        "endTimeUnixNano": str(start + (duration_ms + 2) * 1_000_000),
                        "status": {"code": 1}})
        servers.append({"traceId": trace_id, "spanId": server_id, "parentSpanId": client_id,
                        "name": "GET /synthetic", "kind": 2,
                        "startTimeUnixNano": str(start + 1_000_000),
                        "endTimeUnixNano": str(start + (duration_ms + 1) * 1_000_000),
                        "status": {"code": 2 if index == 1 else 1}})
    # Healthy server arrives in the second batch; graph pairing must span batches.
    return ({"resourceSpans": [resource(CLIENT, clients), resource(SERVER, servers[1:])]},
            {"resourceSpans": [resource(SERVER, servers[:1])]})


def parse_metrics(text):
    rows = []
    pattern = re.compile(r'^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\{(.*)\})?\s+(\S+)(?:\s+\S+)?$')
    labels_pattern = re.compile(r'([a-zA-Z_][a-zA-Z0-9_]*)=("(?:[^"\\]|\\.)*")')
    for line in text.splitlines():
        if not line or line.startswith("#"):
            continue
        match = pattern.fullmatch(line)
        if not match:
            raise VerificationError("Unrecognized Prometheus sample")
        labels = {key: json.loads(value) for key, value in labels_pattern.findall(match[2] or "")}
        value = float(match[3])
        if not math.isfinite(value):
            raise VerificationError("Non-finite Prometheus sample")
        rows.append((match[1], labels, value))
    return rows


def total(rows, name, labels):
    matches = [value for metric, attributes, value in rows
               if metric == name and all(attributes.get(key) == value for key, value in labels.items())]
    return sum(matches) if matches else None


def metrics_evidence(text):
    rows = parse_metrics(text)
    server = {"service": SERVER, "span_kind": "SPAN_KIND_SERVER", "span_name": "GET /synthetic"}
    edge = {"client": CLIENT, "server": SERVER}
    actual = {
        "server_calls": total(rows, "traces_spanmetrics_calls_total", server),
        "server_errors": total(rows, "traces_spanmetrics_calls_total", {**server, "status_code": "STATUS_CODE_ERROR"}),
        "server_latency_le_1s": total(rows, "traces_spanmetrics_latency_bucket", {**server, "le": "1"}),
        "graph_requests": total(rows, "traces_service_graph_request_total", edge),
        "graph_failures": total(rows, "traces_service_graph_request_failed_total", edge),
    }
    expected = {"server_calls": 3, "server_errors": 1, "server_latency_le_1s": 2,
                "graph_requests": 3, "graph_failures": 1}
    if actual != expected:
        raise VerificationError(f"Complete-input metric mismatch: {actual}")
    return actual


def trace_evidence(path):
    spans = []
    if not path.exists():
        raise VerificationError("Sampled trace file not created yet")
    try:
        documents = [json.loads(line) for line in path.read_text().splitlines() if line.strip()]
    except json.JSONDecodeError as error:
        raise VerificationError("Sampled trace file is not complete JSON yet") from error
    for document in documents:
        for batch in document.get("resourceSpans", []):
            for scope in batch.get("scopeSpans", []):
                spans.extend(scope.get("spans", []))
    identities = [(span.get("traceId", "").lower(), span.get("spanId", "").lower()) for span in spans]
    expected = [(TRACE_IDS[index], f"{span_index:016x}") for index in (1, 2)
                for span_index in (index * 2 + 1, index * 2 + 2)]
    if Counter(identities) != Counter(expected):
        raise VerificationError(f"Expected only error/slow traces with four unique spans; found {len(spans)} spans")
    return {"exported_traces": 2, "exported_spans": 4, "healthy_trace_exported": False}


def pipeline_evidence(text):
    rows = parse_metrics(text)
    accepted = sum(value for name, labels, value in rows if "receiver_accepted_spans" in name)
    if accepted != 6:
        raise VerificationError(f"Expected six accepted spans, observed {accepted}")
    guards = [(name, value) for name, labels, value in rows if any(token in name for token in GUARDS)]
    if any(value != 0 for _, value in guards):
        raise VerificationError("Collector reports rejected, lost, or failed spans")
    return {"accepted_spans": accepted, "observed_zero_loss_metrics": sorted({name for name, _ in guards}),
            "loss_metrics_not_emitted_are_not_proven_zero": True}


def poll(check, timeout=45):
    deadline = time.monotonic() + timeout
    last_error = None
    while time.monotonic() < deadline:
        try:
            return check()
        except (VerificationError, OSError, http.client.HTTPException) as error:
            last_error = error
            time.sleep(0.2)
    raise VerificationError(f"Pilot deadline exceeded: {last_error}")


def verify():
    owned = []
    with tempfile.TemporaryDirectory(prefix="openbank-tail-pilot-") as temp:
        root = Path(temp)
        config = root / "collector.yaml"
        shutil.copyfile(Path(__file__).with_name("collector.yaml"), config)
        evidence = root / "evidence"
        evidence.mkdir()
        evidence.chmod(0o777)  # Container UID can write; parent temporary directory stays private 0700.
        config.chmod(0o644)
        name = "openbank-tail-pilot-" + uuid.uuid4().hex
        ownership = "openbank.synthetic-tail-pilot=" + name
        common = ["--memory", "256m", "--cpus", "1", "--read-only", "--cap-drop", "ALL",
                  "--label", ownership,
                  "--security-opt", "no-new-privileges",
                  "--mount", f"type=bind,src={config},dst=/etc/pilot.yaml,readonly",
                  "--mount", f"type=bind,src={evidence},dst=/evidence"]
        try:
            # Creation returns ownership before startup, so failed startup is also cleaned up.
            validation = docker("create", "--name", name + "-validate", "--network", "none",
                                *common, IMAGE, "validate", "--config=/etc/pilot.yaml", timeout=120)
            owned.append(validation)
            docker("start", "--attach", validation)
            if json.loads(docker("inspect", validation))[0]["State"]["ExitCode"] != 0:
                raise VerificationError("Collector rejected pilot configuration")
            container = docker("create", "--name", name, *common,
                               "--publish", "127.0.0.1::4318", "--publish", "127.0.0.1::8889",
                               "--publish", "127.0.0.1::8888", IMAGE, "--config=/etc/pilot.yaml")
            owned.append(container)
            docker("start", container)
            info = json.loads(docker("inspect", container))[0]
            ports = {port: int(info["NetworkSettings"]["Ports"][f"{port}/tcp"][0]["HostPort"])
                     for port in (4318, 8889, 8888)}
            poll(lambda: request(ports[8888], "/metrics"), timeout=20)
            first, second = fixtures()
            first_sent = time.monotonic()
            for batch in (first, second):
                response = json.loads(request(ports[4318], "/v1/traces", batch))
                # This collector serializes an empty partialSuccess object even
                # for full success; nonzero rejections or any warning fail closed.
                partial = response.get("partialSuccess", {})
                if int(partial.get("rejectedSpans", 0)) != 0 or partial.get("errorMessage"):
                    raise VerificationError("Collector returned OTLP partial success")
                if batch is first:
                    time.sleep(0.2)
            if time.monotonic() - first_sent >= 5:
                raise VerificationError("Second batch missed the sampling decision window")

            def checks():
                return {**metrics_evidence(request(ports[8889], "/metrics")),
                        **trace_evidence(evidence / "traces.json"),
                        **pipeline_evidence(request(ports[8888], "/metrics"))}

            result = poll(checks)
            # Allow another flush to catch accidental delayed duplicate/healthy export.
            time.sleep(1.2)
            result = checks()
            docker("stop", "--time", "5", container, timeout=15)
            result.update(trace_evidence(evidence / "traces.json"))
            result.update({"valid": True, "image": IMAGE, "baseline_probability_percent": 0,
                           "configuration_sha256": hashlib.sha256(config.read_bytes()).hexdigest()})
            return result
        except BaseException:
            if owned:
                try:
                    print(docker("logs", "--tail", "25", owned[-1]), file=sys.stderr)
                except (VerificationError, subprocess.SubprocessError):
                    pass
            raise
        finally:
            failures = []
            # A timed-out Docker create may have succeeded without returning its ID.
            # Discover only this invocation's unguessable ownership label, never a
            # broad name/image match that could include another caller's container.
            try:
                discovered = docker("ps", "--all", "--quiet", "--no-trunc", "--filter", "label=" + ownership)
                owned = list(dict.fromkeys([*owned, *discovered.split()]))
            except (VerificationError, subprocess.SubprocessError) as error:
                failures.append(str(error))
            for container in reversed(owned):
                try:
                    docker("rm", "--force", container)
                except (VerificationError, subprocess.SubprocessError) as error:
                    failures.append(str(error))
            if failures:
                raise VerificationError("Owned container cleanup failed: " + "; ".join(failures))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, help="New summary JSON outside all Git checkouts")
    args = parser.parse_args()
    output = args.output.expanduser().resolve() if args.output else None
    if output and (output.exists() or not output.parent.is_dir()
                   or any((parent / ".git").exists() for parent in output.parents)):
        parser.error("Output must be a new file outside Git checkouts, with an existing parent")

    def interrupted(_signal, _frame):
        raise VerificationError("Pilot interrupted")

    previous = signal.signal(signal.SIGTERM, interrupted)
    try:
        result = verify()
        if output:
            with output.open("x") as destination:
                json.dump(result, destination, indent=2)
                destination.write("\n")
        print("PASS: 3 input traces / 6 spans; metrics 3 calls, 1 error, 2 <=1s, 3 edges; "
              "sampled export 2 traces / 4 spans; "
              f"{len(result['observed_zero_loss_metrics'])} observed loss counters zero; owned containers removed.")
        return 0
    except (VerificationError, OSError, subprocess.SubprocessError, ValueError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1
    finally:
        signal.signal(signal.SIGTERM, previous)


if __name__ == "__main__":
    sys.exit(main())
