---
id: case-coordinator
plane: control
adr: ADR-0244
---

# case-coordinator

## Mission

Owns a single running *case* — a long-running Temporal workflow opened for one disposition target
(an alert, incident, or proposal lineage). The coordinator invites other chartered agents to
contribute mid-flight via OPA-gated Temporal signals, enforces the per-case-class budget and
deadline, judges when the swarm has converged, and emits **exactly one** human-in-the-loop proposal
per case. It never writes to a business record directly; the only output is a proposal into the
existing ADR-0031 approval queue.

## Why this agent exists

ADR-0202 covers agent-to-agent *hand-offs*: one agent finishes, another takes over. That pattern
does not cover several agents working the *same* problem concurrently, correcting each other
mid-flight, and converging on a better answer faster. The case-coordinator is the boundary that
keeps that concurrency bounded and safe: one durable workflow owns the case, the budget is declared
up front, pre-emption is deterministic, and the swarm cannot silently stall or explode in cost.
Without a dedicated coordinator, the agent that detected a problem would also judge when it is
solved — a conflict of interest that ADR-0244 D3 explicitly rejects.

## Human oversight

- `every: proposal` — every synthesized case outcome is a proposal into the HITL queue; a human
disposes before any state changes.
- `approver_must_differ_from: author` — segregation of duties is preserved even inside the swarm.
- Case budgets, deadlines, and contested-rate thresholds are declared in `agents.yaml` under
`case_classes`, so they are reviewable and versioned with the same governance rigor as tool tiers.

## Phase 1 runtime (as-built, #4181)

The `openbank-case-coordinator-agent` module now runs the case lifecycle:

- **`CaseWorkflow`** (Temporal workflow type `CaseWorkflow`, task queue `case-coordinator`) — a
  deterministic state machine that accepts `contribute` / `contest` / `request-synthesis` signals,
  enforces the per-case-class contribution cap and deadline (TTL), applies pre-emption by draft
  version, trips the contested-rate breaker, and exits **only** through a single terminal synthesis
  activity — so the "exactly one proposal per case" invariant holds even under a signal storm.
- **Capability gate** — `CaseCapabilityGate` checks `case.open`/`case.contribute`/`case.synthesize`
  against `agents.yaml` (fail-closed). This is the Phase 1 placeholder for the OPA-gated check;
  wiring the gate to the OPA sidecar bundle is Phase 4 work.
- **REST** — `POST /api/v1/case-coordinator/cases` opens a case (201/202/400/403/409/429/503
  depending on dedup, rate-limit, concurrent-ceiling and Temporal-availability outcomes) and
  `POST /api/v1/case-coordinator/cases/{id}/signals` delivers signals to a running workflow.
  Case opens are deduplicated by deterministic workflow id (`case-<class>-<subject>`,
  REJECT_DUPLICATE) and rate-limited per opening agent.
- **Outbox** — the terminal proposal event is published transactionally via the shared outbox
  pattern (`case_outbox` table, V2 migration) to the `proposal-events` Kafka topic; the proposal
  itself is consumed by the existing ADR-0031 HITL queue, which owns human disposition.
- **LLM synthesis** — the convergence judgement is an LLM call through the prompt registry
  (`CaseCoordinatorLlmPort`/`Adapter`), never a silent deterministic shortcut.

## Known gaps (these are real, not aspirational)

- **No other charter currently grants `case.join` or `case.contribute`.** The coordinator is the only
  swarm participant defined today; other agents will gain swarm capabilities only after the signal
  contract and policy gate are implemented.
- **The kill-switch-to-Temporal-cancellation wiring is not built.** The global and per-agent kill
  switches can halt agent runtimes today, but canceling an in-flight case workflow requires a new
  hook that does not yet exist.
- **The capability gate is not yet OPA-backed.** Phase 1 checks `agents.yaml` directly (fail-closed);
  routing the decision through the per-service OPA bundle, and shipping the coordinator's GitOps
  deployment (CNPG database, `openbank.temporal.enabled=true`, network policies), is Phase 4.
- **The read-only case index is live, but durable history remains incomplete.** `/iaops/cases`
  truthfully shows opened coordination cases (and an explicit empty state when none exist). A
  durable history projection and a per-case evidence/thread view remain follow-up work; Temporal
  is still the authoritative source for workflow history.
