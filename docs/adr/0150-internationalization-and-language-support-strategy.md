# ADR-0150 — Internationalization and language-support strategy

Date: 2026-07-02
Decision-Status: Accepted   <!-- Proposed | Accepted | Superseded by ADR-NNNN | Deprecated | Rejected -->
Delivery-Status: Planned    <!-- Planned | Partial | Shipped | N/A — decision-only -->
Author(s): jiri.raska

## Context

`openbank-admin-ui` is bilingual (Czech/English) via a custom `useLanguage()`
hook and a `t('Česky', 'English')` call-site pattern, enforced by a CI guard
test that scans every `page.tsx` for un-wrapped strings. This works well at
exactly two locales, but it does not scale to a third without a rewrite:
every call site is a hardcoded pair, not a lookup against a locale-keyed
catalog. No ADR has decided whether OpenBank ever needs a third language —
customer-facing notification templates, statement PDFs (ADR-0035), and the
`customer-copilot` (ADR-0089, which itself flags "Czech-language quality of
self-hosted models" as its headline risk) all currently assume Czech is the
customer's language without that being a recorded decision either.

Left undecided, the risk runs in both directions: someone eventually
"upgrades" the admin UI to `i18next`/ICU catalogs for no concrete reason
(churn without a customer need), or a real third-language requirement shows
up (e.g. English-only branch, a non-Czech-speaking operator) and there is no
plan for how the current pattern would need to change.

## Decision

We will declare Czech and English as the supported locale set for the
current phase, and treat `t('Česky', 'English')` as an intentional design
choice — not tech debt — for as long as the locale count stays at two: it
is the cheapest correct implementation of a two-locale system, and the
existing CI guard already prevents the alternative failure mode
(un-translated strings). We define the concrete trigger for migration: the
moment a third locale is required for any customer-facing or admin surface,
the admin-ui `t()` call sites migrate to an ICU-message-format catalog
keyed by locale, in the same PR that adds the third language (not before).
Customer-facing backend text (notifications, statement templates per
ADR-0035, dispute/complaint correspondence per ADR-0117) is locale-derived
from the party's declared preference at the point of generation, defaulting
to Czech, with English as the only other currently-supported value —
mirroring the admin-ui decision rather than diverging from it.

## Alternatives considered

- **Migrate to `i18next`/ICU catalogs now, pre-emptively.** Rejected — no
  concrete third-language requirement exists today; this would be exactly
  the kind of premature abstraction the platform's own engineering
  conventions warn against, trading a working, guarded two-locale pattern
  for generic infrastructure serving a hypothetical need.
- **Leave the locale question fully undecided (status quo).** Rejected —
  this is the gap the review surfaced: without a recorded decision, the
  current pattern reads as an oversight rather than a choice, and there is
  no documented trigger for when to revisit it.
- **Support English as the default/primary language instead of Czech.**
  Rejected — the regulatory profile (ČOBS, ČNB, AnaCredit, Czech AML law)
  and the primary customer base are Czech; English is the secondary
  language for both admin operators and any non-Czech-speaking customers,
  consistent with how the platform already treats it.

## Consequences

**Positive**
- Converts an implicit assumption into an explicit, reviewable decision;
  the existing admin-ui pattern stops being an unstated risk and becomes a
  documented choice with a stated expiry condition.
- Gives `customer-copilot` (ADR-0089) and any future customer-facing text
  generation a clear locale contract to build against, rather than an
  assumption to infer from code.

**Negative**
- If a third language does become a real requirement, the migration cost
  (rewriting every `t()` call site) is paid all at once rather than
  amortized — this is a known, accepted trade-off for keeping the current
  pattern simple while it serves exactly two locales.

**Neutral**
- Does not affect the ISO 20022 message layer (ADR-0104) or other
  machine-readable interchange formats, which are not natural-language
  surfaces.

## Compliance impact

- PCI DSS: not applicable.
- DORA: not applicable.
- GDPR: not applicable directly (data-subject communication language is a
  fairness/transparency consideration under Art. 12 "concise, transparent,
  intelligible" — satisfied by defaulting to the customer's own language).
- PSD2: not applicable directly.
- CNB: not applicable directly — consumer-protection expectation that
  contractual/statement documentation be available in Czech is already met
  by the declared default.

## References

- ADR-0035 (multi-currency account statements) — a locale-dependent
  customer-facing document surface.
- ADR-0089 (customer-facing AI assistant) — flags Czech-language model
  quality as its headline risk; this ADR gives that risk a locale contract
  to be measured against.
- ADR-0117 (dispute and complaint lifecycle) — another locale-dependent
  customer-correspondence surface.
- ADR-0076 (admin-ui integration and e2e testing) — the existing bilingual
  guard-test pattern this ADR ratifies.
