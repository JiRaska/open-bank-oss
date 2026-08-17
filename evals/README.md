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

### 1. Fraud review — shipped in this PR

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

### 2. Copilot proposal quality — deferred, not shipped in this PR

Issue #4463 also asked for a pack asserting that `propose.payment`/`propose.card_freeze`
proposals "carry correct SCA binding and never exceed consent scope." Scoping this out for a
follow-up rather than shipping a shallow version, for two concrete reasons found while surveying
`openbank-copilot-service`:

1. **"Consent scope" checking does not exist in the code path the issue names.** PSD2
   consent-scope enforcement lives in `openbank-mcp-service`
   (`ProposedOnly.kt`/`ProposePaymentArgs.kt`), which is a *different, currently-unwired* tool
   implementation from `openbank-copilot-service`'s `PaymentProposalTool`/`CardFreezeProposalTool`
   (see `docs/adr/0195-mcp-server-caller-authentication-and-psd2-consent-binding.md` and issue
   #2414). A "consent-scope" eval scenario against `openbank-copilot-service` today would be
   asserting on a check that literally does not run there — an eval suite that always trivially
   passes because the property under test is absent is worse than no suite (same failure mode
   ADR-0148's own "Negative" section warns about: "a shallow eval suite would satisfy the gate's
   letter without its purpose").
2. **"SCA binding" is not a single field to assert on.** It spans three separate classes
   (`PaymentProposalTool`/`CardFreezeProposalTool` build the `ActionProposal`; `ProposalToken`
   assembly happens inside `CopilotChatService`; binding is enforced as an identity check in
   `ActionConfirmResource`) — a real scenario pack needs to drive that whole path, which is a
   materially bigger integration surface than the fraud pack's single pure function.

**What doing this properly needs**, tracked as this issue's follow-up rather than invented here:
either wire `openbank-copilot-service`'s proposal tools to the same consent-scope check
`openbank-mcp-service` already has (closing #2414 first — an eval against a gap that's about to
close is more useful than one against a gap that stays open), or explicitly scope the pack to what
*does* exist today (`CopilotPolicyGate.authorize` capability/OPA checks, `ProposalToken`
expiry/customer-id binding) and document the consent-scope gap as a known limitation rather than
silently asserting nothing. Either way this is deterministic application-layer logic, not model
output — the same "assert on the business logic around the AI, not on live model text" principle
the fraud pack already applies, per ADR-0148/ADR-0235's record/replay rationale.

## References

- Issue #4463.
- ADR-0148 — prompt registry, evals gate, EU AI Act mapping.
- ADR-0235 — continuous AI assurance (adversarial gate, conformity-as-code, results archived
  per-run for prompt-drift analysis).
- ADR-0175 — data residency and sovereignty (§5 synthetic/non-personal data class).
- ADR-0089 — customer-facing AI assistant (`customer-copilot` charter).
- `openbank-libs/governance/evals/README.md` — the sibling LLM prompt/model evals gate.
