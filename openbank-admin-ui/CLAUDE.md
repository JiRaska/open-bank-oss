# openbank-admin-ui — agent & contributor guide

Next.js (App Router, standalone) operator console for the OpenBank fleet. The browser reaches cluster
services **only** through the BFF proxy (`/api/svc/<k8s-name>/<path>`, ADR-0056) — never a `localhost`
port or in-cluster `.svc` DNS name. See the root `CLAUDE.md` for monorepo-wide rules.

## Non-negotiable UI rules (enforced by tests — don't relearn these page-by-page)

### 1. Graceful-state rule — never leak a raw backend failure

In the sandbox much of the fleet isn't deployed, so BFF/internal calls legitimately fail. A
page must **never** render a raw `HTTP 404`, a hand-written "Cannot reach X", or a red `alert-error`
box. Those read as "the app is broken" when the truth is usually "this service isn't deployed here yet".

**Do this instead** (read `src/app/accounts/page.tsx` or `src/app/audit/page.tsx` as the reference):

- Hold a typed state: `const [unavailable, setUnavailable] = useState<{ kind: UnavailableKind } | null>(null)`.
- On a non-OK BFF response: `setUnavailable({ kind: await classifyBffFailure(res) })` and **return** — do
  not `throw` a `` `HTTP ${res.status}` `` string.
- On a thrown fetch (timeout/abort/network): `catch { setUnavailable({ kind: 'unreachable' }) }`.
- Render the shared panel: `<DataUnavailable kind={unavailable.kind} service="X-service" feature={t('…','…')} lang={language} dense />`.
- For loaded-but-empty results use `kind="no_data"`. For user-initiated **write** actions (form submit,
  freeze/close) a calm inline message via `t(czech, english)` is fine — but still never a raw HTTP status.

Helpers: `src/components/feedback/DataUnavailable.tsx` (the panel, bilingual copy per `kind`) and
`src/lib/services/bff.ts` → `classifyBffFailure(res)` (404 `Unknown service`→`not_deployed`, 401→
`unauthorized`, 502 `upstream_unreachable`→`unreachable`, bare 404→`not_found`, else `error`).

