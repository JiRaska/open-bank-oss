#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Compare existing fast-jar and trained aot-jar artifacts; never train or build.

Requires POSIX and JDK 25. Use the exact JDK/OS that trained app.aot. Reports omit
environment values, JVM argument values, URLs and application logs. HTTP latency
measures response headers (not body transfer). Each run has a fresh JVM; filesystem
caches are not flushed. Pass JVM arguments as --jvm-arg=-Xmx512m.
"""
import argparse
import hashlib
import ipaddress
import json
import math
import os
from pathlib import Path
import platform
import re
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request


VARIANTS = ("fast", "aot-cache-off", "aot-cache-on")


class BenchmarkError(Exception):
    """Safe diagnostic with no child output or configuration values."""


def endpoint(url):
    parsed = urllib.parse.urlsplit(url)
    try:
        address = ipaddress.ip_address(parsed.hostname or "")
        port = parsed.port or (443 if parsed.scheme == "https" else 80)
    except ValueError as error:
        raise BenchmarkError("URLs require literal loopback IP addresses") from error
    if (parsed.scheme not in ("http", "https") or not address.is_loopback
            or parsed.username is not None or parsed.password is not None or parsed.fragment):
        raise BenchmarkError("Only loopback HTTP(S) URLs without credentials or fragments are allowed")
    return str(address), port


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def status(url, timeout):
    # Never use ambient proxies or follow redirects outside the loopback endpoint.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    # A socket timeout alone restarts after each byte: a slow trickle of headers
    # could otherwise overrun the startup deadline indefinitely. CLI is single-threaded.
    def expired(_signal, _frame):
        raise TimeoutError("HTTP response deadline exceeded")

    previous = signal.signal(signal.SIGALRM, expired)
    signal.setitimer(signal.ITIMER_REAL, timeout)
    try:
        with opener.open(url, timeout=timeout) as response:
            return response.status
    except urllib.error.HTTPError as error:
        error.close()
        return error.code
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0)
        signal.signal(signal.SIGALRM, previous)


def ports_free(urls):
    for host, port in {endpoint(url) for url in urls}:
        family = socket.AF_INET6 if ":" in host else socket.AF_INET
        with socket.socket(family, socket.SOCK_STREAM) as probe:
            probe.settimeout(0.3)
            if probe.connect_ex((host, port)) == 0:
                raise BenchmarkError("Benchmark port already has a listener")


def ordering(rounds):
    # Rotate first position to avoid always giving one packaging the coldest cache.
    return [VARIANTS[(round_no + position) % len(VARIANTS)]
            for round_no in range(rounds) for position in range(len(VARIANTS))]


def command(java, flags, variant):
    if variant not in VARIANTS:
        raise BenchmarkError("Unknown benchmark variant")
    if any(not flag.startswith("-") or flag.startswith("@")
           or any(token in flag.lower() for token in ("aot", "xshare", "sharedarchive", "xlog"))
           or flag in ("-jar", "-cp", "-classpath", "--class-path", "-m", "--module")
           for flag in flags):
        raise BenchmarkError("JVM arguments must not override launch, AOT, CDS or logging options")
    specific = {
        "fast": [],
        "aot-cache-off": ["-XX:AOTMode=off"],
        "aot-cache-on": ["-XX:AOTMode=on", "-XX:AOTCache=app.aot", "-Xlog:aot"],
    }
    return [java, *flags, *specific[variant], "-jar", "quarkus-run.jar"]


def stop(process, grace):
    # start_new_session makes this child's PID its own process group. Never select
    # other processes by command name, listening port, or shared application label.
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    try:
        process.wait(timeout=grace)
    except subprocess.TimeoutExpired:
        pass
    finally:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        process.wait(timeout=grace)


def cache_loaded(log):
    log.seek(0)
    opened = mapped = False
    for raw in log:
        line = raw.decode("utf-8", errors="replace")
        if not re.search(r"\[aot(?:,|\])", line):
            continue
        if re.search(r"\[(?:warning|error)\]|failed|unable|not found|ignor|fallback|disabled", line, re.I):
            return False
        opened |= bool(re.search(r"Opened .*app\.aot(?:\.|\s|$)", line))
        mapped |= bool(re.search(r"Mapped (?:static|dynamic)\s+region #", line))
    return opened and mapped


def rss_kib(pid):
    try:
        result = subprocess.run(["ps", "-o", "rss=", "-p", str(pid)],
                                capture_output=True, text=True, timeout=2, check=True)
        return int(result.stdout.strip())
    except (OSError, ValueError, subprocess.SubprocessError):
        return None


def run_once(argv, directory, env, ready_url, request_url, timeout, request_timeout, grace):
    ports_free((ready_url, request_url))
    with tempfile.TemporaryFile() as log:
        started = time.monotonic()
        process = subprocess.Popen(argv, cwd=directory, env=env, stdout=log,
                                   stderr=subprocess.STDOUT, start_new_session=True)
        try:
            deadline = started + timeout
            while True:
                if process.poll() is not None:
                    raise BenchmarkError("Application exited before readiness")
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise BenchmarkError("Readiness timed out")
                try:
                    ready = status(ready_url, min(request_timeout, remaining))
                except (OSError, urllib.error.URLError):
                    ready = None
                if ready == 200:
                    break
                time.sleep(min(0.05, max(0, deadline - time.monotonic())))
            ready_seconds = time.monotonic() - started
            if ready_seconds > timeout:
                raise BenchmarkError("Readiness timed out")
            request_start = time.monotonic()
            response = status(request_url, request_timeout)
            latency = time.monotonic() - request_start
            if response != 200 or process.poll() is not None:
                raise BenchmarkError("First request failed or application exited")
            loaded = cache_loaded(log) if "-XX:AOTCache=app.aot" in argv else None
            if loaded is False:
                raise BenchmarkError("AOT cache loading was not proven or reported an error")
            return {"ready_seconds": ready_seconds, "first_response_headers_seconds": latency,
                    "readiness_status": ready, "response_status": response,
                    "aot_cache_loaded": loaded, "rss_kib": rss_kib(process.pid)}
        finally:
            stop(process, grace)


def sha256(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def manifest(directory):
    return {str(path.relative_to(directory)): sha256(path)
            for path in sorted(directory.rglob("*")) if path.is_file()}


def benchmark(args):
    if os.name != "posix" or args.runs < 3:
        raise BenchmarkError("POSIX and at least three runs per variant are required")
    if any(not math.isfinite(value) or value <= 0
           for value in (args.timeout, args.request_timeout, args.stop_timeout)):
        raise BenchmarkError("Timeouts must be positive")
    endpoint(args.ready_url)
    endpoint(args.first_request_url)
    output = Path(args.output).expanduser().resolve()
    repository = Path(__file__).resolve().parents[2]
    if output.is_relative_to(repository) or any((p / ".git").exists() for p in output.parents):
        raise BenchmarkError("Report output must be outside every Git checkout")
    if output.exists() or not output.parent.is_dir():
        raise BenchmarkError("Output must be a new file in an existing directory")
    directories = {"fast": Path(args.fast_jar).resolve(), "aot": Path(args.aot_jar).resolve()}
    if directories["fast"] == directories["aot"]:
        raise BenchmarkError("Packaging directories must be distinct")
    for directory in directories.values():
        if not (directory / "quarkus-run.jar").is_file():
            raise BenchmarkError("Both packaging directories must contain quarkus-run.jar")
    if not (directories["aot"] / "app.aot").is_file():
        raise BenchmarkError("AOT directory requires an existing trained app.aot")
    java = shutil.which(args.java)
    if java is None:
        raise BenchmarkError("Java executable not found")
    java = str(Path(java).resolve())
    env = os.environ.copy()
    if any(env.get(name) for name in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")):
        raise BenchmarkError("Unset ambient JVM option variables; pass explicit --jvm-arg options")
    commands = {variant: command(java, args.jvm_arg, variant) for variant in VARIANTS}
    version = subprocess.run([java, "-XX:AOTMode=off", "-version"], env=env,
                             capture_output=True, text=True, timeout=10, check=False)
    version_text = version.stderr + version.stdout
    if version.returncode or not re.search(r'version "25(?:\.|\")', version_text):
        raise BenchmarkError("This benchmark requires JDK 25 with AOTMode=off support")
    artifacts = {name: manifest(directory) for name, directory in directories.items()}
    results = []
    for variant in ordering(args.runs):
        directory = directories["fast" if variant == "fast" else "aot"]
        result = run_once(commands[variant], directory, env, args.ready_url,
                          args.first_request_url, args.timeout, args.request_timeout, args.stop_timeout)
        results.append({"variant": variant, **result})
    if artifacts != {name: manifest(directory) for name, directory in directories.items()}:
        raise BenchmarkError("Artifacts changed during the benchmark")
    report = {"valid": True, "java_version": version_text.strip(),
              "java_executable_sha256": sha256(Path(java)),
              "platform": {"system": platform.system(), "release": platform.release(),
                           "machine": platform.machine()},
              "rss_source": "ps -o rss=; KiB; sampled after first response headers",
              "jvm_arguments_sha256": hashlib.sha256(json.dumps(args.jvm_arg).encode()).hexdigest(),
              "artifacts": artifacts, "aot_cache_sha256": artifacts["aot"]["app.aot"],
              "runs_per_variant": args.runs, "results": results}
    with output.open("x", encoding="utf-8") as destination:
        json.dump(report, destination, indent=2)
        destination.write("\n")
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for option in ("fast-jar", "aot-jar", "java", "ready-url", "first-request-url", "output"):
        parser.add_argument("--" + option, required=True)
    parser.add_argument("--runs", type=int, default=5)
    parser.add_argument("--timeout", type=float, default=90)
    parser.add_argument("--request-timeout", type=float, default=5)
    parser.add_argument("--stop-timeout", type=float, default=10)
    parser.add_argument("--jvm-arg", action="append", default=[])
    def interrupted(_signal, _frame):
        raise BenchmarkError("Benchmark interrupted; no valid report produced")

    previous = signal.signal(signal.SIGTERM, interrupted)
    try:
        benchmark(parser.parse_args())
    except BenchmarkError as error:
        print(str(error), file=sys.stderr)
        return 1
    except (OSError, subprocess.SubprocessError, urllib.error.URLError):
        print("Benchmark failed; no valid report produced", file=sys.stderr)
        return 1
    finally:
        signal.signal(signal.SIGTERM, previous)
    return 0


if __name__ == "__main__":
    sys.exit(main())
