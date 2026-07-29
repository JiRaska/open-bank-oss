---
date: 2026-07-29
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [admin-ui, notifications, governance]
summary: "Campaign authoring lives in the admin-ui as a wizard that composes versioned segments and catalogue templates, shows the consent funnel live, and submits to the existing four-eyes queue — free-text copy and ad-hoc SQL stay forbidden."
---

# ADR-0221 — Campaign Studio: the campaign authoring operator experience

## Context

ADR-0200 decides the campaign engine and closes with an explicit non-decision: *"Where campaigns are
authored — an admin-ui section versus its own console — is not decided here."* This ADR is that
decision, and it matters more than its size suggests: for a bank, the authoring surface *is* the
capability. If creating a campaign needs a Jira ticket to engineering, ADR-0200 ships a workflow
engine nobody runs; if authoring is a free-for-all, every discipline the estate just built (ADR-0176's
catalogue, ADR-0201's versioned segments, ADR-0198's consent, ADR-0219's contact policy) is one
convenient UI shortcut away from being bypassed by the people it protects against.

The constraints the Studio must express — not re-decide — are already fixed:

- **Content is a catalogue.** ADR-0176 D4 / ADR-0200 D4: a campaign composes existing templates with
  declared variables. A rich-text editor with an HTML mode is the phishing bypass those ADRs refuse.
- **Segments are versioned artifacts.** ADR-0201 D1: no free-form SQL from a UI; the previewed cohort
  and the sent cohort are the same version or provably different.
- **Activation is four-eyes.** ADR-0200 D5 reuses the ADR-0176 mechanism (`campaign.activate`,
  self-approval guard). The Studio submits; it never activates.
- **Assistance exists and is bounded.** ADR-0203 D4's campaign-copilot proposes segments and maps
  catalogue templates to steps; it never authors message text. The Studio is where that agent lives.
- **The admin-ui has its own consolidation direction.** ADR-0208 (primitive layer, one status
  vocabulary) — the Studio builds on those primitives, not beside them.

Forces beyond the citations: marketing operators are not engineers (the wizard must make the lawful
path the easiest path), compliance reviewers need everything for a decision on one screen (they
already work in the approvals queue — ADR-0176/0200 D5 — so campaign review must arrive *there*, not
in a new inbox), and every audience number an operator sees must be explainable (the consent funnel —
total → after consent → after cap → after suppression — or the first campaign produces a "why did it
send to fewer people" incident review).

## Decision

We will build **Campaign Studio as a section of the admin-ui**, on the ADR-0208 primitive layer — not
a separate console — structured as a guided wizard that can only produce ADR-0200-valid campaigns.

**D1 — The wizard is the domain model, rendered.** Six steps, each mapping to an engine concept:
(1) **Goal** — objective and success metric; the campaign-copilot (ADR-0203 D4) can pre-fill from a
plain-language brief, badged as an AI draft requiring acceptance. (2) **Audience** — a picker over
*versioned* segment artifacts (ADR-0201 D1) with a live count and the **consent funnel** (total →
after ADR-0198 consent → after ADR-0219 caps → after suppression) computed from the ADR-0210 silver
query; new segments are proposed as code (a PR authored by the copilot, reviewed like any code) —
never typed as SQL into the UI. (3) **Steps** — composition from the template catalogue with declared
variables; channel per step (EMAIL first, per ADR-0200 D7); there is no free-text field anywhere in
this step. (4) **Contact rules** — the ADR-0219 caps, quiet period and suppression shown read-only;
changing them is a platform-admin action outside the wizard. (5) **Schedule** — one-shot or journey
skeleton (the Temporal shape ADR-0200 D1 executes). (6) **Summary** — estimated reach, the funnel
snapshot, and submit to the existing approvals queue.

**D2 — Review happens where reviewers already work.** Submission creates a `campaign.activate`
approval in the existing four-eyes flow (ADR-0200 D5) with a one-screen review bundle: definition,
funnel snapshot, step/template references with variables, the copilot's pre-check notes if used, and
the submitter identity (the self-approval guard makes maker ≠ checker structural, per ADR-0176).

**D3 — Live campaigns are observable in the same section.** A dashboard per campaign built from
notification terminal statuses and ADR-0220's `engagement.events`: delivered / opened / clicked /
converted per step and variant, journey state from Temporal, and a pause control that maps to the
engine's stop mechanism. No metrics pipeline of its own — the dashboard is a consumer of streams that
already exist once ADR-0220 lands; before it lands, delivery metrics alone suffice.

**D4 — Roles follow the estate's maker/checker culture.** `campaign-maker` (create/edit drafts),
`campaign-approver` (the four-eyes checker), `campaign-admin` (contact-policy configuration,
suppression administration per ADR-0219 D3), `campaign-auditor` (read-all). Enforced by the existing
OPA sidecar (ADR-0034), exactly as the engine enforces `campaign.activate` — the UI renders
capability, the policy decides it.

**D5 — Explicit non-goals, matching the estate's discipline.** No drag-and-drop journey canvas (a
40-node canvas is where campaign tools go to die; the wizard's step list covers the honest use
cases). No WYSIWYG/HTML editor (ADR-0176 D4). No CSV audience upload or export (ADR-0201's
reproducibility; an export is a separate, audit-logged action with a reason code, not a button on a
list). No per-campaign overrides of platform contact policy (ADR-0219 D4's single enforcement point
would be decorative if a wizard could route around it).

## Alternatives considered

- **A standalone marketing console (separate app, separate auth).** What commercial tools ship.
  Rejected: a second operator surface duplicates the admin-ui's auth, audit and ADR-0208 primitives,
  and splits the reviewer's attention across two inboxes for no capability gain.
- **Author campaigns as code only (YAML in git, no UI).** Maximally auditable, and the estate's
  instinct. Rejected as the only option: ADR-0200 exists because operators asked for a tool, and an
  engineer-in-the-loop per campaign recreates the bottleneck the engine removed — code authoring
  remains possible (a campaign definition *is* data the engine reads), it is just not the operator
  path.
- **Let the wizard draft message text with the LLM, reviewed before send.** The obvious AI feature.
  Rejected without hesitation: ADR-0200 D4 names catalogue discipline "the single most important
  thing not to relax", and a reviewed-by-a-human LLM textbox is that relaxation wearing a workflow
  hat. The copilot's role stays segment proposal and template mapping (ADR-0203 D4).

## Consequences

**Positive**
- The lawful path becomes the easiest path: consent coverage, caps and suppression are *shown* to the
  operator at authoring time, which is cheaper than enforcing after the fact and teaches the consent
  asset's value on every campaign.
- Compliance review cost drops: one bundle, one queue, no archaeology — the ADR-0176 mechanism reused
  rather than reinvented.
- ADR-0200/0201/0211 get an operator-visible surface that makes their disciplines tangible, which is
  what keeps them from being routed around politically.

**Negative**
- A substantial admin-ui build on top of engine work that is itself sequenced behind ADR-0209's
  prerequisites — the Studio is the last slice, and pressure to ship a thin version first (with "just
  a small text field") is the predictable failure mode; D5 exists to refuse it.
- The consent funnel depends on the ADR-0210 silver query's freshness; a stale funnel teaches wrong
  expectations — the UI must show the as-of timestamp.

**Neutral**
- Nothing here relaxes or extends any engine decision; this ADR is a rendering of ADR-0198/0200/0201/
  0203/0211 into operator UX, and any future conflict is resolved in favour of those ADRs.

## Compliance impact

- PCI DSS: not applicable — the Studio handles definitions, templates and counts, no cardholder data.
- DORA: not applicable — an operator UI over existing ICT services; no new third party.
- GDPR: Art. 25 — data protection by design: counts-only audience preview, consent funnel visible at
  design time, no ad-hoc export; Art. 7/21 surface through the ADR-0219 rules the wizard displays but
  cannot override.
- PSD2: not applicable.
- CNB: Act No. 480/2004 Coll. exposure is unchanged from ADR-0200 — the Studio is the authoring
  surface, not a sender.

## References

- [ADR-0200](0200-campaign-journeys-as-temporal-workflows-with-consent-gated-delivery.md) — the
  engine this authors for; its undecided authoring question answered here; D5's four-eyes mechanism
  reused in D2.
- [ADR-0201](0201-customer-segmentation-and-next-best-action-on-the-ml-decisioning-platform.md) —
  segments as versioned artifacts; the no-SQL constraint on D1.
- [ADR-0176](0176-operator-initiated-customer-messaging.md) — the catalogue discipline and the
  approvals mechanism; [ADR-0203](0203-business-plane-ai-agents.md) — the campaign-copilot's bounded
  role in D1.
- [ADR-0219](0219-platform-contact-policy-gate-contact-classes-durable-counters-suppression.md) —
  the contact rules the wizard shows and cannot override.
- [ADR-0210](0210-customer-360-as-a-query-over-the-analytics-silver-layer.md) — the funnel's data
  source; [ADR-0208](0208-admin-ui-consolidation-a-primitive-layer-one-status-vocabulary-and-an-interactive-flow-explainer.md)
  — the primitives the Studio builds on.
- [ADR-0220](0220-in-app-engagement-surfaces-gamification-and-pre-approved-offers.md) — the metrics
  stream D3 consumes.