**Enforcement:** `src/test/graceful-states.guard.test.ts` scans every `src/app/**/page.tsx` and fails CI
if a page reintroduces a banned anti-pattern (raw `` `HTTP ${ ``, `className="alert-error"`,
"cannot/could not reach" copy). Every **new** page must comply — this is not optional.

### 2. Search & pagination rule — validate client-side, cap the request, page the render

- **Validate before you call.** Never send free text to a typed backend endpoint. The account-service
  rejects a non-IBAN with a raw `Invalid IBAN: <value>` at its domain boundary, so an IBAN field must be
  validated with `isValidIban()` (`src/lib/validation/iban.ts`, ISO 13616 mod-97) **before** the BFF
  call; on failure show an inline field hint, don't fetch.
- **Cap the request.** Always pass a bounded `limit` (default `PAGE_SIZE = 25`) — never fetch an unbounded
  list.
- **Page the render.** Show a bounded slice with a "Load more" control rather than dumping thousands of
  rows into the DOM.

Backend fulltext search (`q=`) across the fleet is the money-path work tracked separately (ADR-0055 /
issues #66–68); until it lands, the UI degrades through rule #1 instead of surfacing a raw error.

### 3. Read-only-consumer rule — the operator console never *produces* governance data

The admin-ui **displays** state; it does not generate it. Two consequences, both industry-standard:

- **Security scanning is a CI/CD concern, never a UI button.** Never let the browser trigger a live scan
  (no `POST /scan`). SAST/DAST/dependency/secret scans run in the pipeline (`.github/workflows/security.yml`
  — Trivy, CodeQL, Gitleaks). The page is a read-only view of the latest verdict. A privileged, expensive
  fleet action behind a UI button is an anti-pattern (OWASP DevSecOps / EBA ICT risk).
- **CI artifacts are baked into the image and served read-only.** Test/coverage and security reports follow
  the same pattern as the SBOM bundle: the deploy build summarises real CI output into a JSON
  (`test-results.json`, `security-report.json`), bakes it into the image, and an internal route serves it
  (`OPENBANK_TEST_RESULTS`, `OPENBANK_SECURITY_REPORT`). Never fabricate numbers; a service with no report
  shows `0` (honest "not run here"). Collectors: `scripts/collect-test-results.mjs`. Routes:
  `src/app/api/test-results/route.ts`, `src/app/api/security/route.ts` — both prefer the bundled file, fall
  back to a live read-only source if configured, else degrade through rule #1.
- **Where the bundle comes from.** `build-push-admin-ui.sh` runs the collector before the image build and
  bakes `openbank-admin-ui/test-results.json` (gitignored — derived, never committed). It reads JUnit XML
  from `${TEST_RESULTS_REPO:-<repo-root>}/openbank-*/build/test-results/`, so in CI (which runs
  `./gradlew test` first) the numbers are real; a clean surgical checkout has no XML, so point
  `TEST_RESULTS_REPO` at a checkout that has built+tested. A guard keeps an existing non-empty bundle rather
  than clobbering it with an all-zero collect, so the page never silently regresses to zeros.

### 4. Bilingual-by-default rule — every user-facing string switches with the language toggle

The console is bilingual (cs/en). The top-right toggle flips a client context
(`useLanguage().t(cs, en)` from `@/lib/i18n/LanguageContext`) that every consumer re-renders from. The
Sidebar/Header were wired up, but page **bodies** were historically authored with hardcoded English (or,
worse, hardcoded Czech) JSX text — so toggling the language changed the chrome but not the content. That
is the bug this rule exists to prevent: it isn't "i18n is broken", the strings were simply never wrapped.

**The rule:** a page renders **no** hardcoded human-language copy. Every user-facing string goes through
`t('Česky', 'English')` — both arguments filled, never one-sided, never Czech-only. This includes JSX
text nodes, `placeholder`, `aria-label`, `title`, `alt`, option labels, table headers, and badge text.
Brand/technical tokens that read identically in both languages (IBAN, SWIFT, VoP, "Party ID", regulator
acronyms) may be wrapped as `t('X', 'X')` so intent stays explicit.

**Footguns** (these silently break the build or the toggle):
- `.map(t => …)` / `.filter(t => …)` **shadows** the translation function `t`. Rename the loop var
  (`item`, `row`, `v`) — never let a callback param eat `t`.
- A helper sub-component defined outside the page component needs its **own** `const { t } = useLanguage()`
  (all admin-ui pages are `'use client'`), or take `t` as a prop. Never reference `t` out of scope.

**Enforcement:** `src/test/i18n.guard.test.ts` scans every `src/app/**/page.tsx` and fails CI on
multi-word hardcoded JSX text or hardcoded `aria-label`/`alt`. It is a **ratchet**: a `BASELINE` set lists
the not-yet-swept long-form docs pages (they may still contain copy); every other page must be clean, and
once a baseline page is swept it must be deleted from `BASELINE` (the test fails telling you to) — so
coverage only ever grows. New pages must comply from day one.

### 5. App-shell rule — every operator page renders inside the Sidebar + Header

The console wraps each page in the app shell — the **Sidebar** (nav) + **Header** — via a per-route
`layout.tsx`. The root `app/layout.tsx` mounts only providers (Session/Language), **not** the shell, so
a route subtree with no shell `layout.tsx` renders the page **bare**: no sidebar, no header. To an
operator that reads as "it opened in a new window, the nav is gone". This is the bug this rule prevents
— FinOps/DevOps (and, it turned out, observability) shipped a `page.tsx` with no `layout.tsx`.

**The rule:** every operator page resolves an app-shell layout — a `layout.tsx` in its **own directory
or an ancestor** (below `app/`) that renders `<Sidebar>`. When you add a new top-level route, add its
`layout.tsx` too (copy `src/app/dashboard/layout.tsx`: Sidebar + Header + scrollable `<main>`). Nested
routes inherit the nearest ancestor shell, so a sub-route (e.g. `system/tests/`) needs no layout of its
own as long as a parent (`system/`) has one.

**Enforcement:** `src/test/layout-shell.guard.test.ts` scans every `src/app/**/page.tsx` and fails CI if
its route subtree has no shell layout. Intentionally shell-less pages (auth screens, the `/` redirect)
are listed in `EXEMPT`. New pages must comply from day one.

### 6. Agent-output rule — AI-agent findings render through one shared panel, directly below the metrics

The fleet has a growing population of AI agents (devops-agent, finops-agent, the AML/sanctions/GDPR
oversight agents — ADR-0031/0112/0119) and **more are coming**. Each one's active output (findings,
anomalies, proposed remediations) is operator-facing, and it was drifting into bespoke copy-pasted card
markup on every page — DevOps, FinOps and IAOps each grew their own near-identical renderer, with the
FinOps one buried at the bottom of an unrelated cost panel where an operator never saw it. That is the
bug this rule prevents: an agent's output must be **consistent, recognisable, and prominent** wherever it
appears, because acting on it (HITL approve/reject) is a governed control surface (ADR-0031 D4), not a
decoration.

**The rule:** a page that surfaces an AI agent's findings renders them through the shared
**`AgentInsightsPanel`** (`src/components/agent/AgentInsightsPanel.tsx`), positioned **directly below the
page's metric/KPI cards** — the first thing the operator reads after the headline numbers, mirroring the
DevOps page (the canonical example). Do **not** hand-roll finding cards. Each page maps its native finding
type → the shared `AgentFinding` view-model (see `toAgentFinding()` in `devops`/`finops`/`iaops/page.tsx`)
so the data sources differ but the rendering is identical: detector chip, severity, title, status pill,
root-cause, tags (DORA metric, est. saving…), timestamp, and optional HITL buttons. The panel is
i18n-agnostic — pass every string already translated via `t()` (the page owns the language context). The
panel renders an honest empty/healthy state when there are no findings (graceful-state rule #1), so it is
always mounted, never conditionally hidden.

**Enforcement:** `src/test/agent-insights.guard.test.ts` scans every `src/app/**/page.tsx` and fails CI if
a page hand-rolls an agent-finding renderer (the HITL lifecycle `proposed/approved/rejected` status-map
signature) instead of importing `AgentInsightsPanel`. New agent surfaces must comply from day one.

### 7. Bearer-relay rule — a BFF route calls an OIDC-gated backend with the operator's token, or not at all

Every `openbank-*` service is OIDC-gated (`quarkus.oidc` + `@RolesAllowed`). The browser holds a NextAuth
**session cookie**, not a bearer, so a server-side route that just `fetch`es a backend sends **no
credential** and gets a 401 — from a perfectly healthy service. The page then blames the operator: the
sanctions screen rendered *"Vypršela relace"* (session expired) at a freshly signed-in operator, its list
tab rendered *"Invalid JSON"* (a 401 body is not the JSON the route parsed), and the security screen
printed *"scanner HTTP 401"*. Three routes, all wrong the same way, because each hand-rolled its own
`fetch` (#3336).

**The rule:** a route that reaches a cluster service goes through a shared upstream helper —
`src/lib/sanctions/upstream.ts` and `src/lib/closings/upstream.ts` are the pattern, and the generic
`/api/svc` proxy documents the reasoning. The helper `auth()`s server-side, sends
`Authorization: Bearer <session.user.accessToken>`, and **returns 401 before touching a backend** when
there is no session (ADR-0056: the BFF must never be an unauthenticated relay into the cluster). A
per-route `fetch` is a per-route opportunity to omit the header again.

Two corollaries, both learned the same day:
- **Never pass an upstream error body to the browser.** Replace it with a generic envelope
  (`{error:"upstream_error"}`) and log the detail server-side (ADR-0080 P1). Forwarding it is how
  `{error:"Invalid JSON"}` became operator-facing copy.
- **Map a backend 401/403 to `kind: 'unauthorized'`, never to a status string.** `classifyBffFailure`
  already does this for the `/api/svc` proxy; a route with its own envelope (like `/api/security`) must
  carry the same case, or it prints `scanner HTTP 401` at someone whose session is fine.

**Verifying it:** mock `@/auth`, assert `new Headers(init.headers).get('Authorization')` on the outgoing
`fetch`, and assert that a session-less call never calls `fetch` at all — see
`src/test/sanctions-security-bff-auth.test.ts`. Run the assertions against the *unfixed* route first; a
bearer test that has only ever seen the fixed code proves nothing.

## Versioning

**Do not hand-edit `version.txt` or `package.json` `version` — a feature PR touches neither.**
release-please owns both: admin-ui is registered in `release-please-config.json` as release-type
`simple`, with a `package.json` extra-files updater, so it bumps them together on a real release.
Your Conventional Commit picks the bump (`feat`→minor, `fix`/`perf`→patch, `BREAKING CHANGE`→major)
— the commit message *is* the changelog.

`check-admin-ui-version-sync.sh` only enforces the *invariant* `version.txt == package.json:version`;
it is not an instruction to bump them yourself. A hand bump duplicates release-please and races any
concurrent admin-ui PR for the same number — on 2026-07-16 two PRs both claimed `0.48.1` and one had
to be rebased.

admin-ui is **not** a money-path service, so a non-breaking change is auto-merge-eligible after the
independent review gate.

## E2E tests (Playwright)

- **Every route is gated on an Auth.js session** (`src/proxy.ts`), and there is no Keycloak in
  the Playwright environment — a `page.goto()` without a session silently renders `/auth/login`
  instead of the target page, so an assertion can time out (or worse, false-pass against login-page
  markup) without anyone noticing. Sign in first via `signInAsOperator(context, baseURL)`
  (`e2e/helpers/auth.ts`) in a `test.beforeEach` — it mints a real Auth.js session-token cookie
  (`@auth/core/jwt` `encode()`, same secret/salt `authOptions.ts` uses) and injects it with
  `context.addCookies()`. No production auth/middleware code is touched. Every new e2e spec needs
  this same sign-in. The `NEXTAUTH_SECRET` used to sign the cookie (`e2e-test-secret` by
  default, overridable via env) is **test-only** — it signs a session on the ephemeral
  `next dev` server this test run spawns, never a deployed environment; `authOptions.ts`'s
  `requiredSecret()` already refuses any dev fallback once `NODE_ENV=production`.
- **Locators must not collide with Next.js dev-mode's own DOM.** `playwright.config.ts`'s
  `webServer` runs `next dev`, and a hidden error/warning overlay can inject elements that match a
  naive text regex (e.g. `/\d+\/\d+/` also matches the overlay's own pagination badge). Scope
  assertions to `main` (page content) or a specific landmark rather than a bare `page.getByText`.
- The `E2E tests (Playwright)` CI step is enforced inside the ui-build job (#653) and, since
  #3675, merge-blocking for admin-ui-touching PRs: `Validate manifests` depends on the `ui`
  aggregator, which fails on any ui-build failure. It stays path-scoped — a PR that touches no
  admin-ui file skips the suite entirely.

## Build & verify

```
npm run type-check      # tsc --noEmit
npm run test            # vitest run (includes the graceful-state guard)
npm run build           # full production build — `next build --webpack`, NOT bare `next build`
npx eslint .            # lint
```

- **`next build` under TURBOPACK emits no client source maps, so a crash reporter can never name a
  line — build with `--webpack`.** Next 16 defaults to Turbopack, and `productionBrowserSourceMaps`
  is read only by `next/dist/build/webpack*`, so setting it changes nothing: measured on 16.2.12,
  `.next/static` held **0** `.map` files with the option on or off, against 1056 under
  `.next/server`. GlitchTip therefore stored every admin-ui error with a minified title and an
  **empty culprit** — the same information the person reporting it already had (#3235). Nothing goes
  red about this: the config option is accepted, the upload plugin runs, and it uploads nothing.
  Two follow-on traps, both measured rather than assumed:
  - **Webpack rejects extra value exports from a `route.ts` that Turbopack tolerates** (`"X" is not a
    valid Route export field`) — so the bundler switch surfaces latent route-file violations one
    build at a time. Exported `type`/`interface` are fine (they are not in the value namespace); a
    `const`/`function` is not.
  - **Do NOT reach for `productionBrowserSourceMaps: true` once on webpack.** It forces
    `devtool: 'source-map'`, appending `//# sourceMappingURL=` to every client chunk (198 of them
    here) — which, with maps deleted after upload, is a 404 per chunk and a published index of where
    the sources would be. Leave it unset: @sentry/nextjs then selects `hidden-source-map`, which
    wrote the same maps for upload and left **0** pointers in the bundle while injecting debug IDs
    into 354 chunks. Symbolication joins events to bundles on that debug ID, not on the release.
