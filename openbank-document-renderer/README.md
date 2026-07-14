# openbank-document-renderer

A small, standalone PDF-rendering **sidecar**: HTML in, PDF bytes out. It implements the
default lightweight adapter behind `openbank-document-service`'s `PdfRenderPort`, per
[ADR-0162 decision D3](../docs/adr/0162-document-management-templating-and-e-signature-architecture.md).

## Why this exists

The platform's document templates (statements, contracts, letters, GDPR export letters,
KYC forms) are structured, print-oriented layouts that do not need flexbox/grid/JS or a
full browser engine. ADR-0162 D3 picks **WeasyPrint** (BSD-3-Clause) as the default
renderer for that reason: no browser engine, a much smaller footprint than headless
Chromium (~50–150 MB per process vs. ~150–400 MB), and — because it never opens an
arbitrary network connection to satisfy a `<link>`/`<img>` URL the way Chromium will — a
smaller SSRF surface by construction.

This directory is **not** an `openbank-*-service`: it has no `version.txt`, is not
registered in `release-please-config.json`/`.release-please-manifest.json`, and is not
part of the Gradle/Kotlin monorepo build. It is a standalone, non-JVM helper image that the
platform runs as a sidecar — the same relationship the OPA sidecar has to the services that
call it. `openbank-document-service` (a separate, concurrently-developed Kotlin service) is
the only expected caller; this image ships and versions independently of it.

## Running locally

```bash
cd openbank-document-renderer
docker build -t openbank-document-renderer:local .
docker run --rm -p 8200:8200 openbank-document-renderer:local
```

Render a document:

```bash
curl -sS -X POST http://localhost:8200/render \
  -H 'Content-Type: text/html' \
  --data-binary '<html><body><h1>Hello, OpenBank</h1></body></html>' \
  -o out.pdf
```

Check liveness:

```bash
curl -sS http://localhost:8200/health
# {"status": "ok"}
```

## Endpoints

| Method | Path       | Request                        | Response                                    |
|--------|------------|---------------------------------|----------------------------------------------|
| POST   | `/render`  | Raw HTML body (`Content-Type: text/html`), max 10 MiB, requires `Content-Length` (no chunked transfer) | `200` + `application/pdf` bytes, or `4xx` JSON error |
| GET    | `/health`  | —                                | `200` `{"status": "ok"}` — liveness/readiness probe |

Notes on the contract:
- The service is stdlib `http.server` only (`BaseHTTPRequestHandler` +
  `ThreadingHTTPServer`) plus the `weasyprint` package — deliberately no Flask/FastAPI/
  uvicorn, to keep the image and its dependency/CVE surface minimal for something that
  spends its whole life parsing externally-influenced HTML.
- A request body over 10 MiB is rejected with `413` — a documented, deliberate bound, not
  a silent truncation. A missing `Content-Length` header is rejected with `411` (the
  minimal stdlib server does not support chunked transfer-encoding).
- Every request emits one structured JSON line to stdout
  (`{"method", "path", "status", "duration_ms"}`) — this is a banking-platform sidecar, not
  a black box; point normal log shipping (Fluent Bit, CloudWatch, etc.) at stdout.

## SSRF/LFI mitigation

WeasyPrint's *default* `url_fetcher` will perform outbound network I/O for any absolute
`http(s)://` URL referenced by template HTML (`<link>`, `<img>`, `@import`, …) and will
read local files for a `file://` URL. Template HTML on this platform can originate from a
non-engineer's WYSIWYG editor (ADR-0162 D6), so an unrestricted fetcher run server-side is
a direct SSRF/LFI primitive — e.g. an `<img>` pointed at a cloud metadata endpoint, or a
`file:///etc/passwd` read.

Passing `base_url=None` alone is **not** sufficient: it only prevents *relative* URLs from
resolving (there's no base to join against). An *absolute* URL is unaffected by `base_url`
and would still be fetched.

So `app.py` replaces `url_fetcher` entirely (`restricted_url_fetcher`): only the `data:`
scheme is allowed through (fully inline, no network/disk I/O possible); every other scheme
raises `ValueError`, which WeasyPrint catches per-resource and treats as a missing
image/stylesheet — the render still completes, just without that external resource,
instead of the whole request failing.

## Opt-in: Gotenberg profile

`openbank-document-service` selects its renderer per template via a config key,
`openbank.render.profile: weasyprint | gotenberg`. When set to `gotenberg`, the Kotlin
`PdfRenderPort` HTTP adapter targets [Gotenberg's](https://gotenberg.dev/) own official
`gotenberg/gotenberg:8` image directly (its `POST /forms/chromium/convert/html` endpoint)
instead of this sidecar — Gotenberg needs no custom wrapper here, just a second, optional
Deployment in gitops. That wiring (the config plumbing, the second adapter, the gitops
manifest for the Gotenberg Deployment) is **out of scope for this directory** and is a
separate, later task.

The two profiles do **not** share an HTTP call shape, and whoever implements the Kotlin
adapter needs both:

- **WeasyPrint (this sidecar):** `POST /render`, `Content-Type: text/html`, raw HTML bytes
  as the body, `application/pdf` bytes back. No multipart, no form fields.
- **Gotenberg:** `POST /forms/chromium/convert/html`, `multipart/form-data`, with the HTML
  as a form file part conventionally named `index.html` (Gotenberg renders whatever file is
  named `index.html` in the multipart payload; additional assets — CSS, images, fonts — can
  be attached as sibling form parts and are resolved relative to it). Response is also
  `application/pdf` bytes, but the request construction is a different shape entirely from
  a raw-body POST.

`PdfRenderPort`'s domain-level contract (HTML in, PDF bytes out) is the same either way;
only the two HTTP adapters' request-building code differs.

## Ports & paths (for gitops / the Kotlin adapter)

- Container port: **8200** (chosen clear of the existing `openbank-*-service` Dockerfile
  `EXPOSE` range, which currently runs up to 8143).
- `POST /render`, `GET /health` as documented above.
- No authentication is implemented in this sidecar — it is expected to sit behind the
  cluster's internal network policy, reachable only from `openbank-document-service`, the
  same trust model as the OPA sidecar. Do not expose it outside the cluster.
