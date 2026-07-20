---
date: 2026-05-29
decision-status: accepted
delivery-status: partial
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [docs, architecture, libs]
summary: "Each service self-publishes its own bundled documentation over its own port via a shared libs DocsResource, so docs version with the code instead of being baked into the admin-ui image."
---

# 19. Docs-as-Service — services self-publish their bundled documentation

**Delivery note (updated 2026-07-01):**
- **Phases 1–3** — ✅ Shipped: `DocsResource`/`DocsCatalog`/`ClasspathMarkdownLoader` (now in `openbank-libs-domain`/`-runtime`, package `com.openbank.libs.docs`, after the ADR-0122 split); account-service and balance-service pilot (7 sections × 2 languages + diagrams); admin-ui server-rendered docs page with i18n cookie, version chip, and inline Mermaid rendering (embedded ` ```mermaid ` fences via `MermaidEnhancer`).
- **Phase 4 (fleet rollout)** — ✅ Shipped: 38 services each ship a full 14-file `docs/` tree (526 `.md`, genuine prose) + a 3-file `diagrams/` tree (114 `.mmd`). The "remaining ~30 services / needs `docs/*.md` populated" note was stale.
- **Phase 5b (diagram serving)** — ⬜ **Pending**, not shipped: there is no `DiagramsCatalog` class, no `_diagrams/{slug}` endpoint, no `diagrams` field on `IndexPayload`, and no admin-ui `/diagrams/[slug]` route; schema is `openbank.docs.v3`, not v4. The `.mmd` files exist as content and render only as embedded fences — the three named Phase-5b artifacts do not exist.

## Context

Per-service documentation lived as Markdown files in each service repo,
bundled into the **admin-ui** Docker image at build time and served by a
filesystem proxy in the admin-ui itself. The original Docs-1 / Docs-2 / Docs-3
pilot (commits `1824468`, `637ebe0`, `fe345ca`) used this model.

Three forcing problems:

1. **Docs version drift from code version.** The admin-ui image is rebuilt
   independently of services. A service released today could be paired with
   docs from a week ago. For audit and operations runbooks this divergence is
   wrong by construction.
2. **Static bundle, no live signal.** The bundle path required a host mount
   in dev and a custom bake step in the Dockerfile. Nothing per-service was
   testable as "the service publishes its own contract."
3. **Fleet-wide rebuild whenever any docs change.** A typo fix in
   account-service forced rebuilding admin-ui plus re-uploading the entire
   docs bundle layer.

Compare existing "service self-publishes its own metadata" patterns we already
use: `/q/health` (Quarkus health extension), `/q/metrics` (Micrometer),
`/q/openapi` (OpenAPI Schema), `/api/v1/info` from openbank-libs
`ServiceInfoResource`. All of these expose service-owned data over the
service's own port. Docs should follow the same pattern.

Backstage TechDocs uses the same idea at a higher level (services own their
docs, a portal aggregates). We don't need Backstage today but we want the
file layout to be Backstage-compatible so a future migration is mechanical.

## Decision

Each service self-publishes its bundled documentation under the well-known
prefix `/q/openbank/docs` on the service's own HTTP port. The implementation
lives in `openbank-libs` so every service inherits the endpoint by virtue of
depending on libs.

### File-system contract per service

```
src/main/resources/
  docs/
    README.cs.md
    README.en.md
    01-overview.cs.md
    01-overview.en.md
    02-architecture.cs.md
    02-architecture.en.md
    03-api.cs.md
    03-api.en.md
    04-data.cs.md
    04-data.en.md
    05-operations.cs.md
    05-operations.en.md
    06-compliance.cs.md
    06-compliance.en.md
  diagrams/                       (optional; Phase 5b)
    01-some-flow.mmd              raw Mermaid source
    02-er-schema.mmd
    03-state-machine.mmd
```

- Section numbering mirrors **arc42-lite** (overview / architecture / API / data
  / operations / compliance).
- `<slug>.<lang>.md` naming carries i18n; the platform's two pilot languages
  are `cs` and `en`. Single-language services may ship `<slug>.md` (the
  language-agnostic fallback).
- Diagrams are flat (`diagrams/<NN>-<slug>.mmd`), Mermaid only — BPMN viewer
  is a heavy front-end dep and is deliberately out of scope.

### HTTP contract

```
GET /q/openbank/docs[?lang=cs]
    schema "openbank.docs.v4"
    {
      service, version, buildTime, gitCommit,
      available, requestedLang, availableLanguages,
      links: { openapi, swagger, health, metrics, info, docsMeta },
      diagrams: [ { slug, title, bytes, etag } ... ],
      items:    [ { slug, lang, availableLanguages, title, bytes, etag } ... ]
    }

GET /q/openbank/docs/_meta
    catalogue-wide etag for change detection

GET /q/openbank/docs/{slug}[?lang=cs]
    text/markdown; charset=utf-8
    ETag + If-None-Match → 304

GET /q/openbank/docs/_diagrams/{slug}
    text/vnd.mermaid; charset=utf-8
    ETag + If-None-Match → 304
```

- Slug regex: `^[a-z0-9-]{1,60}$` — blocks path traversal.
- Lang regex: `^[a-z]{2}$` — blocks header injection.
- Classpath-resource lookup; cannot escape the JAR even if the regex were bypassed.
- `@PermitAll` because the prefix is meant for the network-gated management
  port. Do not expose `/q/openbank/docs` to the public Internet.

### Admin-ui consumption

The admin-ui treats per-service docs as **server-rendered content**. Routes:

- `/services` — overview, lists every candidate service and probes
  `/api/services/<id>/docs` to find which have docs available.
- `/services/[name]/docs/[[...slug]]` — server component, reads markdown via a
  shared loader (`src/lib/services/docs.ts`) that fetches live from the
  service for runnable services or from an image-baked bundle for `libs`
  (which has no runnable service to host the endpoint).
- `/services/[name]/diagrams/[slug]` — Mermaid viewer, renders via the
  existing `MermaidEnhancer` (client-only enhancement).

The admin-ui does **not** mount any service repo path at runtime. It reaches
service endpoints through its `/api/svc/<service>` proxy, so links stay
first-party.

### Schema versioning

Wire schema is a single string published in the index payload:

- `openbank.docs.v1` — initial (Phase 1, items only)
- `openbank.docs.v2` — adds per-item `lang` + `availableLanguages` (Phase i18n)
- `openbank.docs.v3` — adds top-level `links` (Phase 5)
- `openbank.docs.v4` — adds top-level `diagrams` (Phase 5b)

Each bump is **additive only** — older clients ignore unknown fields. Removal
of a field requires a major schema bump and a 6-month deprecation overlap
identical to the API versioning rule from ADR 0009.

## Alternatives considered

- **Status quo: docs bundled into the admin-ui image** — per-service Markdown baked into the admin-ui Docker image at build time and served by a filesystem proxy in admin-ui, as in the original Docs-1/Docs-2/Docs-3 pilot. Rejected for three forcing problems: docs version drifts from code version (wrong by construction for audit and runbooks), the static bundle needs a dev host mount plus a custom bake step and gives no per-service live signal, and any docs change forces a fleet-wide admin-ui rebuild and bundle re-upload.
- **Backstage TechDocs** — a portal that aggregates service-owned docs, the same idea at a higher level. Not adopted: the ADR states we don't need Backstage today, but keeps the file layout Backstage-compatible so a future migration is mechanical.
- **BPMN diagram viewer** — deliberately out of scope; a heavy front-end dependency, so diagrams are Mermaid only.

## Consequences

**Positive.**

- Docs version always equals running code version — same JAR, same release.
- Service owns its docs end-to-end; no admin-ui rebuild on doc change.
- Backstage-compatible file layout (`docs/`, `<slug>.<lang>.md`) — future
  migration to a real TechDocs server is a file move, not a rewrite.
- Same `/q/...` ergonomics ops already know from health/metrics/openapi.
- Unit-testable in libs (`DocsCatalogTest`) without
  touching Quarkus.

**Negative / accepted trade-offs.**

- Service must be running for admin-ui to show its docs. Mitigated by a 2 s
  fetch timeout + graceful "service offline" UX in the docs page. The
  always-on `libs` exception covers the only artefact that has no runtime.
- Each service carries its docs in its JAR — small (kB per file) but multiplied
  across the fleet. Acceptable given the consistency win.
- Phase 4 fleet rollout (every service ships its `docs/` tree) is a multi-
  session content-authoring task. Tracked separately; the platform is in place
  whether the content is or not.

## Implementation status

- ✅ libs primitives — `DocsResource`, `DocsCatalog`, `ClasspathMarkdownLoader`,
  `DocsCatalogProducer` (Phase 1)
- ✅ openbank-libs `docs/` pilot — 7 sections × 2 languages (Phase 2 / i18n)
- ✅ openbank-account-service pilot — 7 sections × 2 languages + 3 diagrams
- ✅ openbank-balance-service pilot — 7 sections × 2 languages
- ✅ admin-ui server-rendered docs page + i18n cookie + version chip (Phase 3)
- ✅ admin-ui live fetch via `/api/svc` proxy (Phase 3)
- ✅ Well-known endpoint chips — openapi / swagger / health / metrics / info /
  docsMeta (Phase 5)
- ✅ Phase 4 fleet rollout — 38 services ship full `docs/` trees (526 md) + `diagrams/` (114 mmd)
- ⬜ Diagram auto-render (Phase 5b) — NOT shipped: `DiagramsCatalog`, `_diagrams/{slug}` endpoint, and
  the admin-ui `/services/[name]/diagrams/[slug]` viewer do not exist; schema is v3, not v4. `.mmd`
  files render only as embedded ` ```mermaid ` fences today.

## Related

- ADR 0014 — openbank-libs centralization roadmap (rationale for adding more
  capabilities to libs rather than to every service)
- ADR 0020 — Kover coverage gate on libs (covers the docs primitives)
- Pilot services: `openbank-libs/docs/`, `openbank-account-service/src/main/resources/docs/`,
  `openbank-balance-service/src/main/resources/docs/`

## Compliance impact

- PCI DSS: not applicable — no cardholder data in bundled service documentation.
- DORA:    engaged — the ADR motivates the change by audit and operations-runbook accuracy; specific articles not mapped in this ADR.
- GDPR:    not applicable — service documentation content, no personal data.
- PSD2:    not applicable — documentation endpoint, no payment interface.
- CNB:     not applicable — no CNB requirement is referenced in this ADR.
