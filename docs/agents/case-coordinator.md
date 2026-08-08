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

## Known gaps (these are real, not aspirational)

- **There is no `openbank-case-coordinator-agent` module yet.** This charter is the governance
scaffold; the Quarkus service, Temporal workflow, signal handler, and OPA-gated capability check
are follow-up work tracked by ADR-0244 delivery notes.
- **No other charter currently grants `case.join` or `case.contribute`.** The coordinator is the only
swarm participant defined today; other agents will gain swarm capabilities only after the signal
contract and policy gate are implemented.
- **The kill-switch-to-Temporal-cancellation wiring is not built.** The global and per-agent kill
switches can halt agent runtimes today, but canceling an in-flight case workflow requires a new
hook that does not yet exist.
- **The LLM synthesis step is undefined.** Whether the coordinator uses an LLM for convergence
judgement or a deterministic rule set is a downstream decision; this charter reserves the
`case.synthesize` capability and leaves the implementation open.