- **A generator that VALIDATES something must read the schema, never re-type it — and the schema
  itself should have exactly one author.** `scripts/` holds the fleet's derived-data generators
  (`generate-governance.mjs`, `generate-catalog.mjs`, `generate-security-graph.mjs`, …) and several
  are also the CI gate for the thing they read. A gate that hand-copies its constraints out of a spec
  into a `const` is a *second* source of truth, and the copy is always the one that stops being
  checked: `governance.schema.json` was cited only from a comment while `generate-governance.mjs`
  re-implemented a subset of it (required list, enums, the schemaName/stateless rule) — the two
  drifted and four services shipped a bare `lineage:` key (YAML parses that to `null`, the schema
  says `type: object`) that the green gate accepted for months. The first fix compiled the JSON
  Schema with ajv and derived the constants from it — a real improvement, but it left the JSON
  Schema itself hand-maintained, i.e. a schema with one author who might still drift its prose from
  its constraints. ADR-0196 went one step further: the rules now live ONCE, as a Zod schema in
  `scripts/governance-schema.mjs` (Zod was already an admin-UI dependency — no new package), and
  `governance.schema.json` is DERIVED from it (`--emit-schema`), with a unit test failing on drift.
  Where a friendlier message is worth keeping a hand-written rule, keep it — but assert in a test
  that the two verdicts *agree* case-by-case, or you have just re-created the drift with extra
  steps. Same footgun as the CI-probe one: the checker could not express the failure, so it reported
  success.
