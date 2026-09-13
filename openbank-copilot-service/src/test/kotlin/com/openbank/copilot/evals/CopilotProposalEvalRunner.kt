// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.copilot.evals

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.openbank.copilot.application.CopilotPolicyGate
import com.openbank.copilot.application.port.out.ProposalResult
import com.openbank.copilot.application.port.out.ToolPolicyDecision
import com.openbank.copilot.application.port.out.ToolPolicyPort
import com.openbank.copilot.infrastructure.tool.CardFreezeProposalTool
import com.openbank.copilot.infrastructure.tool.PaymentProposalTool
import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant

/**
 * Three-valued, on purpose.
 *
 * `UNAVAILABLE` is a **first-class outcome with its own value** — it is never folded into [PASS]
 * (which would report quality nobody measured) and never into [FAIL] or a zero score (which would
 * report a quality regression for a wiring gap). The pass rate in [SuiteReport] is computed over
 * `PASS + FAIL` only; the UNAVAILABLE count is carried beside it, not inside it.
 *
 * This repo has shipped the collapsed version of this distinction more than once — a disabled push
 * adapter whose skip returned `success = true`, and a `quarantine()` that was one `log.warnf` — and
 * both read as working for as long as nobody looked. See `evals/README.md`.
 */
enum class ScenarioOutcome { PASS, FAIL, UNAVAILABLE }

/** Outcome of one [CopilotProposalScenario] against the real production classes. */
data class ProposalScenarioResult(
    val id: String,
    val description: String,
    val requirement: String,
    val outcome: ScenarioOutcome,
    /** Ground truth, rendered for the archived report. */
    val expected: String,
    /** What the production code actually produced, or the unavailability reason. */
    val actual: String,
    /** Populated only for [ScenarioOutcome.UNAVAILABLE] — the issue that closes the gap. */
    val trackedBy: String? = null,
)

/** The archived-per-run report (ADR-0235 "results archived per-run"). */
data class ProposalSuiteReport(
    val suite: String,
    val version: String,
    val runAt: String,
    val total: Int,
    val passed: Int,
    val failed: Int,
    /** Scenarios that could not run at all. Reported, never scored. */
    val unavailable: Int,
    /** `passed / (passed + failed)`, and `null` when nothing was assertable — never a silent 0.0. */
    val passRate: Double?,
    val minPassRate: Double,
    val regressed: Boolean,
    val results: List<ProposalScenarioResult>,
)

/**
 * A [ToolPolicyPort] that always allows. The gate under test has two layers; this pack asserts the
 * **application whitelist** (layer 1), so layer 2 is held constant at "allow" — a denying stub would
 * make every gate scenario pass for the wrong reason and prove nothing about the whitelist.
 */
private object AlwaysAllowPolicy : ToolPolicyPort {
    override fun authorize(toolName: String, customerId: String, amount: String?) = ToolPolicyDecision.ALLOWED
}

private object DiscardingAuditPublisher : AuditEventPublisher {
    override suspend fun publish(event: AuditEvent) = Unit
}

/**
 * The runner for the copilot proposal-quality pack. Calls the **real** production classes
 * ([PaymentProposalTool], [CardFreezeProposalTool], [CopilotPolicyGate]) — there is no model call
 * anywhere in this pack, so there is nothing to record or replay, and the LLM egress path being
 * down does not affect a single scenario here. See `evals/README.md` for why that split exists.
 */
object CopilotProposalEvalRunner {
    const val SUITE = "copilot-proposal-quality"
    const val VERSION = "v1"

    private val mapper = ObjectMapper().registerKotlinModule()
    private val paymentTool = PaymentProposalTool()
    private val cardFreezeTool = CardFreezeProposalTool()
    private val gate = CopilotPolicyGate(DiscardingAuditPublisher, AlwaysAllowPolicy, opaEnforce = true)

