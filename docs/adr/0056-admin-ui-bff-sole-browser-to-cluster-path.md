---
date: 2026-06-01
decision-status: accepted
delivery-status: shipped
authors: [OpenBank platform]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [admin-ui, security-ops]
summary: "The admin-UI BFF is the only browser-to-cluster path: the browser makes same-origin requests only, the server tier does all egress, and the proxy refuses unauthenticated callers before touching a backend."
---

# Admin-UI BFF as the sole browser→cluster path

## Context

The admin-ui docs surfaces (API catalog `/docs/api`, Service Map `/docs/service-map`, and the
per-service detail panels) linked and fetched **cluster services directly from the browser**:

- live status was read with `fetch("http://localhost:<8100..8127>/q/health/ready" | "/q/openapi" |
  "/api/v1/info")`;
- "Swagger", "Swagger UI", "Health Check" buttons pointed at `http://localhost:<port>/q/swagger-ui`
  / `/q/health`;
- "Changelog" / "Release Notes" linked to `/docs/changelog/<id>` and `/docs/release-notes/<id>`
  routes **that did not exist**;
- "OpenAPI Spec" linked to a hardcoded `raw.githubusercontent.com/.../openapi.yaml`.

From an operator's machine (the app is served at `admin.<...>.sslip.io`, the cluster is remote)
**none of these resolve**: `localhost:81xx` is the operator's own laptop → `ERR_CONNECTION_REFUSED`;
the missing routes → `404`; the raw GitHub path 404s when the repo layout differs. Every one of
these links was dead.

This is the *same class of bug* we already fixed for System Health, where the browser must not
address pods directly. We solved that with a **Backend-for-Frontend (BFF)**: the authenticated
Next.js server proxies to services it resolves via ADR-0051 discovery
(`/api/svc/<k8s-service-name>/<path>`, injecting the operator's Keycloak bearer). The docs surfaces
simply never adopted it.

Three concerns are entangled and must be separated:

1. **Reachability** — only the server tier can route to in-cluster Service DNS / management ports.
2. **Security** — calls must carry the operator's identity (bearer) and be gated; a browser-issued
   call to a backend bypasses our session→bearer relay, and a backend not yet `@RolesAllowed`
   (e.g. product-catalog) would be anonymously reachable.
3. **Artifact vs live data** — a changelog / release note is a *build artifact* (release-please
   output), not a live service endpoint; it has a different, GitHub-backed source.

## Decision

**The admin-ui BFF is the only path from the browser to a cluster service or to build artifacts.
The browser issues same-origin requests exclusively; the server tier does all egress.**

Concretely:

1. **Live service data → `/api/svc/<k8s-service-name>/<path>`** (the existing proxy). The docs
   surfaces use a single helper `src/lib/services/bff.ts#svcUrl(k8sName, path, query)` and never
   construct `http://localhost:<port>` or `…​.svc` URLs. The canonical key is the **Kubernetes
   Deployment/Service name** (`account-service`, `product-catalog`) — the same key the proxy's
   `SERVICE_MAP` and the discovery feed already use. UI-local short ids are resolved to it at the
   call site.

2. **Edge auth on the proxy.** The proxy refuses (`401`) when the caller presents neither an
   explicit bearer nor a valid operator session — *before* touching any backend. This is
   defense-in-depth on top of per-service RBAC, closing the "anonymous relay into an un-gated
   backend" hole.

3. **Embedded, not linked, API exploration.** Swagger-UI is not linked out to `…/q/swagger-ui`
   (unreachable, and a second auth context). The catalog already renders a native, React-19-safe
   endpoint explorer from the OpenAPI document; that document now arrives via the BFF. (We
   explicitly reject `swagger-ui-react`: it pins React ≤18 / legacy lifecycles and is incompatible
   with the app's React 19.) Raw OpenAPI JSON/YAML remain available as same-origin BFF links for
   download.

4. **Build artifacts → docs BFF (`/api/docs/<kind>/<service>`)** backed by
   `src/lib/docs/releases.ts` (server-only). It fetches release-please output **server-side**:
   - `changelog` → `raw.githubusercontent.com/<repo>/<ref>/openbank-<service>/CHANGELOG.md`;
   - `release-notes` → GitHub Releases whose tag matches the component.
   The `/docs/changelog/<service>` and `/docs/release-notes/<service>` **server components** render
   it (reusing `MarkdownView`), so even GitHub is never contacted from the browser. Service ids are
   validated against a fixed `^[a-z][a-z0-9-]{1,48}$` vocabulary to prevent SSRF/path traversal.
   Every fetch fails soft to an empty-but-linked state — never a 404.

5. **Graceful undeployed state.** A service that discovery does not resolve returns `404`/offline
   through the proxy; the catalog already renders "offline / no endpoints" rather than hanging.

## Consequences

**Positive**
- Every docs link works from the operator's browser against a remote cluster; no `localhost`, no
  dead routes.
- One authenticated, audited egress path; backend RBAC is no longer the only gate.
- Changelog/release notes are first-class, bilingual, and sourced from the canonical release-please
  output without a browser→GitHub call.
- New services need no per-link wiring — discovery + the k8s-name convention carry them.

**Negative / trade-offs**
- The BFF proxy is now on the hot path for docs status polling; it already caches discovery for 30 s,
  but a large fan-out (28 services) issues 28×N same-origin calls. Acceptable for an internal admin
  tool; can be batched later behind a single `/api/services/openapi-index` if needed.
- "Try it out" against a live backend is not offered from the embedded explorer (it would need the
  bearer in-browser and CORS). Out of scope; the explorer is read-only documentation.
- Changelog/release-notes depend on the public OSS repo being reachable and on release-please tag
  conventions; both are handled with soft-fail + a configurable `GITHUB_DOCS_REPO`/`_REF`.

## Alternatives considered

- **Keep direct browser→pod calls, fix only the port map.** Rejected: unreachable from a remote
  cluster by construction, and bypasses auth.
- **`swagger-ui-react` embed.** Rejected: React 19 incompatibility; the native explorer already
  covers the need.
- **A NodePort/Ingress per service `/q/*`.** Rejected: exposes management endpoints at the edge and
  multiplies auth contexts; the BFF centralises this.

## Follow-ups

- Optional `/api/services/openapi-index` aggregation to collapse the catalog's fan-out.
- Apply the same `svcUrl` discipline to any remaining surface that still hand-builds service URLs.

## Compliance impact

- PCI DSS: not applicable — operator docs surfaces carry no cardholder data.
- DORA:    engaged — this is an ICT access-path control (single authenticated egress path, proxy refuses unauthenticated callers); specific articles not mapped in this ADR.
- GDPR:    not applicable — proxies service metadata, health and changelogs only.
- PSD2:    not applicable — internal operator tooling, no payment-service interface.
- CNB:     not applicable — internal admin UI, no regulatory return affected.
