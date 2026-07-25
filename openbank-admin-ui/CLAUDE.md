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

- **Every route is gated on an Auth.js session** (`src/middleware.ts`), and there is no Keycloak in
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
- The `E2E tests (Playwright)` CI step is currently `continue-on-error: true` (advisory, not a merge
  gate) pending full browser-dep provisioning across the runner pool — see `ci.yml` for the exact
  condition to watch before tightening it.

## Build & verify

```
npm run type-check      # tsc --noEmit
npm run test            # vitest run (includes the graceful-state guard)
npx next build          # full production build
npx eslint .            # lint
```

- **A generator that VALIDATES something must read the schema, never re-type it.** `scripts/` holds
  the fleet's derived-data generators (`generate-governance.mjs`, `generate-catalog.mjs`,
  `generate-security-graph.mjs`, …) and several are also the CI gate for the thing they read. A gate
  that hand-copies its constraints out of a spec into a `const` is a *second* source of truth, and
  the copy is always the one that stops being checked. `governance.schema.json` was cited only from a
  comment while `generate-governance.mjs` re-implemented a subset of it (required list, enums, the
  schemaName/stateless rule) — the two drifted and four services shipped a bare `lineage:` key (YAML
  parses that to `null`, the schema says `type: object`) that the green gate accepted for months.
  Compile the spec (ajv 8, `ajv/dist/2020.js`, `strict: true` so an unhandled keyword **throws**
  instead of being skipped) and derive the constant lists from it. Where a friendlier message is
  worth keeping a hand-written rule, keep it — but assert in a test that the two verdicts *agree*
  case-by-case, or you have just re-created the drift with extra steps. Same footgun as the CI-probe
  one: the checker could not express the failure, so it reported success.
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
