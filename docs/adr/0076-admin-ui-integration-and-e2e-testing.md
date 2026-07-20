---
date: 2026-06-09
decision-status: accepted
delivery-status: shipped
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [admin-ui, testing, ci]
summary: "Admin-UI testing gets four layers: run the existing Vitest suite in CI, MSW integration tests for BFF routes, Playwright E2E for live-state pages, and a docs-as-service schema guard in service CI."
---

# 76. Admin UI integration and E2E testing strategy

## Context

The admin UI surfaces live banking-service state across multiple pages — service documentation
coverage, health/readiness matrix, serverless tier classification, governance manifest, DORA
metrics, finops allocation, and more. Each page fetches data through a Next.js BFF layer that
proxies requests to running services (ADR-0056).

An audit of the current test suite (June 2026) uncovered four compounding gaps:

### Gap 1 — Unit tests never run in CI

`package.json` defines `"test": "vitest run"` and 14 test files exist covering guards, route
handlers, and data-transformation logic (≈ 30 assertions). The CI `ui` job
(`.github/workflows/ci.yml` lines 25–68) runs lint + type-check + build but **never calls
`npm run test`**. The guard tests that enforce graceful error states, i18n completeness, and
app-shell layout are therefore not enforced at PR time.

### Gap 2 — BFF data-loading paths are completely untested

The three most staleness-prone paths have zero test coverage:

| File | Role | Tests |
|---|---|---|
| `src/app/api/services/[name]/docs/route.ts` | Docs-as-Service proxy (live vs. bundled) | ❌ 0 |
| `src/app/api/services/health/route.ts` | Service health probe + K8s discovery | ❌ 0 |
| `src/lib/services/docs.ts` | Docs loader (language fallback, 2 s timeout) | ❌ 0 |
| `src/lib/services/registry.ts` | Service registry + discovery | ❌ 0 |

### Gap 3 — Pages that render live state have no end-to-end test

`src/app/services/page.tsx` displays "X / Y services have documentation". When services gain or
lose docs, add new endpoints, or change their `/q/openbank/docs` shape, the page can show a stale
or incorrect count with no automated signal. Concretely, after adding docs to 32 services in
PR #655 the only way to verify the page shows 37/37 was to manually open a browser — there is
no test that can be run pre-merge.

### Gap 4 — No contract guard between admin UI and service APIs

The admin UI is a consumer of `/q/openbank/docs`, `/q/health/ready`, and `/api/v1/info` on every
service. ADR-0063 established Pact contracts for money-path inter-service REST, but admin UI as a
consumer is out of scope. A service that renames a field in `/q/openbank/docs` silently breaks the
docs page; the breakage surfaces only when a human notices the UI.

### Why this matters

The team has repeatedly encountered the pattern: a service feature lands, the admin UI page is
not updated, the discrepancy is noticed days later. Each of gaps 2–4 is a direct cause. Gap 1
means the guards that prevent graceful-state regressions can also silently break.

### Technology landscape

The existing stack already has **Vitest 4** + **@testing-library/react** + **jsdom** — the
heavyweight testing runtime is already installed. What is missing is:

- A **network-mock layer** (MSW) to intercept BFF → service HTTP calls in tests
- A **browser automation layer** (Playwright) for full-page rendering tests
- A **Docs-as-Service schema guard** in service CI to catch upstream breaking changes

Considered alternatives:

| Option | Pros | Cons |
|---|---|---|
| **Playwright** (chosen for E2E) | Next.js-native, TypeScript first-class, component + full-page modes, parallelism, network interception, trace viewer | Heavier setup than Cypress for pure unit concerns |
| Cypress | Mature ecosystem | CommonJS, slower startup, weaker TypeScript; Next.js recommends Playwright |
| Storybook + Chromatic | Visual regression | Visual, not behavioral; does not verify data-fetch correctness |
| Supertest (Next.js `createServer`) | Fast, no browser | Tests only BFF routes, not page rendering |
| Extend Pact to admin UI | Contract-level precision | Pact JVM is service-side; admin UI is Next.js/TypeScript — different Pact client, more config |

