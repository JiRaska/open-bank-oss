---
date: 2026-07-26
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [governance, architecture, ml, analytics]
summary: "Sequences ADR-0199/0200/0201: extract the 14×-duplicated Temporal wiring into libs first, ship crm-service then campaign-service with deterministic segments only, and do not start the NBA model until ADR-0140 phase 2 exists."
---

# ADR-0209 — CRM and campaign sequencing: prerequisites, first slice, and what must not start yet

## Context

ADR-0199 (Customer 360 read model in a new `crm-service`), ADR-0200 (campaign journeys as Temporal
workflows) and ADR-0201 (segmentation + next-best-action) are three well-scoped, reuse-conscious
decisions. This ADR does not narrow them — a reading of all three found nothing to cut. It sequences
them, because three facts about the substrate they assume are not what the ADRs assume, and one of
them is named by ADR-0201 itself as a dependency that does not exist.

**Fact 1 — none of it is built.** Verified on `origin/main`, not inferred from delivery-status:
`git grep -lIiE 'campaign|\bcrm\b|next-best-action'` over every `openbank-*/src` and
`openbank-admin-ui` returns **zero files**. There is no `openbank-crm-service`, no
`openbank-campaign-service`, no segment or NBA code, no module in `settings.gradle.kts`, no gitops
component. So the question is not "how do we fix these ADRs" — it is "in what order does someone
start three services' worth of work", which none of the three answers.

**Fact 2 — the Temporal duplication ADR-0200 D1 mentions in passing is worse than it says.** D1 notes
that "Temporal wiring is duplicated per service today (`infrastructure/temporal/TemporalClientProducer.kt`
in each service, no shared libs module), so campaign-service copies that pattern rather than reusing a
library that does not exist." Counted: **14 copies of `TemporalClientProducer.kt`**, and nothing
Temporal-related anywhere under `openbank-libs-domain` or `openbank-libs-runtime`. campaign-service
would be the 15th. That is not a campaign-service problem — it is a fleet problem that campaign-service
happens to be about to make worse.

**Fact 3 — ADR-0201's own stated dependency does not exist.** D4 says plainly that segment
backtesting and model training both need an as-of join, so "ADR-0140's phase-2 offline snapshotter
becomes a dependency of this work rather than a standing intention", and warns that papering over it
by training on the online store "would reintroduce exactly the skew ADR-0140 exists to prevent".
Verified: ADR-0140 **phase 1 exists** — `libs-domain/.../feature/FeatureDefinition.kt`,
`OnlineFeatureStore.kt`, `libs-runtime/.../feature/online/RedisOnlineFeatureStore.kt`. There is **no
offline store, no snapshotter and no as-of join anywhere in the tree**. So ADR-0201 D5 is not merely
unbuilt; it is unbuildable as specified, and the only way to "start" it today is the exact shortcut
its own text forbids.

**Fact 4 — the consent choke point these ADRs depend on is now real.** ADR-0200 D2/D3 route all
delivery through notification-service so the ADR-0198 D4 consent gate cannot be bypassed. As of
ADR-0198/0205/0206 and the notification gate, that path exists: consent-service owns marketing consent
as per-channel scopes under a fixed grantee, party-service forwards rather than writing its own
column, and notification-service fails closed on marketing rather than defaulting to send. This is the
one prerequisite ADR-0200 assumed that is already satisfied — worth recording, because it means the
sequencing below starts from a consent-safe delivery path rather than needing to build one.

**Fact 5 — a read-only copilot slice needs no new service.** `openbank-mcp-service` exists with a
working `McpToolRegistry` and five registered tools, one of which is already `list_consents`. ADR-0203's
`campaign-copilot` is described as read-only with a human disposition. So the cheapest useful thing in
this whole area is a tool registration, not a service.

## Decision

**D1 — Extract the Temporal client wiring into `openbank-libs-runtime` before campaign-service is
started.** One `TemporalClientProducer` (plus its config contract and worker-registration helper) in
libs, and the 14 existing copies migrate to it. This is sequenced first because it is the only item
here whose value does not depend on CRM shipping at all: it pays back across 14 services immediately,
it is mechanical enough to be verified by "every service still builds and its workflow tests pass",
and doing it *after* campaign-service means migrating 15 copies instead of 14 while a new service is
still in flux.

**D2 — Then ADR-0199's `crm-service`, unchanged.** Its shape is already correct — a projection that
owns no facts, with ADR-0118 erasure by direct anonymisation on `PARTY_ERASED` rather than replay
(D4's corrected mechanism, which is right: every topic is `retention.ms: 604800000` with
`cleanup.policy: delete`, so a rebuild is impossible for any customer older than a week). Nothing in
this ADR changes it.

**D3 — Then ADR-0200's `campaign-service`, with deterministic segments only.** ADR-0201 D2 already
establishes that a rule-based segment is the floor and "means ADR-0200 can ship with no model at all".
This ADR makes that the *required* first shape rather than an available option: campaign-service ships
with rule-based segments, both consent mechanisms (pull before every send, push on
`consent.revoked`), delivery via notification-service, catalogue templates only, and
`campaign.activate` four-eyes. No model, no NBA, no feature-store dependency.

