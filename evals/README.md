<!-- SPDX-License-Identifier: Apache-2.0 -->
# Agent-quality evals — scenario packs (issue #4463, ADR-0148/ADR-0235 follow-up)

This directory documents the **runnable benchmark suite** issue #4463 asked for. It is
deliberately separate from
[`openbank-libs/governance/evals/`](../openbank-libs/governance/evals/README.md), which is a
different mechanism for a different question:

| | `openbank-libs/governance/evals/` (ADR-0148) | this program |
|---|---|---|
| Question it answers | "did this **prompt or model** change degrade the copilot/ops-agent's behaviour?" | "does the **deterministic business logic** the agent plane depends on/feeds still do the right thing?" |
| Mechanism | record/replay of real model output (`.github/scripts/run-evals.py`) — CI has no model credentials, so yesterday's recorded output is replayed against today's `assert` blocks | direct assertion against real domain code (no model call, nothing to record) |
| Where scenarios live | `<charter>.yaml`, keyed by an `agents.yaml` charter id | next to the domain code they exercise (a service's own `src/test`), documented here |

Both feed the same ADR-0148 evals-gate story — regression against a declared baseline blocks a
change — but a scenario asserting on deterministic rule-engine output does not belong in the
prompt-replay registry: it has no `prompt:`, no recording, and nothing that could go stale the way
a prompt hash does.

## Scenario packs

### 1. Fraud review — shipped (PR #5105)

Location: `openbank-fraud-service/src/test/kotlin/com/openbank/fraud/evals/`.

- `FraudReviewScenarios.kt` — the scenario pack: synthetic transactions with known ground truth
  (expected `FraudVerdict`, expected review-queue surfacing, expected evidence reasons), covering
  every rule in `FraudRuleEngine` (velocity count/amount, large-single-transaction, new-payee,
  unmapped-currency fail-closed) plus two boundary/differential controls.
- `FraudReviewEvalRunner.kt` — the runner: calls the real `FraudRuleEngine.score()` (pure,
  framework-free domain code, ADR-0002) and compares against ground truth. No live model, no
  record/replay — see the file's KDoc for why that would be the wrong mechanism here.
- `FraudReviewEvalSuiteTest.kt` — wires the pack into JUnit: one `DynamicTest` per scenario
  (per-scenario pass/fail in CI's JUnit XML) plus a regression-gate test that archives a JSON
  report to `build/eval-reports/fraud-review-queue.json` and fails the build if the pass rate
  drops below `src/test/resources/evals/fraud-review-baseline.json`.
- `FraudReviewEvalHarnessSelfTest.kt` — proves the gate can go red: a known-bad ground-truth
  fixture, asserted through the runner directly (never added to the shipped pack, which would make
  it permanently red for the wrong reason).

CI: `.github/workflows/evals-fraud-review.yml`, path-scoped to the fraud domain rule engine and
this eval pack; archives the JSON report as a build artifact on every run.

**Data.** Every account/counterparty id is a deterministic name-based UUID derived from a fixed
seed string (ADR-0175 §5 class 3 — synthetic/non-personal). No production data of any kind.

### 2. Copilot proposal quality — shipped

Location: `openbank-copilot-service/src/test/kotlin/com/openbank/copilot/evals/`.

Issue #4463 asked for scenarios proving `propose.payment` / `propose.card_freeze` proposals "carry
correct SCA binding and never exceed consent scope". **Two of those three properties are not
assertable in this service today**, and the pack says so with a distinct outcome rather than
scoring them:

| property | outcome class | why |
|---|---|---|
| proposal construction is validated, propose-only, bounded | **runnable** — 8 scenarios | `PaymentProposalTool` / `CardFreezeProposalTool` are pure functions over a `JsonNode` |
| capability gating is deny-by-default | **runnable** — 3 scenarios | `CopilotPolicyGate.authorize` is application-layer code with injectable ports |
| SCA binding (proposal token → confirm) | **`UNAVAILABLE`** | nothing under `openbank-copilot-service/src/main` ever constructs a `ProposalToken`. `ActionConfirmResource` reads the token store; no production code writes to it, so `/api/v1/copilot/actions/{id}/confirm` can only ever answer `404 PROPOSAL_NOT_FOUND` and there is no binding to assert on |
| PSD2 consent scope not exceeded | **`UNAVAILABLE`** | consent-scope enforcement lives in `openbank-mcp-service`'s tool implementation (ADR-0195), a different and currently-unwired path — issue #2414 |

**`UNAVAILABLE` is a first-class outcome with its own value.** It is never folded into a pass (which
would claim a measurement nobody made) and never into a failure or a zero (which would report an
agent-quality regression for a wiring gap). The pass rate is computed over `passed + failed` only;
the unavailable count rides beside it in the archived report. When *nothing* is assertable the rate
is `null` and `regressed` is `true` — a pack that measured nothing must never read as a clean pass.
This repo has shipped the collapsed version of that distinction before: a disabled push adapter
whose skip returned `success = true`, and a pentest attestation minted by a job that fuzzed nothing.

Files:

- `CopilotProposalScenarios.kt` — the pack: declarative ground truth (expected proposal fields,
  expected rejection, expected policy verdict) over fixed synthetic UUIDs (ADR-0175 §5 class 3).
- `CopilotProposalEvalRunner.kt` — calls the real production classes and returns the three-valued
  `ScenarioOutcome`.
- `CopilotProposalEvalSuiteTest.kt` — one `DynamicTest` per scenario (per-scenario pass/fail in the
  JUnit XML; an `UNAVAILABLE` scenario is `abort`ed so it appears as a **third status — skipped —
  with its reason**), plus the regression gate that archives
  `build/eval-reports/copilot-proposal-quality.json` and fails below
  `src/test/resources/evals/copilot-proposal-baseline.json`.
- `CopilotProposalEvalHarnessSelfTest.kt` — proves the gate can go red *and* that the third outcome
  cannot collapse: known-bad ground truth must FAIL, an unavailable scenario must not become a pass,
  and adding unavailable scenarios must not move the pass rate.
- `ProposalPathAvailabilityTest.kt` — **stops an `UNAVAILABLE` declaration outliving its gap.** Each
  unavailability rests on a checkable fact about the source tree, re-proven every run; the moment a
  `ProposalToken` is constructed in `src/main`, or a consent scope is referenced there, the build
  goes red telling you to promote the scenario. Same bidirectional rule as
  `openbank-libs/governance/evals/recordings/backlog.yaml`: an undeclared gap and a stale
  declaration are both errors.

CI: `.github/workflows/evals-copilot-proposal.yml`, path-scoped to the proposal tools, the policy
gate, the copilot domain and this eval pack; archives the JSON report on every run.

**Why this pack needs no live model.** The LLM egress path is not a dependency of a single scenario
here — every one calls deterministic production code. That matters right now: the platform's
`openbank_llm_requests_total` has only ever recorded `http_error` outcomes (issue #5736), so a
benchmark that drove the real agents would today score zero for reasons that have nothing to do with
agent quality. The ADR-0148 record/replay gate reaches the model half by replaying *recorded*
output, which is why it is a different mechanism in a different tree — see the table at the top.

## References

- Issue #4463.
- ADR-0148 — prompt registry, evals gate, EU AI Act mapping.
- ADR-0235 — continuous AI assurance (adversarial gate, conformity-as-code, results archived
  per-run for prompt-drift analysis).
- ADR-0175 — data residency and sovereignty (§5 synthetic/non-personal data class).
- ADR-0089 — customer-facing AI assistant (`customer-copilot` charter).
- `openbank-libs/governance/evals/README.md` — the sibling LLM prompt/model evals gate.