**MSW (Mock Service Worker)** is chosen as the network-mock layer because it:
- Intercepts at the `fetch`/`XMLHttpRequest` level — same mock definition works in Node (Vitest)
  and in a browser (Playwright), eliminating duplicated stub code
- Is the standard recommendation for testing Next.js apps (Next.js docs, Testing Library docs)
- Lets handler files serve as the living specification of "what the admin UI expects from services"

## Decision

### Layer 0 — Fix CI immediately (no ADR required, done alongside this ADR)

Add `npm run test` as a step in the `ui` CI job before `npm run build`. This is a one-line fix;
all existing guard tests then run on every PR that touches `openbank-admin-ui/**`.

```yaml
# .github/workflows/ci.yml  (ui job, after type-check)
- name: Test
  run: npm run test
  working-directory: openbank-admin-ui
```

### Layer 1 — MSW + Vitest integration tests for BFF routes

Install MSW (`msw@^2`) as a dev dependency. Create
`src/test/mocks/handlers.ts` with Request handlers for the service APIs the admin UI calls:

```
GET /q/openbank/docs          → DocsIndex JSON (docs present / absent variants)
GET /q/health/ready           → 200 UP / 503 DOWN / timeout scenarios
GET /api/v1/info              → ServiceInfo JSON
GET /api/catalog/openapi/:svc → OpenAPI YAML passthrough
```

Each BFF route handler (`/api/services/[name]/docs/route.ts`, `/api/services/health/route.ts`)
gets a companion Vitest integration test file under `src/test/`:

| Test file | What it verifies |
|---|---|
| `docs-route.test.ts` | Live-service path returns docs; bundled path serves libs; 404 when absent; language fallback en→cs→any; 2 s timeout → null |
| `health-route.test.ts` | Kubernetes discovery path; static-probe fallback; DOWN when service unreachable; latency captured |
| `docs-loader.test.ts` | `loadDocsIndex()` parses index correctly; language header negotiation; caches 60 s live / no-store bundled |
| `registry.test.ts` | Static SERVICES list has no port collisions (extends existing dashboard-services.test.ts) |

These tests run in **Node via Vitest** (no browser) — fast (< 5 s total), part of `npm run test`.

### Layer 2 — Playwright E2E for critical page scenarios

Install `@playwright/test` as a dev dependency. Add `playwright.config.ts` that:
- Starts Next.js dev server (`webServer`) on a random port before the test run
- Uses the same MSW handlers (imported from `src/test/mocks/handlers.ts`) via a custom fixture
  that starts MSW in Node mode and patches `global.fetch` to go through MSW

**Scope** — only the pages that directly render live service state:

| Test file | Page | Critical scenarios |
|---|---|---|
| `e2e/services-docs.spec.ts` | `/services` | Coverage shows 37/37 when all mocks return docs; shows 0/37 when all mocks return 404; handles individual timeout gracefully |
| `e2e/services-health.spec.ts` | `/services` (health column) | UP/DOWN/SCALED_TO_ZERO classification renders correctly per BFF response |
| `e2e/governance.spec.ts` | `/governance` (if exists) | Governance manifest table renders; missing governance.json degrades gracefully |

E2E tests run **only when `openbank-admin-ui/**` is in the path-scoped diff** (same gate as the
existing `ui` CI job). Target: < 60 s total for the Playwright suite with parallelism.

Playwright trace artifacts are uploaded on failure for debugging.

### Layer 3 — Docs-as-Service schema guard in service CI

Each service CI build (`.github/workflows/services-ci.yml`) already runs `@QuarkusTest`. Add a
test in `openbank-libs` that is inherited by all services:

