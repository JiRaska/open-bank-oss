# openbank-fetch-broker

The only way a governed agent reads the public web (ADR-0387). It serves one MCP tool,
`web.fetch(url, mode)` with `mode` set to `text` or `markdown`. The OPA resource is
`read.public_web` and the tier is `read`.

## What a call does

1. The caller is the agent named by the validated token (`sub = agent:<id>`). The shared ADR-0034
   PDP authorizes `read.public_web` with the request host as an attribute, and fails closed.
2. The broker checks the host again against the caller's charter, `data_scope.public_web.hosts`
   in `agents.yaml`. A charter must be enabled and allow `read.public_web`. Each entry is an exact
   HTTPS hostname, or `{host, render: static|js}`. Wildcards and IP literals are ignored.
3. Only GET is used. URLs with a query string, fragment, userinfo or non-default port are refused.
   A redirect is followed only while every hop stays on the allowlist.
4. robots.txt is honoured: 4xx means no restriction, and 5xx or unreachable means full disallow.
   Each host has a minimum interval between requests, and the User-Agent is generic.
5. `static` hosts are fetched through `SafeHttpClient` (ADR-0320). Text is extracted in-cluster
   from HTML, PDF (PDFBox) and plain text. `js` hosts go to the Browser Run Quick Actions REST API
   with `browser=kitesurf`. If that renderer is not configured or fails, the outcome is
   `NOT_RENDERABLE`.

## Response envelope

`url`, `final_url`, `fetched_at`, `sha256` (of the source bytes), `bytes`, `renderer`, `outcome`
(`FETCHED`, `DENIED` or `NOT_RENDERABLE`), `reason`, `truncated`, and `content`. The content is
wrapped in fixed untrusted-content delimiters, and any copy of the delimiters inside the page is
removed.

## Evidence and metrics

- Every call emits one audit event (`agent.mcp.tool_call`, resource `public_web`) with
  `source_url`, `sha256` and `renderer`.
- `openbank_web_fetch_total{outcome,host}` counts calls. The `host` label only takes
  charter-listed hosts, or `off_list`.
- `openbank_workflow_last_success_age_seconds{workflow="web-fetch"}` (ADR-0237) records success on
  `FETCHED` only.

## Configuration

| Key | Meaning |
|---|---|
| `openbank.fetch-broker.user-agent` | User-Agent sent on every request |
| `openbank.fetch-broker.max-response-bytes` | response size cap passed to `SafeHttpClient` |
| `openbank.fetch-broker.max-content-chars` | extracted-text cap; longer content sets `truncated` |
| `openbank.fetch-broker.per-host-min-interval` | per-host rate limit |
| `openbank.fetch-broker.kitesurf.base-url` | optional; the Browser Run endpoint base |
| `openbank.fetch-broker.kitesurf.api-token` | optional; from the `browser-run-api-token` secret |

The deployment manifests are delivered separately.
