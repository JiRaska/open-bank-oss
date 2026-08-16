---
date: 2026-08-16
decision-status: accepted
delivery-status: shipped
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [notifications, testing, architecture, compliance]
summary: "An explicit decision-graph journey runs as a second Temporal workflow type and task queue, not a Workflow.getVersion gate in the linear workflow, so its reshaped command sequence never breaks or burdens the frozen legacy binary."
---

# ADR-0260 — Versioned Decision-Graph Campaign Journeys via Dual Temporal Workflow Types

## Context

ADR-0200 D1 made a campaign enrolment a per-party `CampaignJourneyWorkflow` instance that walks a
linear, ordered list of steps. Issue #4680 asked for more than that: explicit decision nodes with
true/false path edges, so a marketer can route a party down two different sequences from the
Studio UI rather than relying on the order-based `StepCondition` skip mechanism #3585 had already
added. The issue's own acceptance criteria included a hard constraint most feature requests do not
carry: *"Version Temporal execution so existing histories and linear campaign definitions replay
unchanged."* That sentence is the entire reason this ADR exists — the domain shape (decision
nodes, forward-only edges, a durable per-party path record) is the easy 20%; making the change
land without breaking a live, in-flight Temporal execution is the rest.

PR #4781 ("add explicit decision graph journeys") shipped the domain model, the persistence, the
Studio UI and the workflow mechanism, and its body said `Closes #4680`. The issue did not
auto-close (GitHub links `Closes #N` to the PR's *default* branch merge; #4680 is tracked on this
platform's issue backlog and the merge event did not trigger GitHub's closing-keyword handler for
it — a backlog-hygiene gap, not a functional one, and not this ADR's concern to fix). What #4781
left genuinely open is rigor, not scope: no ADR documented the mechanism it built, and nothing in
the repository proves — as opposed to argues in a doc comment — that a pre-#4781 Temporal history
replays against the current binary. This ADR documents the mechanism PR #4781 shipped (Decision,
below) and this PR closes that rigor gap (see Delivery note).

**The forcing question is a Temporal-specific one: how do you change a workflow's *command
sequence* — not just add a field, but reshape which activities run in which order for which
executions — without breaking replay for executions Temporal has already recorded history for?**
Temporal's own documentation is explicit that this has two supported answers depending on the
shape of the change, and they are not interchangeable:

- **`Workflow.getVersion` / `Patched`** — a branch point *inside* one workflow type's code, gated
  by a change ID. Every replay, old or new, executes the same function; the version marker just
  tells old history "take the pre-existing branch" and new executions "take the new one". This is
  sound for small, local, additive changes with a single branch point, and this codebase already
  uses it correctly three times in `CampaignJourneyWorkflowSupport` for exactly that shape:
  `CONTROL_STATE_CHANGE_ID` (a new signal-driven pause check), `DECISION_SOURCE_CHANGE_ID` (an
  explicit vs. inferred condition source), `PATH_EXPERIMENT_DELAY_CHANGE_ID` (delay resolved
  through an activity instead of the definition). Each is one `if` inside one function.
- **A new Temporal workflow *type*, on its own task queue** — used when the change is not a branch
  point but a different *shape* of execution: a different loop structure, a different set of
  activities, a different termination condition. Temporal's guidance is that stacking
  `getVersion` gates for a structural change makes the original function permanently carry every
  historical shape it has ever had, indefinitely, because a *live* execution can be paused at any
  version and must still be resumable.

A campaign decision graph is the second shape, not the first. `runLinear` walks
`definition.steps.sortedBy { it.order }` unconditionally; a decision graph instead walks a
`stepOrder -> CampaignDecision` map with two-way branching and no guaranteed pass over every step.
That is a different control-flow shape, not a value threaded through one `if`.

## Decision

We will run explicit decision-graph journeys as a **second Temporal workflow type,
`DecisionJourneyWorkflowImpl`, on its own task queue (`openbank-campaign-decision`)**, sharing
step-execution logic with the original `CampaignJourneyWorkflowImpl` through a common abstract
base (`CampaignJourneyWorkflowSupport`) but never sharing a workflow type or queue with it.