```kotlin
// openbank-libs/.../docs/DocsSchemaGuardTest.kt
@QuarkusTest
class DocsSchemaGuardTest {
    @Test
    fun `docs index has required fields`() {
        given().get("/q/openbank/docs?lang=en")
            .then().statusCode(200)
            .body("items", not(empty()))
            .body("items[0].slug", not(emptyString()))
            .body("items[0].title", not(emptyString()))
            .body("items[0].lang", equalTo("en"))
    }
}
```

This runs per-service, per-PR. If a service changes the `DocsIndex` schema in a way that breaks
the admin UI consumer, CI catches it in the provider's own build before it merges — not after the
admin UI page silently stops rendering docs.

This is a lightweight alternative to full Pact for this non-money-path consumer: the schema is
simple (slug + title + lang + body), the provider test is one REST-assured assertion, and the
admin UI MSW handlers (Layer 1) serve as the informal consumer contract.

### Phasing

| Phase | Deliverable | Trigger | Blocks merge? |
|---|---|---|---|
| **0** (immediate) | Add `npm run test` to CI `ui` job | This ADR lands | Yes, from day 1 |
| **1** | MSW + Vitest integration tests for 4 BFF route files | Fleet sweep PR, 1 service at a time | Yes (ratchet: coverage may not drop) |
| **2** | Playwright E2E for `/services` and `/governance` pages | Dedicated PR | Yes once green |
| **3** | `DocsSchemaGuardTest` in `openbank-libs` | Separate PR | Yes once merged |

Phase 0 is a code change, not a conceptual gate — it can and should land immediately. Phases 1–3
are additive; each phase builds on the MSW handler definitions established in Phase 1.

### What is NOT in scope

- **Full Storybook visual regression** — distinct concern, separate ADR if desired.
- **Pact consumer contracts for admin UI** — the Docs-as-Service schema is too simple to justify
  the Pact JVM bootstrap. Revisit if admin UI starts consuming money-path APIs directly.
- **Mutation testing for admin UI** — UI logic is predominantly display/routing; mutation testing
  ROI is low outside domain arithmetic (ADR-0063 scope).
- **Performance / load testing** — admin UI is low-frequency operator tooling; not a priority.

## Consequences

### Positive

- Pages that display live service state get regression tests that catch staleness **before
  merge**, not after a human notices the UI.
- The MSW handler files become the living specification of what admin UI expects from service
  APIs — a lightweight, language-appropriate alternative to Pact for a TypeScript consumer.
- Guard tests (graceful states, i18n, layout shell) are enforced in CI from Phase 0 onwards.
- The `DocsSchemaGuardTest` (Phase 3) creates a closed feedback loop: service changes that break
  admin UI are caught in the service's own CI, not discovered visually days later.

### Negative / Trade-offs

- **Playwright adds ~15–20 s to the UI CI job** on the first full run; amortized over PRs it
  is acceptable. The suite must stay scoped to critical pages — avoid E2E-for-everything creep.
- **MSW handlers must be kept in sync with actual service schemas.** If a service adds a new
  field and the handler is not updated, the test passes but the UI might break on the new field.
  The `DocsSchemaGuardTest` (Phase 3) mitigates this for the docs path; other paths rely on
  developer discipline.
- **Layer 1 integration tests stub HTTP** — they do not test the real Kubernetes discovery path.
  That path remains covered only by the existing `dashboard-services.test.ts` structural guard and
  manual sandbox verification.

## Compliance notes

Admin UI is an internal operator tool (not customer-facing). The testing requirements under
DORA (Art. 25 ICT testing) apply to "critical or important" ICT functions. Admin UI is
classified as **Platform / Operator tooling**, not a money-path or customer-facing function.
Therefore the testing obligations here are good engineering practice, not a direct regulatory
mandate. However, if admin UI becomes the primary interface for four-eyes approval of
money-path operations (ADR-0068 onboarding cockpit, ADR-0034 OPA policy toggle), the
classification may need revisiting.
