// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.copilot.evals

import com.openbank.copilot.domain.ActionKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Proves [CopilotProposalEvalRunner] can actually report a FAILURE, and that its third outcome
 * cannot collapse into either of the other two — not merely that the harness runs.
 *
 * `openbank-libs/governance/evals/README.md` states the principle for the sibling LLM gate: "a
 * runner that has quietly lost the ability to fail takes the build down with it rather than
 * reporting green". A pack with an UNAVAILABLE outcome needs the same proof twice over, because
 * there are now two ways for it to become theatre:
 *
 *  - the comparison stops detecting a wrong result (`known-bad ground truth reported as PASS`), and
 *  - the unavailable outcome silently becomes a pass, or silently becomes a zero that tanks the rate.
 *
 * Every fixture here is built ad hoc and asserted through the runner directly — never added to
 * [COPILOT_PROPOSAL_SCENARIOS], which would make the real pack permanently red for the wrong reason.
 */
class CopilotProposalEvalHarnessSelfTest {

    private val knownGood = COPILOT_PROPOSAL_SCENARIOS.single { it.id == "payment-fields-are-validated-not-narrated" }

    @Test
    fun `control - the known-good fixture passes`() {
        assertThat(CopilotProposalEvalRunner.evaluate(knownGood).outcome)
            .withFailMessage("control fixture failed — the comparison logic itself is broken")
            .isEqualTo(ScenarioOutcome.PASS)
    }

    @Test
    fun `runner reports FAIL for a deliberately wrong expected amount`() {
        val knownBad = knownGood.copy(
            id = "self-test-wrong-amount",
            expected = ExpectedOutcome.Proposal(
                kind = ActionKind.PAYMENT,
                fields = (knownGood.expected as ExpectedOutcome.Proposal).fields + ("amount" to "999999.00"),
            ),
        )
        assertThat(CopilotProposalEvalRunner.evaluate(knownBad).outcome)
            .withFailMessage(
                "harness self-test FAILED: a proposal whose amount is off by six orders of magnitude " +
                    "was reported as PASS — the pack is assurance theatre",
            )
            .isEqualTo(ScenarioOutcome.FAIL)
    }

    @Test
    fun `runner reports FAIL when a rejection is expected but a proposal is produced`() {
        val knownBad = knownGood.copy(
            id = "self-test-expected-rejection",
            expected = ExpectedOutcome.Rejected(errorContains = "IBAN"),
        )
        assertThat(CopilotProposalEvalRunner.evaluate(knownBad).outcome)
            .withFailMessage("harness self-test FAILED: a produced proposal satisfied an expected rejection")
            .isEqualTo(ScenarioOutcome.FAIL)
    }

    @Test
    fun `runner reports FAIL when the gate's verdict is the opposite of ground truth`() {
        val allowScenario = COPILOT_PROPOSAL_SCENARIOS.single { it.id == "gate-allows-declared-action-capability" }
        val knownBad = allowScenario.copy(
            id = "self-test-inverted-gate-verdict",
            expected = ExpectedOutcome.Decision(allow = false),
        )
        assertThat(CopilotProposalEvalRunner.evaluate(knownBad).outcome)
            .withFailMessage("harness self-test FAILED: an allowed capability satisfied an expected deny")
            .isEqualTo(ScenarioOutcome.FAIL)
    }

    // --- the UNAVAILABLE outcome must be its own value ------------------------------------------

    @Test
    fun `an unavailable scenario is neither a pass nor a failure`() {
        val unavailable = COPILOT_PROPOSAL_SCENARIOS.first { it.subject is ProposalSubject.NotWiredYet }
        val result = CopilotProposalEvalRunner.evaluate(unavailable)

        assertThat(result.outcome)
            .withFailMessage(
                "an unwired requirement was scored as %s. Folding 'could not run' into a real result " +
                    "is how a disabled adapter reports as a working one — the exact defect this pack's " +
                    "third outcome exists to prevent.",
                result.outcome,
            )
            .isEqualTo(ScenarioOutcome.UNAVAILABLE)
        assertThat(result.trackedBy).isNotBlank()
    }

    @Test
    fun `unavailable scenarios are excluded from the pass rate, not counted as zero`() {
        val oneGood = listOf(knownGood)
        val unavailable = COPILOT_PROPOSAL_SCENARIOS.filter { it.subject is ProposalSubject.NotWiredYet }

        val alone = CopilotProposalEvalRunner.run(oneGood, minPassRate = 1.0)
        val withUnavailable = CopilotProposalEvalRunner.run(oneGood + unavailable, minPassRate = 1.0)

        assertThat(withUnavailable.passRate)
            .withFailMessage(
                "adding %d unavailable scenario(s) moved the pass rate from %s to %s — they are being " +
                    "scored as failures, so the pack would report an agent-quality regression for a " +
                    "wiring gap it cannot measure",
                unavailable.size,
                alone.passRate,
                withUnavailable.passRate,
            )
            .isEqualTo(alone.passRate)
        assertThat(withUnavailable.unavailable).isEqualTo(unavailable.size)
        assertThat(withUnavailable.regressed).isFalse()
    }

    @Test
    fun `a pack with nothing assertable has a null rate and REGRESSES - it never reads as a clean pass`() {
        val onlyUnavailable = COPILOT_PROPOSAL_SCENARIOS.filter { it.subject is ProposalSubject.NotWiredYet }
        val report = CopilotProposalEvalRunner.run(onlyUnavailable, minPassRate = 1.0)

        assertThat(report.passRate)
            .withFailMessage("a pack that measured nothing reported a numeric score of %s", report.passRate)
            .isNull()
        assertThat(report.regressed)
            .withFailMessage(
                "a pack where every scenario was unavailable reported regressed=false — it would clear " +
                    "its gate having measured nothing at all",
            )
            .isTrue()
    }
}