**D1 — One base class, two `@WorkflowInterface` types, two task queues.**
`CampaignJourneyWorkflowSupport` holds every step-execution primitive (`executeStep`,
`deliverWhenReady`, `readyOrTermination`, the three existing `getVersion` gates, all five signal
handlers). Two thin subclasses each implement one interface and delegate `run()` to one of two
entry points on the shared base: `CampaignJourneyWorkflowImpl.run()` calls `runLinear()`;
`DecisionJourneyWorkflowImpl.run()` calls `runDecisionGraph()`. `runLinear`'s own doc comment
states the invariant this ADR is built to protect: *"The original workflow type. Its command
sequence must remain replayable by an older worker."* `runDecisionGraph` requires
`definition.decisions.isNotEmpty()` and executes `executeGraph`: a `while` loop over a
`stepOrder -> CampaignStep` / `stepOrder -> CampaignDecision` map, following `nextStepOrder` for a
plain node and `confirmedStepOrder`/`notConfirmedStepOrder` for a decision node, until a null
`nextStepOrder` terminates the journey (`CampaignJourneyWorkflowImpl.kt`).
`CampaignWorkerRegistrar` registers `CampaignJourneyWorkflowImpl` on the original
`openbank-campaign` queue and `DecisionJourneyWorkflowImpl` on a second worker polling
`openbank-campaign-decision`; both share the same `CampaignJourneyActivitiesImpl` activity
implementation, since the activities themselves (`loadDefinition`, `deliverStep`,
`recordDecisionPath`, …) are shape-agnostic.

**D2 — Routing is by the presence of an explicit graph, not a stored version tag.**
`CampaignService` starts `DecisionJourneyWorkflowImpl` when `campaign.decisions.isNotEmpty()` and
`CampaignJourneyWorkflowImpl` otherwise. This is an implicit discriminator rather than a
first-class `schemaVersion`/`journeyModel` field on the campaign record, and that is a deliberate,
narrow scope decision: today there are exactly two shapes (linear, decision-graph) and the
discriminator that already governs `validateDecisionGraph()`'s legacy/graph mutual-exclusion rule
is the same `decisions.isEmpty()` test, so introducing a second, parallel discriminator field would
create a second source of truth for the same fact with no third shape yet to justify it. Should a
third journey shape be added, an explicit field replacing this inference is the correct next step
— **out of scope for this ADR**, tracked as its own follow-up.

**D3 — The domain model stays a bounded, reviewable graph, not an open DAG.** `CampaignDecision`
carries exactly one predicate kind — was the source step's delivery `CONFIRMED`, from
notification-service's durable delivery-status fact, never an invented open/click/engagement
signal — with `MAX_DECISIONS = 3` decision nodes against `MAX_STEPS = 5` steps.
`validateDecisionGraph()` enforces: forward-only edges (`confirmedStepOrder`/`notConfirmedStepOrder`
/ `nextStepOrder` must all exceed their source order), no mixing of the legacy `StepCondition`
mechanism with an explicit graph in the same campaign, at most one `CampaignDecision` per source
step, and full reachability of every declared step from step 1 by depth-first traversal. A campaign
that fails any of these is rejected at construction, before Studio ever submits it and long before
a workflow starts.

**D4 — The per-party decision trail is a durable, idempotently-written audit record,
not a derived value.** `Enrolment.decisionPath: List<DecisionPathSelection>` records, for every
decision node this party's journey actually crossed: `sourceStepOrder`, the `selected`
`DecisionPath` (`CONFIRMED`/`NOT_CONFIRMED`), the resulting `nextStepOrder`, and `decidedAt`.
`CampaignJourneyActivitiesImpl.recordDecisionPath` writes this by **replacing** any existing entry
for the same `sourceStepOrder` rather than appending — Temporal's at-least-once activity retry
means a redelivered `recordDecisionPath` call must not inflate one decision into two audit rows,
and keying the replace on `sourceStepOrder` (a graph-node identity fixed by the campaign
definition, not a sequence counter) makes the write naturally idempotent.

