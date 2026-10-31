#!/usr/bin/env python3
"""Private, bounded Unix-socket IPC for one Admin UI collector stage."""

# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors.

from __future__ import annotations

import json
import os
import signal
import socket
import socketserver
import stat
import tempfile
import threading
import time
import uuid
from collections import Counter
from pathlib import Path

STAGES = {
    "sbom": {"curl"},
    "service_ti": {"curl", "python_urllib"},
    "customer_ti": {"curl"},
    "immutable_ti": {"curl"},
    "pit": {"curl"},
    "performance": {"curl"},
    "gate_health": {"node_fetch"},
    "deploy_ti": {"curl"},
    "quality_report": {"node_fetch"},
    "infra_lifecycle": {"node_fetch"},
}
AUTH = {
    "sbom": "github",
    "service_ti": "github",
    "customer_ti": "app_ro",
    "immutable_ti": "github",
    "pit": "github",
    "performance": "github",
    "gate_health": "github",
    "deploy_ti": "github",
    "quality_report": "pact_broker",
    "infra_lifecycle": "none",
}
AUTH_ALLOWED = {stage: {alias} for stage, alias in AUTH.items()}
AUTH_ALLOWED["quality_report"] = {"pact_broker", "none"}
DESTINATIONS = {"api", "non_api", "unknown"}
MAX_FRAME = 4096
MAX_TOTAL_BYTES = 1_048_576
MAX_INVOCATIONS = 4096
EOF_GRACE_SECONDS = max(
    0.1, min(5.0, float(os.environ.get("ADMIN_UI_TRACE_EOF_GRACE_SECONDS", "3")))
)
SERVER_LIFETIME_SECONDS = 30 * 60


def paths(stage: str) -> tuple[Path, Path, Path]:
    root_value = os.environ.get("ADMIN_UI_TRACE_DIR", "")
    if not root_value:
        raise OSError("trace directory is unavailable")
    root = Path(root_value)
    return root / f"{stage}.sock", root / f"{stage}.pid", root / f"admin-ui-stage-{stage}.json"


def send_signal_taint(_stage: str) -> None:
    try:
        pid = int(os.environ["ADMIN_UI_TRACE_PID"])
        if pid <= 1 or pid == os.getpid():
            return
        os.kill(pid, signal.SIGUSR1)
    except Exception:
        pass


class Invocation:
    def __init__(self, stage: str, client: str, auth: str) -> None:
        self.stage = stage
        self.client = client
        self.auth = auth
        self.id = uuid.uuid4().hex
        self.sock: socket.socket | None = None
        self.started = False
        try:
            sock_path, _pid, _report = paths(stage)
            self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            self.sock.settimeout(2.0)
            self.sock.connect(str(sock_path))
            self._send({"type": "begin", "id": self.id, "stage": stage, "client": client, "authAlias": auth})
            self.started = self._read_ack()
        except Exception:
            self.close()
            send_signal_taint(stage)

    def _send(self, value: dict) -> None:
        assert self.sock is not None
        data = json.dumps(value, separators=(",", ":")).encode("utf-8") + b"\n"
        if len(data) > MAX_FRAME:
            raise ValueError("trace frame too large")
        self.sock.sendall(data)

    def _read_ack(self) -> bool:
        assert self.sock is not None
        data = bytearray()
        while len(data) <= 256:
            chunk = self.sock.recv(1)
            if not chunk:
                return False
            if chunk == b"\n":
                return json.loads(data.decode("utf-8")).get("ok") is True
            data.extend(chunk)
        return False

    def end(self, counts: Counter[str], *, complete: bool, reason: str | None) -> None:
        if not self.started or self.sock is None:
            send_signal_taint(self.stage)
            self.close()
            return
        try:
            self._send({
                "type": "end",
                "id": self.id,
                "counts": dict(counts),
                "complete": complete,
                "reasonEnum": reason,
            })
            if not self._read_ack():
                send_signal_taint(self.stage)
        except Exception:
            send_signal_taint(self.stage)
        finally:
            self.close()

    def close(self) -> None:
        if self.sock is not None:
            try:
                self.sock.close()
            except OSError:
                pass
            self.sock = None