- **Keep `package-lock.json` in sync — a drift breaks CI *fleet-wide*, not just admin-ui.** The
  "Customer-app dossier" job runs `npm ci` here (it installs admin-ui for the YAML parser) on **every
  repo PR**, and `npm ci` hard-fails on any lockfile drift: a missing transitive dep
  (`Missing: react-is@17.0.2 from lock file`) or a stale top-level `version` that lags a
  release-please `package.json` bump. It went red on every open PR on 2026-07-24 (#2040) and, being
  advisory (not in the required set), silently masked real dossier failures. Fix: run `npm install`
  in `openbank-admin-ui`, commit **only** `package-lock.json`. Any change to `package.json`
  dependencies must commit the regenerated lockfile in the same PR. Existing PRs cut before the fix
  stay red on their own stale lockfile until rebased/`gh pr update-branch`d — a re-run alone won't
  clear it.
- **Run `npm test`, never `npx vitest run` — the latter skips `pretest` and manufactures failures.**
  `pretest: node scripts/generate-governance.mjs && node scripts/generate-catalog.mjs` produces
  `catalog.json` and the governance fixtures that `finops-taxonomy`, `finops-allocation` and
  `service-registry.guard` read. Bypass it and those 4–5 tests fail with `ENOENT: … catalog.json`,
  which looks exactly like a pre-existing broken suite. Measured 2026-07-26: `npx vitest run` → 5
  failed; `npm test` → **781 passed, exit 0**. The trap is the second half: "confirming" the
  failures are pre-existing by stashing your branch and re-running **the same wrong command** proves
  nothing, because the probe carries the defect it is meant to rule out. That mistake got as far as
  two PR descriptions before it was caught.
- **A CSS or layout regression passes `tsc`, `eslint` AND the mount-only `render-smoke` suite.**
  jsdom applies no stylesheet, so geometry is never measured. PR #2556 shipped three that way: a
  swatch that reused `.badge` and overrode `width`/`height` inline — but `.badge` sets
  `padding: 4px 10px; border-radius: 20px` and `globals.css` sets a global
  `box-sizing: border-box`, so an 18px box had 20px of horizontal padding (**zero** content width)
  and a 26px tile rendered as a circle; a header icon silently dropped in a migration; and a
  `StatCard` that tinted its label but not its value. For anything visual, measure it in a browser —
  either an `e2e/` spec asserting `getComputedStyle` / `boundingBox` (see
  `e2e/ui-primitives.spec.ts`), or render the page to static HTML from a throwaway vitest dump with
  `globals.css` inlined, serve it over HTTP and look. **Never override `.badge`'s geometry** — use
  `.tone-swatch` / `SWATCH_CLASS`, which composes the colour-only `.badge-*` rule with its own
  padding and radius.
- **`BpmnView`'s `viewBox` is hardcoded `0 0 1120 300`** (`components/docs/BpmnView.tsx`). A node
  placed beyond that is **silently clipped** and `bpmn-manifest.test.ts` stays green — it validates
  the manifest against the Zod schema, which knows nothing about the canvas. Keep `x ≤ 1080` (the
  widest existing diagrams, `dispute` and `lending`, stop there) and confirm against the rendered
  SVG that no node box exceeds 1120. Also: every `kind: async` edge **must** carry a `topic` — the
  manifest test enforces it, so a colliding topic caption is fixed by shortening the node label, not
  by dropping the topic.

- **A `standalone` build silently un-instruments OpenTelemetry unless every OTel package is in
  `serverExternalPackages`.** `@opentelemetry/instrumentation-*` works by PATCHING a module at
  load time; webpack bundling changes module identity, so the patch lands on webpack's copy and
  never on the module Next.js actually calls out through. The SDK still starts, installs a global
  propagator into its own bundled copy of `@opentelemetry/api`, logs nothing wrong — and emits
  zero spans. Measured on the artifact (#6164): with only `pg` external,
  `.next/standalone/node_modules/@opentelemetry` held exactly ONE entry (`api`); with the OTel
  packages listed, 29. **"The SDK failed to start" is the wrong diagnosis** and sends the next
  reader hunting in the wrong place — with `OTEL_LOG_LEVEL=debug` it starts in BOTH builds, and
  the only difference is the stack frame: `.next/server/instrumentation.js` (broken) versus
  `node_modules/@opentelemetry/sdk-node/build/src/sdk.js` (working). The pod is Ready with 0
  restarts and a clean log either way.
- **Nothing unauthenticated reaches a backend, so a smoke test cannot prove BFF tracing works.**
  `src/proxy.ts` sends every path except `/auth`, `/privacy` and `/.well-known/` to login
  (ADR-0080 P0, post-pentest). Every other route answers 307 before the handler runs, `/privacy`
  is static, and `/api/auth/*` is served locally — so no outbound `fetch` happens and no span can
  exist. Verifying tracing end-to-end needs a real operator session; the synthetic journey does
  not help either, it only GETs `/`.

- **`instrumentation.ts` is TOO LATE to start an OpenTelemetry SDK that patches `node:http`, and
  the failure is silent in every direction you would check.** `@opentelemetry/instrumentation-http`
  works by monkey-patching `node:http` at require time; by the moment Next.js calls `register()`
  the standalone server has already loaded it and created its listener, so the patch lands on
  nothing. Measured 2026-08-21 against the standalone build: the SDK **starts** (its `start()`
  frame is in the `OTEL_LOG_LEVEL=debug` output, resolved out of `node_modules`), the process is
  healthy, requests are served — and **zero** spans are exported. `NEXT_OTEL_VERBOSE=1` changed
  nothing, and Next.js's own `BaseServer.handleRequest` span did not appear either. So "the SDK
  failed to start" is the wrong diagnosis and will send you hunting the wrong thing; the right one
  is load order. Fix: preload it with `NODE_OPTIONS=--require /app/otel-bootstrap.cjs` (baked in
  the Dockerfile). Two consequences worth knowing: a `--require` preload is invisible to Next.js
  file tracing, so the OTel packages reach `.next/standalone/node_modules` only because
  `src/lib/telemetry/tracing.ts` imports them — delete that seemingly-unused module and the
  container dies on MODULE_NOT_FOUND; and the preloaded file cannot be TypeScript, which is why
  the scrub rules live in a plain `otel-scrub.cjs` the tests import directly rather than in a TS
  copy that would drift.
- **Scrub PII at the EXPORTER, not in per-instrumentation hooks — Next.js emits spans no hook of
  yours will ever see.** `applyCustomAttributesOnSpan` / `requestHook` only run for spans that
  instrumentation created. Next.js calls the OpenTelemetry API directly for
  `BaseServer.handleRequest`, so with hooks alone a request to `/privacy?token=SECRET123` still
  put the token on the wire. Also grep the attribute names before believing a scrub works: the
  first version covered `url.full` and `http.url`, **neither of which the inbound HTTP
  instrumentation sets** — the leak was in `http.target` and `url.query`, so the scrub ran, found
  nothing to do, and reported success. Wrap `exporter.export` instead; it is the one place that
  sees every span whatever created it (`otel-scrub.cjs: scrubSpans`).

<!-- BEGIN:nextjs-agent-rules -->

# This is NOT the Next.js you know

This version has breaking changes — APIs, conventions, and file structure may all differ from your training data. Read the relevant guide in `node_modules/next/dist/docs/` (resolved from this file's directory; in monorepos the `next` package may not be visible from the repo root) before writing any code. Heed deprecation notices.

This block is written and re-added by `next dev` — verify at `node_modules/next/dist/server/lib/generate-agent-files.js`. Removing it from a diff only re-creates the uncommitted change; committing it with your work keeps the tree clean.

<!-- END:nextjs-agent-rules -->