## Alternatives considered

- **A single `Workflow.getVersion`/`Patched` gate inside `CampaignJourneyWorkflowImpl`, branching
  between the linear loop and a graph loop.** This is the pattern this codebase already uses
  correctly for the three additive changes listed under D1's Context, and it is tempting to reuse
  for consistency. Rejected: those three gates are each one `if` around a single local decision;
  a decision graph replaces the entire iteration strategy (a sorted-order `for` loop with a
  branch-map `while` loop, a different termination condition, a different activity call sequence
  for `recordDecisionPath`/`advanceToStep`). Folding that into one function behind a version check
  means the ORIGINAL workflow function permanently carries both control-flow shapes forever,
  because Temporal must still be able to resume an execution that is paused mid-history at any
  historical version — there is no way to ever delete the pre-version-1 branch while a single
  execution older than it might still be running. Every future structural change would add another
  branch to the same function, and the function that started as a 15-line loop over ordered steps
  would become an ever-growing dispatcher of every journey shape the service has ever supported.
  The two-workflow-type split instead lets the frozen `runLinear` branch stay exactly as small as
  it is today, permanently, and confines all graph-shape evolution to `runDecisionGraph` and any
  workflow type after it.
- **Temporal server-side Worker Build-ID Versioning alone (no new workflow type), routing old and
  new executions to different worker builds by build ID.** Rejected as the wrong layer: Build-ID
  versioning is a *deployment* mechanism — which binary build serves which in-flight execution —
  and does not by itself change what commands a given piece of workflow code emits. It solves "how
  do I roll out a new binary without breaking in-flight work", which this ADR also needs, but it
  does not solve "the command sequence for a decision graph is structurally different from a linear
  walk" — that is a data/control-flow-shape problem, not a binary-selection problem, and conflating
  the two would still require *some* mechanism inside the workflow code to pick a control-flow
  shape once a build serves both kinds of history. The two-workflow-type-plus-task-queue pattern
  used here is compatible with Build-ID versioning being layered on top later (each workflow type
  could independently adopt build-ID pinning); it just does not depend on the namespace having it
  enabled, which was unverified for this environment at the time of writing.
- **An opaque, event-sourced interpreter: store the graph as generic JSON and drive a single
  general-purpose "replay this graph" workflow function over it.** Maximally future-proof — any
  future graph shape needs no new workflow type, ever. Rejected on ADR-0200 D4's own discipline,
  restated here for execution rather than templates: a campaign step is a reviewable, catalogued
  unit precisely because ADR-0200 refused an author-supplied free-form body; an opaque interpreter
  over generic graph JSON reintroduces the same unreviewable-arbitrary-logic risk one layer down, in
  the *execution engine* instead of the *message content*. With a five-step, three-decision bounded
  surface (`MAX_STEPS`, `MAX_DECISIONS`), the concrete two-workflow-type approach is legible in a
  code review in a way a generic interpreter's behavior, for a given campaign, is not — a reviewer
  reads `runDecisionGraph`'s ~50 lines once, not a JSON graph interpreted by code that has to stay
  correct for every graph anyone could ever author.

## Consequences

**Positive**
- The frozen `runLinear` workflow keeps exactly the command sequence it has always had; nothing
  proposed here, or foreseeably after it, requires touching that function again.
- A rollback to a pre-#4781 binary leaves in-flight decision-graph executions queued on
  `openbank-campaign-decision` rather than replayed incorrectly by an old worker that has never
  heard of `DecisionJourneyWorkflowImpl` — the old worker simply does not poll that queue.
- Structural evolution has a clear home: a third journey shape gets a third workflow type and task
  queue, not a fourth branch threaded through two functions that already do two different things.
