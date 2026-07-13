"""openbank-document-renderer — standalone WeasyPrint HTTP sidecar.

ADR-0162 D3: this is the *default lightweight renderer* leg of the
`PdfRenderPort` abstraction (see README.md for the full HTTP contract,
including the different call shape a later Gotenberg opt-in adapter needs).

Deliberately minimal dependencies: Python stdlib `http.server` + `weasyprint`
only. No Flask/FastAPI/uvicorn — this is a single-purpose sidecar that spends
its whole life parsing attacker-influenced HTML, not a general web service;
every extra dependency is more surface area in that position.
"""

from __future__ import annotations

import json
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from weasyprint import HTML, default_url_fetcher

HOST = "0.0.0.0"  # noqa: S104 - deliberate: this is a containerized sidecar, bound per-pod
PORT = 8200

# Deliberate, documented bound (ADR-0162 "new template content is a new
# injection surface"). 10 MiB comfortably covers any realistic bank document
# (statement, contract, letter) while bounding memory use per request in this
# sidecar, which has no auth of its own and trusts its network perimeter.
MAX_BODY_BYTES = 10 * 1024 * 1024  # 10 MiB


def _log_json(**fields: object) -> None:
    """Emit one structured JSON line per request to stdout.

    This is a banking-platform sidecar, not a black box: every request
    produces a single parseable JSON line (method, path, status,
    duration_ms) that log shipping (Fluent Bit / CloudWatch etc.) can
    ingest without a custom grok pattern.
    """
    print(json.dumps(fields, default=str), file=sys.stdout, flush=True)


def restricted_url_fetcher(url: str, timeout: int = 10, ssl_context: object = None) -> dict:
    """SSRF/LFI mitigation for template-supplied HTML (ADR-0162 D3).

    WeasyPrint's *default* url_fetcher (`weasyprint.default_url_fetcher`)
    will happily perform outbound network I/O for any absolute http(s) URL
    referenced by a `<link>`, `<img>`, `@import`, etc. — and will also read
    local files for a `file://` URL. Template HTML on this platform can
    originate from a non-engineer's WYSIWYG editor (ADR-0162 D6) and is
    rendered server-side here, so an unrestricted fetcher is a direct
    SSRF/LFI primitive: e.g. `<img src="http://169.254.169.254/latest/
    meta-data/...">` against a cloud metadata endpoint, or
    `file:///etc/passwd`.

    Passing `base_url=None` to `HTML(...)` is NOT sufficient on its own: it
    only stops *relative* URLs from resolving (there is no base to resolve
    against). An *absolute* URL is completely unaffected by `base_url` and
    would still be fetched by the default fetcher. So we replace
    `url_fetcher` entirely.

    The only scheme allowed through is `data:` — a fully self-contained,
    inline URI that involves no network or disk I/O whatsoever (safe to
    delegate to WeasyPrint's own fetcher, which already knows how to decode
    it). Every other scheme raises `ValueError`; WeasyPrint catches that
    per-resource (see `weasyprint.urls.fetch`) and treats it as a missing
    image/stylesheet, so the render still completes — just without that
    external resource, rather than crashing the whole request.
    """
    if url.startswith("data:"):
        return default_url_fetcher(url, timeout=timeout, ssl_context=ssl_context)
    raise ValueError(f"blocked fetch of disallowed URL scheme (SSRF guard): {url!r}")


class RenderHandler(BaseHTTPRequestHandler):
    server_version = "openbank-document-renderer/1.0"

    def log_message(self, format: str, *args: object) -> None:  # noqa: A002
        # Silence BaseHTTPRequestHandler's default stderr access log — we
        # emit our own structured JSON line per request instead (_log_json).
        pass

    def _write_json(self, status: int, payload: dict) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:
        start = time.monotonic()
        if self.path == "/health":
            status = 200
            self._write_json(200, {"status": "ok"})
        else:
            status = 404
            self._write_json(404, {"error": "not found"})
        self._access_log("GET", start, status)

    def do_POST(self) -> None:
        start = time.monotonic()
        status = 500
        try:
            if self.path != "/render":
                status = 404
                self._write_json(404, {"error": "not found"})
                return

            length_header = self.headers.get("Content-Length")
            if length_header is None:
                # No chunked-transfer support in this minimal stdlib server —
                # clients must send a Content-Length header.
                status = 411
                self._write_json(411, {"error": "Content-Length required"})
                return

            try:
                content_length = int(length_header)
            except ValueError:
                status = 400
                self._write_json(400, {"error": "invalid Content-Length"})
                return

            if content_length < 0:
                status = 400
                self._write_json(400, {"error": "invalid Content-Length"})
                return

            if content_length > MAX_BODY_BYTES:
                status = 413
                self._write_json(
                    413,
                    {"error": "payload too large", "max_bytes": MAX_BODY_BYTES},
                )
                # Drain up to the limit so the connection stays reusable
                # instead of leaving unread bytes on the socket.
                self.rfile.read(min(content_length, MAX_BODY_BYTES))
                return

            body = self.rfile.read(content_length)
            try:
                html_source = body.decode("utf-8")
            except UnicodeDecodeError:
                status = 400
                self._write_json(400, {"error": "body must be UTF-8 encoded HTML"})
                return

            try:
                pdf_bytes = HTML(
                    string=html_source,
                    base_url=None,
                    url_fetcher=restricted_url_fetcher,
                ).write_pdf()
            except Exception as exc:  # noqa: BLE001 - any render failure -> 400
                status = 400
                self._write_json(400, {"error": f"render failed: {exc}"})
                return

            status = 200
            self.send_response(200)
            self.send_header("Content-Type", "application/pdf")
            self.send_header("Content-Length", str(len(pdf_bytes)))
            self.end_headers()
            self.wfile.write(pdf_bytes)
        except (BrokenPipeError, ConnectionResetError):
            status = 499  # client disconnected mid-response
        finally:
            self._access_log("POST", start, status)

    def _access_log(self, method: str, start: float, status: int) -> None:
        _log_json(
            method=method,
            path=self.path,
            status=status,
            duration_ms=round((time.monotonic() - start) * 1000, 2),
        )


def main() -> None:
    server = ThreadingHTTPServer((HOST, PORT), RenderHandler)
    _log_json(event="startup", host=HOST, port=PORT)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