class _StageState:
    def __init__(self, stage: str, auth: str) -> None:
        self.stage = stage
        self.auth = auth
        self.lock = threading.Lock()
        self.pending: dict[str, str] = {}
        self.clients: set[str] = set()
        self.counts: Counter[tuple[str, str]] = Counter()
        self.incomplete = False
        self.reason: str | None = None
        self.total_bytes = 0
        self.invocations = 0
        self.finish_requested = threading.Event()
        self.signal_tainted = False

    def taint(self, reason: str) -> None:
        with self.lock:
            self.incomplete = True
            self.reason = self.reason or reason

    def begin(self, event: dict) -> bool:
        identifier = event.get("id")
        client = event.get("client")
        if (
            event.get("stage") != self.stage
            or event.get("authAlias") != self.auth
            or not isinstance(identifier, str)
            or not identifier.isascii()
            or len(identifier) != 32
            or not isinstance(client, str)
            or client not in STAGES[self.stage]
        ):
            self.taint("malformed_lifecycle")
            return False
        with self.lock:
            if self.finish_requested.is_set() or identifier in self.pending or self.invocations >= MAX_INVOCATIONS:
                self.incomplete = True
                self.reason = self.reason or "invalid_lifecycle"
                return False
            self.pending[identifier] = client
            self.clients.add(client)
            self.invocations += 1
            return True

    def end(self, event: dict) -> bool:
        identifier = event.get("id")
        counts = event.get("counts")
        complete = event.get("complete")
        reason = event.get("reasonEnum")
        if (
            not isinstance(identifier, str)
            or not isinstance(counts, dict)
            or type(complete) is not bool
            or (reason is not None and reason not in {
                "trace_parse_failure", "no_send_observed", "pre_send_failure",
                "client_start_failure", "hook_unavailable", "trace_write_failure",
                "collector_not_configured", "collector_failed",
            })
        ):
            self.taint("malformed_lifecycle")
            return False
        normalized: Counter[str] = Counter()
        for destination, count in counts.items():
            if destination not in DESTINATIONS or type(count) is not int or count < 0:
                self.taint("malformed_lifecycle")
                return False
            normalized[destination] += count
        with self.lock:
            client = self.pending.pop(identifier, None)
            if client is None:
                self.incomplete = True
                self.reason = self.reason or "unmatched_lifecycle"
                return False
            for destination, count in normalized.items():
                self.counts[(client, destination)] += count
            if not complete:
                self.incomplete = True
                self.reason = self.reason or reason or "trace_parse_failure"
            return True

    def snapshot(self, *, eof: bool) -> dict:
        with self.lock:
            incomplete = self.incomplete or self.signal_tainted or bool(self.pending) or not eof
            reason = self.reason
            if self.signal_tainted:
                reason = reason or "ipc_failure"
            elif self.pending:
                reason = reason or "missing_terminal_frame"
            elif not eof:
                reason = reason or "bounded_eof_timeout"
            records = [
                {"client": client, "destinationClass": destination, "numericSendCount": count}
                for (client, destination), count in sorted(self.counts.items())
            ]
            for _client in sorted(STAGES[self.stage] - self.clients):
                incomplete = True
                reason = reason or "missing_client"
            if not records:
                incomplete = True
                reason = reason or "no_send_observed"
            return {
                "schemaVersion": 1,
                "stage": self.stage,
                "authAlias": self.auth,
                "complete": not incomplete,
                "reasonEnum": reason,
                "records": records,
            }


class _Handler(socketserver.StreamRequestHandler):
    def _read_frame(self) -> dict | None:
        raw = self.rfile.readline(MAX_FRAME + 1)
        if not raw:
            return None
        if len(raw) > MAX_FRAME or not raw.endswith(b"\n"):
            self.server.state.taint("malformed_lifecycle")
            return None
        with self.server.state.lock:
            self.server.state.total_bytes += len(raw)
            if self.server.state.total_bytes > MAX_TOTAL_BYTES:
                self.server.state.incomplete = True
                self.server.state.reason = self.server.state.reason or "trace_limit_exceeded"
                return None
        try:
            event = json.loads(raw.decode("utf-8"))
            return event if isinstance(event, dict) else None
        except Exception:
            self.server.state.taint("malformed_lifecycle")
            return None

    def handle(self) -> None:
        state: _StageState = self.server.state
        first = self._read_frame()
        if not first:
            self.wfile.write(b'{"ok":false}\n')
            return
        if first.get("type") == "finish":
            ok = first.get("stage") == state.stage and first.get("authAlias") == state.auth
            if not ok:
                state.taint("malformed_lifecycle")
            state.finish_requested.set()
            self.wfile.write(b'{"ok":true}\n')
            return
        if first.get("type") != "begin" or not state.begin(first):
            self.wfile.write(b'{"ok":false}\n')
            return
        identifier = first["id"]
        self.wfile.write(b'{"ok":true}\n')
        second = self._read_frame()
        if not second or second.get("type") != "end" or second.get("id") != identifier:
            state.taint("missing_terminal_frame")
            with state.lock:
                state.pending.pop(identifier, None)
            return
        if state.end(second):
            self.wfile.write(b'{"ok":true}\n')
        else:
            self.wfile.write(b'{"ok":false}\n')


class _Server(socketserver.ThreadingMixIn, socketserver.UnixStreamServer):
    daemon_threads = True
    allow_reuse_address = False

    def __init__(self, address: str, state: _StageState) -> None:
        self.state = state
        super().__init__(address, _Handler)


def atomic_report(path: Path, payload: dict) -> None:
    temp_path = None
    try:
        fd, temp_path = tempfile.mkstemp(prefix=".admin-ui-stage-", suffix=".tmp", dir=path.parent)
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            json.dump(payload, stream, separators=(",", ":"))
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temp_path, path)
        temp_path = None
    finally:
        if temp_path:
            try:
                os.unlink(temp_path)
            except OSError:
                pass