- The per-party `decisionPath` audit trail, combined with this PR's observed-status snapshot
  (Delivery note), makes "why did this enrolment take this path" answerable from one row.

**Negative**
- Two task queues and two worker registrations is more operational surface than one: a queue
  misconfiguration or a missed worker registration for the decision queue is a class of outage the
  linear-only design did not have (mitigated here by both workers being registered in the same
  `CampaignWorkerRegistrar.onStart`, so one missing entry is one missed line in a five-line diff to
  review, not two independently deployed components that can drift).
- The `decisions.isEmpty()` routing discriminator (D2) does not scale past two journey shapes
  without becoming ambiguous; it is deliberately not fixed here (out of scope, tracked as a
  follow-up) and a reviewer of a future third shape must not silently overload the same inference.
- A `WorkflowReplayer`-based test proving byte-for-byte replay compatibility for a pre-#4781
  history did not exist until this PR (Delivery note) — the invariant `runLinear`'s doc comment
  asserted was, until now, argued rather than demonstrated.

**Neutral**
- Live in-flight execution counts on either task queue, and whether Temporal server-side Worker
  Build-ID Versioning is enabled for this namespace, were not verified as part of this decision;
  neither changes the mechanism decided here, only its current operational exposure.

## Compliance impact

- GDPR: Art. 5(2) accountability and Art. 30 records-of-processing both bear on being able to
  reconstruct, after the fact, why a specific customer received a specific message sequence.
  Before this PR, that reconstruction required joining `Enrolment.decisionPath` (which records
  only the *derived* `CONFIRMED`/`NOT_CONFIRMED` path, not the raw delivery fact it was derived
  from) back to the `send_log` table by `(campaignId, partyId, sourceStepOrder)` to see the actual
  `DeliveryStatus` the decision evaluated. That join is sound today only because `DeliveryStatus`
  transitions are monotonic per ADR-0239 D4 (a status observed later cannot un-happen an earlier
  decision), but it is one avoidable hop from self-contained. This PR adds the observed
  `DeliveryStatus` directly onto `DecisionPathSelection` (Delivery note), so a reviewer or auditor
  reads one row instead of performing — and separately having to justify the safety of — a join.
- DORA: this ADR does not change campaign-service's ICT third-party posture (none) or introduce a
  new external dependency; it documents an execution-mechanism decision entirely internal to the
  fleet's existing Temporal deployment.
- PCI DSS: not applicable — no cardholder data in a campaign definition, a decision predicate, or
  the audit trail; the predicate is restricted by construction to a delivery-confirmation boolean.
- PSD2: not applicable — no account access or payment initiation.
- CNB: not applicable.

## Delivery note

**Phase 0 — already shipped, via PR #4781 (merged 2026-08-15T06:16:59Z).** The mechanism this ADR
documents — the domain model (`CampaignDecision`, `validateDecisionGraph`, `nextStepOrder`,
`DecisionPathSelection`), the dual-workflow-type-plus-task-queue split
(`CampaignJourneyWorkflowSupport`, `DecisionJourneyWorkflow`, `DecisionJourneyWorkflowImpl`,
`CampaignWorkerRegistrar`), the Studio authoring UI, and a real-database HTTP contract test
(`CampaignRestContractIT`) exercising a decision-graph campaign end to end against a Postgres
testcontainer — is all already live on `main`. This ADR was written to document a mechanism that
existed and shipped with no ADR at all.

**Phase A — this PR.** Backfills the rigor #4680's own acceptance criterion asked for and #4781
did not yet supply:
- A `WorkflowReplayer`-based test that replays a hand-constructed pre-#4781 Temporal history
  (matching the exact JSON shape `JourneyDefinitionLegacyShapeTest` already establishes as the
  legacy wire format — no `decisions`, no `nextStepOrder`) against the current
  `CampaignJourneyWorkflowImpl` binary and asserts it completes with no non-deterministic-history
  error. This is the concrete proof that was previously only a doc-comment claim.