    /** Pure apart from the production call it makes. The one comparison the whole gate rests on. */
    fun evaluate(scenario: CopilotProposalScenario): ProposalScenarioResult {
        val subject = scenario.subject
        if (subject is ProposalSubject.NotWiredYet) {
            return ProposalScenarioResult(
                id = scenario.id,
                description = scenario.description,
                requirement = scenario.requirement,
                outcome = ScenarioOutcome.UNAVAILABLE,
                expected = "not assertable in this service today",
                actual = subject.reason,
                trackedBy = subject.trackedBy,
            )
        }

        val (actual, matched) = when (subject) {
            is ProposalSubject.ProposePayment -> compareProposal(
                paymentTool.propose(mapper.readTree(subject.argsJson)),
                scenario.expected,
            )
            is ProposalSubject.ProposeCardFreeze -> compareProposal(
                cardFreezeTool.propose(mapper.readTree(subject.argsJson)),
                scenario.expected,
            )
            is ProposalSubject.AuthorizeCapability -> {
                val decision = runBlocking { gate.authorize("synthetic-customer", subject.tool, subject.capability) }
                val rendered = "allow=${decision.allow} reason=${decision.reason}"
                val want = scenario.expected
                rendered to (want is ExpectedOutcome.Decision && want.allow == decision.allow)
            }
            is ProposalSubject.NotWiredYet -> error("unreachable — handled above")
        }

        return ProposalScenarioResult(
            id = scenario.id,
            description = scenario.description,
            requirement = scenario.requirement,
            outcome = if (matched) ScenarioOutcome.PASS else ScenarioOutcome.FAIL,
            expected = render(scenario.expected),
            actual = actual,
        )
    }

    /** Returns (rendered actual, matched). Field comparison is EXACT — an extra field is a mismatch. */
    private fun compareProposal(result: ProposalResult, expected: ExpectedOutcome): Pair<String, Boolean> {
        val proposal = result.proposal
        val rendered = when {
            proposal != null -> "proposal(kind=${proposal.kind}, fields=${proposal.fields})"
            else -> "rejected(${result.error})"
        }
        val matched = when (expected) {
            is ExpectedOutcome.Proposal ->
                proposal != null && proposal.kind == expected.kind && proposal.fields == expected.fields
            is ExpectedOutcome.Rejected ->
                proposal == null && (result.error?.contains(expected.errorContains, ignoreCase = true) == true)
            else -> false
        }
        return rendered to matched
    }

    private fun render(expected: ExpectedOutcome): String = when (expected) {
        is ExpectedOutcome.Proposal -> "proposal(kind=${expected.kind}, fields=${expected.fields})"
        is ExpectedOutcome.Rejected -> "rejected(error containing '${expected.errorContains}')"
        is ExpectedOutcome.Decision -> "allow=${expected.allow}"
        ExpectedOutcome.NotAssertable -> "not assertable"
    }

    fun run(
        scenarios: List<CopilotProposalScenario> = COPILOT_PROPOSAL_SCENARIOS,
        minPassRate: Double,
        clock: Clock = Clock.systemUTC(),
    ): ProposalSuiteReport {
        val results = scenarios.map { evaluate(it) }
        val passed = results.count { it.outcome == ScenarioOutcome.PASS }
        val failed = results.count { it.outcome == ScenarioOutcome.FAIL }
        val unavailable = results.count { it.outcome == ScenarioOutcome.UNAVAILABLE }
        val scored = passed + failed
        // null, not 0.0: "nothing could be measured" and "everything measured failed" are different
        // facts, and a benchmark that renders the first as the second reports a score it cannot earn.
        val rate = if (scored == 0) null else passed.toDouble() / scored
        return ProposalSuiteReport(
            suite = SUITE,
            version = VERSION,
            runAt = Instant.now(clock).toString(),
            total = results.size,
            passed = passed,
            failed = failed,
            unavailable = unavailable,
            passRate = rate,
            minPassRate = minPassRate,
            // An unmeasurable suite is NOT a passing suite: no rate means the gate cannot clear.
            regressed = rate == null || rate < minPassRate,
            results = results,
        )
    }
}

private val proposalReportMapper: ObjectMapper = ObjectMapper()
    .registerKotlinModule()
    .registerModule(JavaTimeModule())
    .apply { enable(SerializationFeature.INDENT_OUTPUT) }

fun ProposalSuiteReport.toJson(): String = proposalReportMapper.writeValueAsString(this)