def serve(stage: str, auth: str | None = None) -> int:
    sock_path, pid_path, report_path = paths(stage)
    auth = auth or AUTH[stage]
    if auth not in AUTH_ALLOWED[stage]:
        return 1
    sock_path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    try:
        report_path.unlink(missing_ok=True)
        if sock_path.exists():
            if not stat.S_ISSOCK(sock_path.lstat().st_mode):
                return 1
            sock_path.unlink()
    except OSError:
        return 1
    state = _StageState(stage, auth)

    def taint(_signum: int, _frame: object) -> None:
        state.signal_tainted = True

    signal.signal(signal.SIGUSR1, taint)
    try:
        server = _Server(str(sock_path), state)
        os.chmod(sock_path, 0o600)
        pid_path.write_text(f"{os.getpid()}\n", encoding="ascii")
        os.chmod(pid_path, 0o600)
    except OSError:
        return 1

    server.timeout = 0.2
    started = time.monotonic()
    try:
        while not state.finish_requested.is_set():
            if time.monotonic() - started > SERVER_LIFETIME_SECONDS:
                return 1
            server.handle_request()
        deadline = time.monotonic() + EOF_GRACE_SECONDS
        while time.monotonic() < deadline:
            with state.lock:
                if not state.pending:
                    break
            time.sleep(0.02)
        with state.lock:
            eof = not state.pending
        payload = state.snapshot(eof=eof)
        try:
            atomic_report(report_path, payload)
        except Exception:
            report_path.unlink(missing_ok=True)
            return 1
        return 0 if payload["complete"] else 2
    finally:
        server.server_close()
        for path in (sock_path, pid_path):
            try:
                path.unlink(missing_ok=True)
            except OSError:
                pass


def shell_finish(stage: str, auth: str, supervisor_pid: int | None = None) -> str:
    pid_export = f"export ADMIN_UI_TRACE_PID={supervisor_pid}\n" if supervisor_pid else ""
    return pid_export + (
        "trace_admin_ui_finish() {\n"
        "  _trace_original_status=$?\n"
        "  trap - EXIT\n"
        f"  python3 ${{GITHUB_WORKSPACE:-.}}/.github/scripts/trace-admin-ui-stage.py finish --stage {stage} --auth {auth} >/dev/null 2>&1 || true\n"
        "  return \"$_trace_original_status\"\n"
        "}\n"
        "trap trace_admin_ui_finish EXIT\n"
    )


def start(stage: str, auth: str) -> str:
    sock_path, _pid_path, report_path = paths(stage)
    sock_path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    report_path.unlink(missing_ok=True)
    process = None
    try:
        import subprocess

        process = subprocess.Popen(
            [os.environ.get("PYTHON", "python3"), __file__, "serve", "--stage", stage, "--auth", auth],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            close_fds=True,
            start_new_session=True,
        )
    except Exception:
        return shell_finish(stage, auth)
    deadline = time.monotonic() + 2.0
    while time.monotonic() < deadline:
        if sock_path.exists() and _pid_path.exists():
            break
        time.sleep(0.02)
    return shell_finish(stage, auth, process.pid)


def finish(stage: str, auth: str) -> int:
    try:
        sock_path, _pid_path, report_path = paths(stage)
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as client:
            client.settimeout(4.0)
            client.connect(str(sock_path))
            frame = json.dumps({"type": "finish", "stage": stage, "authAlias": auth}, separators=(",", ":")).encode() + b"\n"
            client.sendall(frame)
            response = client.makefile("rb").readline(257)
            if json.loads(response.decode("utf-8")).get("ok") is not True:
                return 1
        deadline = time.monotonic() + EOF_GRACE_SECONDS + 1.0
        while time.monotonic() < deadline:
            if report_path.is_file():
                report = json.loads(report_path.read_text(encoding="utf-8"))
                result = 0 if report.get("complete") is True else 2
                cleanup_deadline = time.monotonic() + 1.0
                while time.monotonic() < cleanup_deadline and _pid_path.exists():
                    time.sleep(0.01)
                return result
            time.sleep(0.01)
        return 1
    except Exception:
        return 1


def main() -> int:
    import argparse

    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=("begin", "finish", "serve"))
    parser.add_argument("--stage", required=True, choices=sorted(STAGES))
    parser.add_argument("--auth", choices=sorted({a for aliases in AUTH_ALLOWED.values() for a in aliases}))
    args = parser.parse_args()
    auth = args.auth or AUTH[args.stage]
    if auth not in AUTH_ALLOWED[args.stage]:
        parser.error("auth alias is not allowed for stage")
    if args.mode == "begin":
        print(start(args.stage, auth), end="")
        return 0
    if args.mode == "serve":
        return serve(args.stage, auth)
    return finish(args.stage, auth)


if __name__ == "__main__":
    raise SystemExit(main())