- `JourneyDefinitionLegacyShapeTest` extended with the graph-fields case: a definition JSON with no
  `decisions` key and a step with no `nextStepOrder` key deserializes, through the production
  `kotlinAwareDataConverter`, to `decisions == emptyList()` and `step.nextStepOrder == null`.
- `DecisionPathSelection` gains the raw observed `DeliveryStatus` alongside the derived path,
  closing the two-hop audit-reconstruction gap named above in Compliance impact. This is an
  activity-side addition only: `CampaignJourneyActivitiesImpl.recordDecisionPath` re-reads the
  status via the same `SendLogRepository.deliveryStatusForStep` port the workflow already consulted
  to choose the path, rather than the workflow passing an additional argument into the activity
  call. The workflow-level code and the `CampaignJourneyActivities` interface are therefore
  unchanged — no `Workflow.getVersion` gate was needed because no workflow-issued command changed
  shape, count or order. The new field is serialized inside `enrolments.decision_path_json`, an
  existing free-form JSON text column added additively by V14 (`decision_path_json text`,
  nullable) — because the column is already schemaless JSON, adding a field to the serialized
  `DecisionPathSelection` needs no new Flyway migration; a pre-existing row simply deserializes
  with the new field defaulted to null, the same pattern `CampaignStep.condition`'s own doc comment
  already relies on for the analogous case.

**Phase B — not this PR's scope, future work.** An explicit `schemaVersion`/`journeyModel` field
replacing the `decisions.isEmpty()` inference (D2), landing as an additive column plus a backfill
migration once a third journey shape makes the inference ambiguous. Issue #4712 (per-enrolment
"why this path" UI) does not need to wait on Phase B — it can build against today's single
predicate kind — but it does directly benefit from this PR's `DecisionPathSelection.observedStatus`
field: a per-enrolment "why this path fired" view becomes a direct field read instead of the
send-log join described under Compliance impact.

## References

- [ADR-0200](0200-campaign-journeys-as-temporal-workflows-with-consent-gated-delivery.md) — D1's
  one-workflow-per-enrolment model and the `CampaignJourneyWorkflow` this ADR's
  `CampaignJourneyWorkflowSupport` refactor keeps replayable; this ADR extends it with a second
  workflow type rather than revising it.
- [ADR-0239](0239-delivery-outcome-events-for-notification-requests.md) — D4's monotonic
  `DeliveryStatus` transition guarantee, which is what makes the pre-Phase-A send-log join
  (Compliance impact) sound even before this PR's direct-snapshot fix.
- Issue #4680 — "add versioned multi-path decision workflow model", the request this ADR answers;
  remains open pending a manual close alongside this PR (see Context — the PR #4781 auto-close did
  not fire).
- PR #4781 — "add explicit decision graph journeys", the Phase 0 implementation this ADR documents.
- Issue #4712 — per-enrolment path-outcome UI; benefits from but does not block on this PR's
  `DecisionPathSelection.observedStatus` addition.
- `openbank-campaign-service/src/main/kotlin/com/openbank/campaign/application/workflow/CampaignJourneyWorkflowImpl.kt`
  — `CampaignJourneyWorkflowSupport`, `runLinear`, `runDecisionGraph`, the three existing
  `Workflow.getVersion` gates.
- `openbank-campaign-service/src/main/kotlin/com/openbank/campaign/application/workflow/CampaignWorkerRegistrar.kt`
  — the dual task-queue worker registration.
- `openbank-campaign-service/src/main/kotlin/com/openbank/campaign/domain/model/Campaign.kt` —
  `CampaignDecision`, `MAX_DECISIONS`, `validateDecisionGraph`.
- `openbank-campaign-service/src/main/kotlin/com/openbank/campaign/domain/model/Enrolment.kt` —
  `DecisionPathSelection`.
- `openbank-campaign-service/src/test/kotlin/com/openbank/campaign/integration/CampaignRestContractIT.kt`
  — the real-database decision-graph contract coverage this ADR cites as already-shipped evidence.
