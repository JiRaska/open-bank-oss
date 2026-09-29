"""Tests for the document renderer's SSRF/LFI guard (ADR-0162 D3, #11448).

Run from openbank-document-renderer/: `python -m pytest tests`.
"""

from __future__ import annotations

import base64
import http.server
import sys
import threading
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import app  # noqa: E402  - the module under test lives one directory up
from weasyprint import HTML  # noqa: E402
from weasyprint.urls import URLFetcher  # noqa: E402

# 1x1 transparent PNG.
PNG_B64 = (
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="
)
DATA_URI = f"data:image/png;base64,{PNG_B64}"

FORBIDDEN = [
    "http://169.254.169.254/latest/meta-data/iam/security-credentials/",
    "file:///etc/passwd",
    "https://example.com/logo.png",
]


def _render(html: str) -> bytes:
    return HTML(string=html, base_url=None, url_fetcher=app.RestrictedURLFetcher()).write_pdf()


@pytest.fixture
def parent_fetch_calls(monkeypatch):
    """Record every URL that gets past the guard into WeasyPrint's real fetcher."""
    calls: list[str] = []
    original = URLFetcher.fetch

    def spy(self, url, headers=None):
        calls.append(url)
        return original(self, url, headers)

    monkeypatch.setattr(URLFetcher, "fetch", spy)
    return calls


def test_simple_html_renders_to_pdf():
    pdf = _render("<html><body><h1>Statement</h1><p>Hello</p></body></html>")
    assert pdf.startswith(b"%PDF-")


@pytest.mark.parametrize("url", FORBIDDEN)
def test_guard_refuses_forbidden_url(url, parent_fetch_calls):
    with pytest.raises(ValueError, match="SSRF guard"):
        app.RestrictedURLFetcher().fetch(url)
    assert parent_fetch_calls == []


@pytest.mark.parametrize("url", FORBIDDEN)
def test_render_never_fetches_forbidden_url(url, parent_fetch_calls):
    html = f'<html><head><link rel="stylesheet" href="{url}"></head><body><img src="{url}"></body></html>'
    pdf = _render(html)
    assert pdf.startswith(b"%PDF-")
    assert parent_fetch_calls == [], f"guard let {parent_fetch_calls!r} reach the real fetcher"


def test_render_sends_no_request_to_a_live_http_server():
    hits: list[str] = []

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):  # noqa: N802
            hits.append(self.path)
            self.send_response(200)
            self.send_header("Content-Type", "image/png")
            self.end_headers()
            self.wfile.write(base64.b64decode(PNG_B64))

        def log_message(self, *args):
            pass

    server = http.server.HTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        url = f"http://127.0.0.1:{server.server_port}/meta-data"
        _render(f'<html><body><img src="{url}"></body></html>')
    finally:
        server.shutdown()
        server.server_close()
    assert hits == []


def test_data_uri_is_still_served(parent_fetch_calls):
    response = app.RestrictedURLFetcher().fetch(DATA_URI)
    assert response.read() == base64.b64decode(PNG_B64)
    pdf = _render(f'<html><body><img src="{DATA_URI}"></body></html>')
    assert pdf.startswith(b"%PDF-")
    assert DATA_URI in parent_fetch_calls