**D4 — ADR-0201 D5 (the NBA model) must not start until ADR-0140 phase 2 is built and merged.** This
is the load-bearing constraint of this ADR. Not a preference: without an offline store there is no
as-of join, and without an as-of join the only way to train is on the online store, which is the skew
ADR-0140 exists to prevent and which ADR-0201 D4 explicitly refuses. ADR-0140 phase 2 therefore
becomes its own tracked work item with its own issue, sequenced before any NBA work, and the NBA
port's type boundary (ADR-0201 D5's structural bar against credit decisions) is designed at that point
rather than retrofitted.

**D5 — The campaign-copilot slice is an MCP tool registration, not a service.** Register read-only
tools against the existing `McpToolRegistry` (which already carries `list_consents`), governed by the
existing ADR-0195 consent binding and per-call audit. This delivers the "copilot" value in ADR-0203
without waiting on D2 or D3, and it is explicitly barred from acquiring a write tool: a copilot that
can enrol or send is a campaign-service concern gated by D3's four-eyes, not a tool.

**D6 — Nothing here authorises starting D2 or D3.** This ADR sequences work; it does not schedule it.
Three services' worth of build is a business decision about spend, and each of D1–D5 is separately
startable, so the correct default is that D1 and D5 (both cheap, both fleet-useful on their own) may
proceed and D2/D3 wait for an explicit decision to fund them.

## Alternatives considered

- **Narrow ADR-0199/0200/0201.** This was the original intent and it was dropped after reading them:
  they are already reuse-first (a projection that owns no facts, Temporal instead of a scheduler,
  notification-service instead of per-service delivery adapters, the existing template catalogue,
  the existing four-eyes store, the existing feature catalogue). Cutting scope would have removed
  correctness, not fat. The real gap was ordering and unmet prerequisites, so that is what this ADR
  addresses.
- **Supersede all three with one smaller ADR.** Rejected: it would discard three carefully-argued
  decision records and their alternatives sections, and it would hide rather than resolve the ADR-0140
  dependency — the thing most likely to be quietly skipped.
- **Build campaign-service first (it is the visible business value).** Rejected: it makes the Temporal
  duplication a 15th copy, and it invites shipping the model path early, which D4 exists to prevent.
- **Do the Temporal extraction later, as cleanup.** Rejected on arithmetic: 14 copies migrated once is
  cheaper than 15, and "later" for a duplication this size historically means never.
- **Start ADR-0201 with the online store and fix the skew afterwards.** Rejected because ADR-0201's
  own D4 rejects it, and because training/serving skew is not the kind of defect that announces
  itself — it shows up as a model that validated well and performs worse in production, which is
  indistinguishable from ordinary model drift.

## Consequences

**Positive**
- D1 pays back on 14 services regardless of whether CRM is ever funded, so the first step is
  useful even if the programme stops after it.
- D5 delivers the copilot slice for the cost of a tool registration, against infrastructure that
  already enforces consent and audit per call.
- The ADR-0140 phase 2 dependency gets an owner and an order instead of remaining a sentence inside
  ADR-0201 that a future implementer can read past.
- Campaign delivery starts from an already-consent-safe path (Fact 4), so the gate is not being
  built under campaign-shaped deadline pressure.

**Negative**
- D1 touches 14 services for no user-visible change. That is a hard PR to prioritise and an easy one
  to leave half-done; if it stalls midway the fleet carries two Temporal wiring conventions, which is
  worse than either. It should be one sweep with a per-service build check, or not started.
- D4 delays the most commercially interesting part (NBA) behind unglamorous platform work, and that
  ordering will be under pressure. The mitigation is that it is written down here as a constraint with
  a stated reason, not as a preference.
- Sequencing does not reduce total cost. Three services remain three services; this ADR only stops
  the order from making them more expensive.

**Neutral**
- No decision in ADR-0199/0200/0201 is changed, reversed or narrowed. This ADR adds ordering and
  records two verified substrate facts those ADRs assumed differently.

## Compliance impact

- PCI DSS: not applicable — no cardholder-data path.
- DORA:    not applicable at this stage; D1 is an internal refactor with no third-party or resilience
  change. A funded D2/D3 would carry their own assessment.
- GDPR:    Art. 6(1)(a) and Art. 7(3) are already handled by the consent chain recorded in Fact 4;
  this ADR adds no processing. ADR-0199 D4's erasure mechanism (Art. 17) is unchanged.
- PSD2:    not applicable.
- CNB:     not applicable — no regulatory reporting surface.
- EU AI Act: ADR-0201 D5's bar against credit outcomes is unchanged and is why D4 defers the model
  rather than accelerating it; a model built without the as-of join would be harder to defend on
  data-governance grounds, not just less accurate.

## References

- ADR-0199 / ADR-0200 / ADR-0201 (the three decisions this ADR sequences, none of them modified)
- ADR-0140 (feature store; phase 1 shipped, phase 2 is D4's blocking dependency)
- ADR-0139 (deterministic rule floor — the precedent ADR-0201 D2 follows)
- ADR-0198 / ADR-0205 / ADR-0206 (the consent choke point recorded in Fact 4)
- ADR-0195 (MCP consent binding and per-call validation, which D5 reuses)
- ADR-0203 (business-plane agents, incl. the campaign-copilot D5 delivers as a tool)
- ADR-0118 (erasure pattern ADR-0199 D4 follows)
- ADR-0176 (template catalogue and four-eyes store ADR-0200 D4/D5 reuse)
