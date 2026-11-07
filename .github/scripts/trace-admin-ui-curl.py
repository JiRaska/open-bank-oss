#!/usr/bin/env python3
"""Count outgoing curl request-header sends without retaining verbose data."""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import threading
from pathlib import Path
from collections import Counter
from urllib.parse import urlsplit

sys.path.insert(0, str(Path(__file__).resolve().parent))
from admin_ui_trace_ipc import Invocation

MAX_LINE = 16 * 1024
STAGES = {
    "sbom", "service_ti", "customer_ti", "immutable_ti", "pit", "performance", "deploy_ti"
}
ALIASES = {"github", "app_ro"}


def destination(authority: str | None) -> str:
    if not authority:
        return "unknown"
    try:
        host = urlsplit("//" + authority).hostname
    except ValueError:
        return "unknown"
    if not host:
        return "unknown"
    return "api" if host.lower() == "api.github.com" else "non_api"


class VerboseParser:
    """Parse curl's verbose request-header lines, discarding every raw line."""

    def __init__(self) -> None:
        self.counts: Counter[str] = Counter()
        self.request_method: str | None = None
        self.authority: str | None = None
        self.request_terminated = False
        self.saw_request = False
        self.incomplete = False

    def _finish(self) -> None:
        if self.request_method is None:
            return
        if not self.request_terminated:
            self.incomplete = True
        self.saw_request = True
        if self.request_method != "CONNECT":
            kind = destination(self.authority)
            self.counts[kind] += 1
            if kind == "unknown":
                self.incomplete = True
        self.request_method = None
        self.authority = None
        self.request_terminated = False

    def feed(self, raw: bytes) -> None:
        if len(raw) >= MAX_LINE and not raw.endswith(b"\n"):
            self.incomplete = True
            return
        # Decode only to inspect protocol metadata; never retain or emit raw lines.
        line = raw.decode("latin-1", errors="replace").rstrip("\r\n")
        if line in {">", "> "}:
            self.request_terminated = True
            self._finish()
            return
        if not line.startswith("> "):
            # HTTP/2/3 implementations that do not expose the request header lines
            # are not silently counted as zero; they make the trace incomplete.
            if line.startswith("=> Send header"):
                self.incomplete = True
            return
        value = line[2:]
        match = re.match(r"^([A-Z]+)\s+\S+\s+HTTP/\S+$", value)
        if match:
            self._finish()
            self.request_method = match.group(1)
            self.request_terminated = False
            return
        if self.request_method is None:
            return
        name, sep, content = value.partition(":")
        if sep and name.strip().lower() in {"host", ":authority"}:
            self.authority = content.strip()

    def finish(self) -> None:
        self._finish()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--stage", required=True, choices=sorted(STAGES))
    parser.add_argument("--auth", required=True, choices=sorted(ALIASES))
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    if not command:
        parser.error("a curl command is required")

    invocation = Invocation(args.stage, "curl", args.auth)
    parser_state = VerboseParser()
    trace_error = threading.Event()
    try:
        proc = subprocess.Popen([*command, "--verbose"], stderr=subprocess.PIPE)
    except FileNotFoundError:
        invocation.end(Counter(), complete=False, reason="client_start_failure")
        return 127
    except OSError:
        invocation.end(Counter(), complete=False, reason="client_start_failure")
        return 126

    def consume() -> None:
        assert proc.stderr is not None
        try:
            while True:
                line = proc.stderr.readline(MAX_LINE + 1)
                if not line:
                    break
                if len(line) > MAX_LINE and not line.endswith(b"\n"):
                    trace_error.set()
                    while line and not line.endswith(b"\n"):
                        line = proc.stderr.readline(MAX_LINE + 1)
                    continue
                try:
                    parser_state.feed(line)
                except Exception:
                    # Continue draining stderr after a parser fault so curl never
                    # blocks on a full pipe or observes an instrumentation SIGPIPE.
                    trace_error.set()
            parser_state.finish()
        except Exception:
            trace_error.set()
        finally:
            try:
                proc.stderr.close()
            except Exception:
                trace_error.set()

    reader = threading.Thread(target=consume, name="curl-trace-reader", daemon=True)
    reader.start()
    try:
        return_code = proc.wait()
    except KeyboardInterrupt:
        proc.terminate()
        return_code = proc.wait()
        trace_error.set()
    reader.join()
    complete = (
        not trace_error.is_set()
        and not parser_state.incomplete
        and sum(parser_state.counts.values()) > 0
    )
    reason = None if complete else (
        "trace_parse_failure"
        if trace_error.is_set() or parser_state.incomplete
        else "pre_send_failure" if return_code != 0 else "no_send_observed"
    )
    invocation.end(parser_state.counts, complete=complete, reason=reason)
    if return_code != 0:
        print("WARN: traced curl command failed; request details withheld.", file=sys.stderr)
    return 128 - return_code if return_code < 0 else return_code


if __name__ == "__main__":
    raise SystemExit(main())
