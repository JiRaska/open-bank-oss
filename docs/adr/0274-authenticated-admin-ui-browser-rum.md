---
date: 2026-08-28
decision-status: accepted
delivery-status: partial
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [testing, observability, admin-ui, privacy-gdpr]
summary: "OpenBank emits privacy-bounded browser RUM only from authenticated Admin UI routes through a same-origin relay, so web E2E, RUM and backend traces can be correlated without exposing collector access or operator identifiers."
followup: "#7483 — deployed sandbox navigation and Tempo-backed browser-to-backend proof remain required; customer mobile RUM stays separately consent-gated."
---

# ADR-0274 — Authenticated Admin UI browser RUM

## Context

The Test Intelligence console can show browser E2E and mobile RUM as separate observations, but
the Admin UI itself has no browser-originated trace. That leaves an operator-facing web journey
unable to connect its rendered screen to the backend trace that explains a failure. A direct browser
export to an observability backend would expose an internal endpoint, require a cross-origin policy,
and create a separate credential boundary. Raw operator URLs can also contain customer or case
identifiers and must not become telemetry attributes.

## Decision

We will emit one span per authenticated App Router navigation from `openbank-admin-ui`.

The browser exports only to a same-origin route. The server relay reads its collector endpoint at
runtime and forwards the OTLP payload; the endpoint never enters the browser bundle. The route stays
behind the existing Admin UI authentication boundary. A missing endpoint is reported as an explicit
disabled relay state, not as a successful export.

Resource attributes are a closed, low-cardinality set: service name, bundle version, coarse device
model and operating-system family/version. Span attributes contain only a normalized screen route.
Queries, fragments and identifier-shaped path segments are removed before export. Browser RUM is
restricted to authenticated Admin UI routes; public surfaces do not emit it.

The Test Intelligence UI must continue to treat browser RUM, browser E2E and mobile RUM as distinct
evidence types. A non-zero browser trace proves telemetry arrival, never customer-impact volume or a
test verdict.

## Alternatives considered

- **Keep browser RUM rejected for the internal console** — rejected. The console is now the central
  testing and operational surface; an authenticated, privacy-bounded screen-to-backend trace gives
  direct diagnosis value without session replay or customer telemetry.
- **Export directly from the browser to an observability endpoint** — rejected. It expands CORS,
  credentials and endpoint exposure unnecessarily when a same-origin authenticated relay preserves
  the existing trust boundary.
- **Capture raw URL or user-agent values** — rejected. Both add avoidable identifier/fingerprinting
  risk and unbounded metric cardinality.

## Consequences

**Positive**

- Browser E2E failures can be correlated with bounded runtime traces and backend services.
- The same Test Intelligence page can show web and mobile evidence without claiming they are equal.
- No observability credential or internal endpoint is shipped to the browser.

**Negative**

- A deployed sandbox navigation and Tempo query are still required to prove the complete runtime hop.
- The telemetry intentionally omits full session replay and user-level analytics.

**Neutral**

- Mobile RUM consent, client instrumentation and its hardened public ingest remain separate work.

## Compliance impact

- PCI DSS: not applicable — no payment-data field is emitted; identifier-shaped routes are masked.
- DORA: operational traceability is strengthened; no new automated operational action is introduced.
- GDPR: privacy-by-design posture: no raw user-agent, query, customer or case identifier is exported.
- PSD2: not applicable — this is an internal operator-console telemetry path, not an XS2A interface.
- CNB: not applicable — no reporting dataset or filing obligation changes.

## References

- ADR-0273 — Unified test intelligence evidence and admin UI.
- ADR-0088 — Observability extension, mobile RUM and privacy boundaries.
- New Relic browser-to-backend distributed tracing documentation (W3C trace context and explicit
  cross-origin controls), consulted 2026-08-28.
