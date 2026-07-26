---
date: 2026-07-26
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [admin-ui, architecture, governance]
summary: "Admin UI gets a components/ui layer on the already-declared Radix+CVA, one status vocabulary replacing 35 per-page colour maps, deduplicated layouts, one data path, and reuses the existing BPMN/FlowParticle renderers for explainers."
---

# ADR-0208 — Admin UI consolidation: a primitive layer, one status vocabulary, and an interactive flow explainer

## Context

`openbank-admin-ui` has grown to **86 pages / 30 202 lines in `page.tsx` alone**, plus 66
`app/api/**/route.ts` handlers and 41 layouts. It works and ships: every page mounts
(`render-smoke.test.tsx`'s `CANNOT_MOUNT` set is empty), every route resolves a shell, and
conventional debt markers are essentially absent (1 `@ts-ignore`, 4 real TODOs). The problem
is not neglect — it is that there is no shared vocabulary, so the marginal cost of a page
never falls and every visual inconsistency is structural rather than accidental.

Measured directly on `origin/main`, 2026-07-26:

- **`src/components/ui/` does not exist.** 32 files under `src/components/`, all
  domain-specific. There is no shared Table, PageHeader, StatusBadge, Card, Modal or Form
  field; every page hand-rolls `<table>` markup. `payments/page.tsx` is 934 lines and imports
  **zero** shared components.
- **35 of 86 pages carry their own `Record<string, string>` style map**, and **46 pages
  contain at least one raw hex literal** — **1 133 hex literals** across `page.tsx` files. The
  same semantic triple is re-typed per page: `#dc2626` ×112, `#d97706` ×92, `#16a34a` ×89.
  Meanwhile `globals.css` already defines `.badge-success` / `-danger` / `-warning` / `-info`
  / `-neutral` and `.status-dot-*` — a 421-line design layer that pages bypass.
- **22 of 41 `layout.tsx` files are two byte-identical files**, copied 11 times each
  (verified by checksum).
- **Eight UI dependency families are declared and entirely unused**: 8 `@radix-ui/*`
  packages, `react-hook-form`, `@hookform/resolvers`, `class-variance-authority`, `date-fns`
  — **zero references anywhere in `src/`**. A design system was intended and never built, so
  the repo already pays the dependency, bundle-audit and Dependabot cost for nothing.
- **69 of 86 pages are `'use client'`**, and ~50 fetch with bare `fetch()` inside
  `useEffect`. `src/lib/api.ts` exists but only 8 files import it, and it hardcodes a
  34-entry service→port table duplicating the BFF's own routing.

Two things are explicitly *not* claimed as discoveries. Test coverage is already measured and
documented: `vitest.config.ts` records that without the mount-only smoke suite the same run
measures 7.03 % statements, and says so in a comment. And `openbank-admin-ui/CLAUDE.md`
already carries six numbered UI rules, each written as a past bug and each backed by a
source-text guard — this ADR extends that established mechanism rather than inventing one.

There is also **a capability already present and nearly unused**. The tree contains 17 YAML
flow definitions (`src/content/bpmn/*.yaml` plus `src/content/processes/auth-flow.yaml`)
rendered by `components/docs/BpmnView.tsx` / `ProcessView.tsx` against a schema in
`lib/docs/bpmn/schema.ts`, and `components/topology/FlowParticle.tsx` +
`useFlowAnimation.ts` — SVG particle animation over `requestAnimationFrame`, live on
`infrastructure/topology`. `mermaid` 11.16 is wired for docs. `framer-motion`, `react-flow`,
`d3` and GSAP are all **absent**, and adding them is unnecessary: the machinery for an
animated, educational "how does this actually work" view is already built and paid for.

## Decision

Five decisions, each independently shippable, each backed by a guard test in the style
`openbank-admin-ui/CLAUDE.md` establishes.

**D1 — Build `src/components/ui/` on the dependencies already declared.** Table, PageHeader,
StatusBadge, Card, EmptyState, Modal, Form field. Use the **existing** `@radix-ui/*` +
`class-variance-authority` + `clsx`/`tailwind-merge` (`lib/utils.ts` already exports `cn()`)
rather than hand-rolling or adding a library. This converts dead dependency families into
load-bearing ones; any still unused when D1 lands are **deleted** from `package.json`, not
left declared.

**D2 — One status vocabulary.** A single `statusTone(value): Tone` helper plus the existing
`globals.css` badge/dot classes become the only way a page expresses status, severity or
health. A guard test asserts no file under `src/app/**/page.tsx` contains a raw hex literal.
The guard ships **with** the first migration tranche, scoped to migrated paths — a guard that
fails on 46 pages on day one gets disabled rather than obeyed.

**D3 — Deduplicate the layouts.** Collapse the 22 byte-identical layouts into one shared
shell, keeping a per-route file only where it genuinely differs. Extend the existing
`layout-shell.guard` test — which exists because FinOps/DevOps/observability once shipped a
`page.tsx` with no `layout.tsx` and rendered bare — to also fail on a re-declared identical
shell.

**D4 — One data-access path.** Every page reaches backends through the BFF
(`app/api/svc/[service]/[...path]`, ADR-0056) via a typed client in `lib/api.ts`; the
hardcoded 34-entry port table is deleted in favour of the BFF's own service resolution. Pages
stop calling bare `fetch()` in `useEffect`. We do **not** introduce a state manager or a
data-fetching library here — that is a separate decision, and the win available now is one
path, not a new abstraction.

**D5 — Flow explainers reuse the BPMN + FlowParticle machinery.** A flow worth explaining is
authored as a **YAML file under `src/content/`**, never as a bespoke page, and rendered by the
existing `BpmnView`/`ProcessView` + `FlowParticle` components. The first is the
marketing-consent path (ADR-0198 / ADR-0205 / ADR-0206): mobile app → customer-edge →
party-service forwarder → consent-service → Kafka → the party-service projection, with the
OPA grantee gate and the single-writer invariant called out. No animation library is added.

Migration is **tranche-by-tranche, not a rewrite**. Each tranche moves a set of pages onto the
primitives and enables the corresponding guard for exactly those paths. A tranche that cannot
be finished is reverted rather than left half-migrated, so the tree never carries two
competing conventions indefinitely.

## Alternatives considered

- **Add `framer-motion` + `react-flow` for the explainer.** Rejected: the BPMN YAML +
  `BpmnView` + `FlowParticle` stack already renders animated flow diagrams from declarative
  input and is live on `infrastructure/topology`. Two new libraries to do what exists would
  grow the bundle and supply-chain surface *and* create a second way to express a flow — the
  duplication this ADR exists to remove.
- **Adopt a full third-party design system.** MUI/Mantine rejected: they replace the 421-line
  `globals.css` layer and the Tailwind setup wholesale, which is a rewrite of 86 pages rather
  than a consolidation. shadcn/ui is in substance what D1 describes (Radix + CVA + Tailwind,
  vendored) — so it is adopted in substance without the generator, not rejected.
- **Big-bang rewrite of all 86 pages.** Rejected: 30 k lines with 7 % behavioural coverage is
  exactly the case where a big-bang silently changes behaviour nothing catches. Tranches with
  per-tranche guards keep each step falsifiable.
- **Do nothing; the pages work.** Rejected on cost, not aesthetics. With no primitives every
  new page re-derives its table, its status colours and its fetch, so the marginal cost never
  falls — and the consent-management page ADR-0198 still needs would be the 87th instance.
- **Raise test coverage first.** Rejected as a sequencing error: behavioural tests written
  against 86 bespoke implementations get rewritten by the consolidation. Coverage is worth
  raising *after* there is a primitive layer whose behaviour is worth asserting once.

## Consequences

**Positive**
- Marginal cost of a page falls: the consent-management surface ADR-0198 needs — and any
  CRM/campaign surface later — composes primitives instead of re-deriving them.
- Eight declared-but-dead dependency families become either load-bearing or removed. Either
  outcome beats the status quo, where they cost and provide nothing.
- Status presentation becomes reviewable: one helper and one CSS vocabulary instead of 35
  local maps and 1 133 hex literals.
- The animated explainer costs a YAML file, not a new rendering stack.

**Negative**
- Touching 86 pages against 7 % behavioural coverage is genuinely risky. Tranches, the
  mount-smoke suite and per-tranche guards bound it; they do not remove it. Expect visual
  regressions only a human notices.
- The guards are source-text checks over source text, a shape this repo has already been bitten
  by — a guard flagging the very prose that explains the rule it enforces. Each needs an
  explicit rule for code-about-code (strip comments) and must be run against a case it MUST
  NOT flag before it is trusted.
- A partially-migrated tree carries two conventions. The revert-an-incomplete-tranche rule is
  what bounds that window, and only if it is actually honoured.

**Neutral**
- No new runtime dependency is introduced by D1–D5; the net direction is fewer.
- Auth, the BFF proxy contract, the CSP nonce middleware (ADR-0080) and the bilingual
  requirement are unchanged — D1's primitives must satisfy the existing i18n guard like any
  other component.

## Compliance impact

- PCI DSS: not applicable — no cardholder-data path changes.
- DORA:    not applicable — no ICT third-party or resilience change, and no new runtime dependency.
- GDPR:    enabling only. The consent-management surface ADR-0198 needs does not exist in
  admin-ui today; D1 is what makes it cheap to build correctly. This ADR processes no personal
  data and changes no lawful basis.
- PSD2:    not applicable.
- CNB:     not applicable — operator-console ergonomics; no regulatory reporting surface changes.

## References

- ADR-0056 (admin-ui reaches services through a BFF, not per-service calls)
- ADR-0080 (per-request nonce CSP — constrains what D1's primitives may inline)
- ADR-0198 / ADR-0205 / ADR-0206 (the marketing-consent chain D5's first explainer describes)
- `openbank-admin-ui/CLAUDE.md` — the six existing guard-backed UI rules this ADR extends
- issue #2370 (compliance page's hand-maintained claims with no gate — the same
  duplication-without-a-single-source pattern, expressed in prose rather than markup)
