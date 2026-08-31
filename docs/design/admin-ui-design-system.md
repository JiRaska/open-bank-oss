# Admin UI design system

## Purpose

OpenBank Admin UI is a dense, bilingual operator console. Its visual language must help a bank
operator distinguish a fact, an in-flight action and a risk in a single scan. Tokens and shared
primitives are therefore product infrastructure, not decorative CSS.

`openbank-admin-ui/src/app/globals.css` is the token source of truth. It contains the light
`:root` values and the matching `.dark` values. A theme is enabled only by adding `dark` to the
document root; there is no per-page palette.

## Token rules

| Concept | Use | Do not use |
| --- | --- | --- |
| `--surface*`, `--border*` | page, card and grouped-control surfaces | raw white/grey literals |
| `--text-primary/secondary/tertiary` | hierarchy of operator-facing text | `--text-muted` in new code; it is a temporary alias |
| `--accent*` | navigation, primary action and selected state | `--ob-accent*` in new code; they are compatibility aliases |
| `--success/warning/danger/info-*` | status background, border and text | mapping a domain status directly to a colour |
| `--space-*`, `--text-*`, `--font-*` | primitive layout and type | a new competing literal scale |
| `--motion-*`, `--ease-standard` | transitions | a new arbitrary transition duration |
| `--z-*` | overlays and sticky UI | ad-hoc z-index values |

Text tokens and status text tokens are protected by
`src/test/ui-token-contrast.guard.test.ts`: AA (4.5:1) is required over their actual token
surfaces in light and dark themes. The visual companion is `e2e/ui-primitives.spec.ts`.

## Components and contracts

Use `PageHeader`, `StatCard` and `StatusBadge` from `@/components/ui` today. `StatusBadge`
maps `status -> Tone -> CSS class`; unknown values are neutral, never green. A domain may pass an
explicit `tone` only when the same word has a documented different meaning (for example PID
`REVOKED`). Components must accept caller-supplied Czech and English copy, expose native focus and
disabled/loading state, and keep icons decorative unless they are the sole accessible label.

The next primitive tranches, derived from repeated live page shapes, are: Button/FormField,
Tabs, Pagination/FilterBar, dense Table, Card, modal/drawer, EmptyState and tooltip. Each needs
default, hover, focus-visible, disabled, loading, error and empty-state evidence before adoption.

## Mechanical migration map

| Existing literal family | Replacement | Exception |
| --- | --- | --- |
| `#6366f1`, `#4f46e5`, `#eef2ff`, `#c7d2fe`, `#4338ca` | matching `--accent*` token | chart series may retain an explicit, documented data-series token |
| `#16a34a`, `#dcfce7`, `#86efac` | `--success-text/bg/border` | never use success for an unrecognised status |
| `#d97706`, `#fef9c3`, `#fde047` | `--warning-text/bg/border` | use only for attention, not failure |
| `#dc2626`, `#fee2e2`, `#fca5a5` | `--danger-text/bg/border` | use only for blocking/failure/security concern |
| `#2563eb`, `#dbeafe` | `--info-text/bg` | informational, non-terminal state only |
| greys/white | matching surface, border or text token | data visualisations need a named series token before migration |

The raw-colour and local-status-map ratchets are shrink-only. A new literal or page-local status
map is a CI failure; a literal that cannot be mapped must be recorded with its data-visualisation
reason before it is kept.

## Migration order

1. Operator money and risk surfaces: Payments, FinOps, sanctions, day-end, FX.
2. System-control surfaces: IAOps, temporal, system tests, DevOps, observability.
3. Documentation and educational pages, then remaining low-density pages.

Each PR is a single workflow or primitive change; it preserves BFF requests, RBAC and
maker-checker behaviour, adds focused guard coverage, passes real-browser visual checks where
geometry changes, and is merged only after green CI plus independent review.

## Dark theme decision

Dark mode is supported at the token layer now because the app already declared class-based dark
mode. It is intentionally not enabled by a user preference control until the raw-colour ratchet
has reduced legacy surfaces enough for a coherent whole-console experience. This avoids offering
operators a half-themed environment while preserving a testable, accessible implementation path.
