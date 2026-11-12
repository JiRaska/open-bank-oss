// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.fincrime.admission

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CancellationException

class PaymentAdmissionChainTest {

    private val budget = Duration.ofMillis(100)
    private val ok = AdmissionReasonCode("OK")
    private val notBound = AdmissionReasonCode("NOT_BOUND_ON_THIS_RAIL")

    private fun decided(decision: AdmissionDecision, reason: String = "OK") =
        StepOutcome.Decided(decision, AdmissionReasonCode(reason))

    /** A chain with every step answering [fallback] except the ones in [overrides]. */
    private fun chain(
        overrides: Map<AdmissionStep, AdmissionCheck<String>> = emptyMap(),
        policy: UnavailablePolicy = UnavailablePolicy.HOLD,
        ticker: AdmissionTicker = AdmissionTicker { 0L },
        calls: MutableList<AdmissionStep> = mutableListOf(),
    ): PaymentAdmissionChain<String> {
        val builder = PaymentAdmissionChain.Builder<String>(budget, policy).ticker(ticker)
        AdmissionStep.entries.forEach { step ->
            val check = overrides[step] ?: AdmissionCheck { _, _ -> decided(AdmissionDecision.ALLOW) }
            builder.check(step) { facts, remaining ->
                calls += step
                check.evaluate(facts, remaining)
            }
        }
        return builder.build()
    }

    @Test
    fun `every control allowing is ALLOW and every step is recorded in order`(): Unit = runBlocking {
        val result = chain().admit("payment")

        assertThat(result.decision).isEqualTo(AdmissionDecision.ALLOW)
        assertThat(result.steps.map { it.step }).isEqualTo(AdmissionStep.entries)
        assertThat(result.degraded).isFalse()
        assertThat(result.reasons).containsOnly(ok)
        assertThat(result.toString()).contains("decision=ALLOW")
    }

    @Test
    fun `the most severe step wins and names the reason`(): Unit = runBlocking {
        val result = chain(
            mapOf(
                AdmissionStep.LIMITS to AdmissionCheck { _, _ -> decided(AdmissionDecision.CHALLENGE, "STEP_UP") },
                AdmissionStep.FRAUD to AdmissionCheck { _, _ -> decided(AdmissionDecision.HOLD, "FRAUD_REVIEW") },
            ),
        ).admit("payment")

        assertThat(result.decision).isEqualTo(AdmissionDecision.HOLD)
        assertThat(result.decidedBy.map { it.step }).containsExactly(AdmissionStep.FRAUD)
        assertThat(result.reasons).containsExactly(AdmissionReasonCode("FRAUD_REVIEW"))
        assertThat(result[AdmissionStep.LIMITS].effective).isEqualTo(AdmissionDecision.CHALLENGE)
    }

    @Test
    fun `a REJECT ends the admission and later controls are never called`(): Unit = runBlocking {
        val calls = mutableListOf<AdmissionStep>()
        val result = chain(
            mapOf(
                AdmissionStep.STANDING to AdmissionCheck { _, _ ->
                    decided(AdmissionDecision.REJECT, "ACCOUNT_FROZEN")
                },
            ),
            calls = calls,
        ).admit("payment")

        assertThat(result.decision).isEqualTo(AdmissionDecision.REJECT)
        assertThat(calls).containsExactly(AdmissionStep.STANDING)
        assertThat(result.steps.drop(1).map { it.outcome }).containsOnly(StepOutcome.NotReached)
        assertThat(result.steps.drop(1).map { it.effective }).containsOnlyNulls()
    }

    @Test
    fun `a HOLD does not end the admission so a later REJECT still wins`(): Unit = runBlocking {
        val result = chain(
            mapOf(
                AdmissionStep.SANCTIONS to
                    AdmissionCheck { _, _ -> decided(AdmissionDecision.HOLD, "SANCTIONS_REVIEW") },
                AdmissionStep.FRAUD to AdmissionCheck { _, _ -> decided(AdmissionDecision.REJECT, "FRAUD_DECLINED") },
            ),
        ).admit("payment")

        assertThat(result.decision).isEqualTo(AdmissionDecision.REJECT)
        assertThat(result[AdmissionStep.PAYEE_VERIFICATION].outcome).isEqualTo(StepOutcome.NotReached)
    }

    @Test
    fun `an unavailable control holds outbound money and is reported as degraded`(): Unit = runBlocking {
        val result = chain(
            mapOf(
                AdmissionStep.SANCTIONS to AdmissionCheck { _, _ ->
                    StepOutcome.Unavailable(UnavailableCause.DEPENDENCY_DOWN)
                },
            ),
        ).admit("payment")

        assertThat(result.decision).isEqualTo(AdmissionDecision.HOLD)
        assertThat(result.degraded).isTrue()
        assertThat(result.reasons).containsExactly(AdmissionReasonCode("UNAVAILABLE_DEPENDENCY_DOWN"))
        // The hold did not end the admission: the controls after it still ran.
        assertThat(result[AdmissionStep.FRAUD].effective).isEqualTo(AdmissionDecision.ALLOW)
    }

    @Test
    fun `on a deadline rail an unavailable control rejects and ends the admission`(): Unit = runBlocking {
        val calls = mutableListOf<AdmissionStep>()
        val result = chain(
            mapOf(
                AdmissionStep.LIMITS to
                    AdmissionCheck { _, _ -> StepOutcome.Unavailable(UnavailableCause.MISSING_DATA) },
            ),
            policy = UnavailablePolicy.REJECT,
            calls = calls,
        ).admit("payment")

        assertThat(result.decision).isEqualTo(AdmissionDecision.REJECT)
        assertThat(calls).containsExactly(AdmissionStep.STANDING, AdmissionStep.LIMITS)
    }

    @Test
    fun `a check that throws is unavailable, never a pass`(): Unit = runBlocking {
        val result = chain(
            mapOf(AdmissionStep.FRAUD to AdmissionCheck { _, _ -> error("adapter bug") }),
        ).admit("payment")

        val outcome = result[AdmissionStep.FRAUD].outcome as StepOutcome.Unavailable
        assertThat(outcome.cause).isEqualTo(UnavailableCause.CHECK_FAILED)
        assertThat(outcome.evidence.attributes).containsEntry("exception", "java.lang.IllegalStateException")
        assertThat(result.decision).isEqualTo(AdmissionDecision.HOLD)
    }

    @Test
    fun `cancellation is not swallowed into a hold`() {
        val cancelled = chain(
            mapOf(AdmissionStep.SANCTIONS to AdmissionCheck { _, _ -> throw CancellationException("stopping") }),
        )
        assertThatThrownBy { runBlocking { cancelled.admit("payment") } }
            .isInstanceOf(CancellationException::class.java)
    }

    @Test
    fun `an unbound step is recorded as not applicable and is not an ALLOW`(): Unit = runBlocking {
        val builder = PaymentAdmissionChain.Builder<String>(budget, UnavailablePolicy.HOLD)
        AdmissionStep.entries.forEach { builder.unbound(it, notBound) }

        val result = builder.build().admit("payment")

        assertThat(result.steps.map { it.outcome }).containsOnly(StepOutcome.NotApplicable(notBound))
        assertThat(result.decision).isEqualTo(AdmissionDecision.HOLD)
        assertThat(result.reasons).containsExactly(PaymentAdmissionChain.NO_CONTROL_DECIDED)
        assertThat(result.decidedBy).isEmpty()
    }

    @Test
    fun `one deciding control is enough for the unbound rest not to hold`(): Unit = runBlocking {
        val builder = PaymentAdmissionChain.Builder<String>(budget, UnavailablePolicy.HOLD)
            .check(AdmissionStep.STANDING) { _, _ -> decided(AdmissionDecision.ALLOW) }
        AdmissionStep.entries.drop(1).forEach { builder.unbound(it, notBound) }

        assertThat(builder.build().admit("payment").decision).isEqualTo(AdmissionDecision.ALLOW)
    }

    @Test
    fun `a chain cannot be built with a step missing, bound twice or without a budget`() {
        val partial = PaymentAdmissionChain.Builder<String>(budget, UnavailablePolicy.HOLD)
            .check(AdmissionStep.STANDING) { _, _ -> decided(AdmissionDecision.ALLOW) }
        assertThatThrownBy { partial.build() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("LIMITS")
        assertThatThrownBy { partial.unbound(AdmissionStep.STANDING, notBound) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("bound twice")
        listOf(Duration.ZERO, Duration.ofMillis(-1)).forEach { bad ->
            assertThatThrownBy { PaymentAdmissionChain.Builder<String>(bad, UnavailablePolicy.HOLD) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `each check is told how much of the budget is left`(): Unit = runBlocking {
        var now = 0L
        val seen = mutableListOf<Duration>()
        val spend = AdmissionCheck<String> { _, remaining ->
            seen += remaining
            now += Duration.ofMillis(10).toNanos()
            decided(AdmissionDecision.ALLOW)
        }
        chain(AdmissionStep.entries.associateWith { spend }, ticker = { now }).admit("payment")

        assertThat(seen).containsExactly(
            Duration.ofMillis(100),
            Duration.ofMillis(90),
            Duration.ofMillis(80),
            Duration.ofMillis(70),
            Duration.ofMillis(60),
            Duration.ofMillis(50),
        )
    }

    @Test
    fun `an ALLOW that arrives after the budget is unavailable`(): Unit = runBlocking {
        var now = 0L
        val calls = mutableListOf<AdmissionStep>()
        val result = chain(
            mapOf(
                AdmissionStep.SANCTIONS to AdmissionCheck { _, _ ->
                    now += budget.toNanos() + 1
                    decided(AdmissionDecision.ALLOW)
                },
            ),
            ticker = { now },
            calls = calls,
        ).admit("payment")

        assertThat(result[AdmissionStep.SANCTIONS].outcome)
            .isEqualTo(StepOutcome.Unavailable(UnavailableCause.BUDGET_EXCEEDED))
        // With the budget gone the remaining controls are not called at all.
        assertThat(calls).containsExactly(AdmissionStep.STANDING, AdmissionStep.LIMITS, AdmissionStep.SANCTIONS)
        assertThat(result[AdmissionStep.FRAUD].outcome)
            .isEqualTo(StepOutcome.Unavailable(UnavailableCause.BUDGET_EXCEEDED))
        assertThat(result.decision).isEqualTo(AdmissionDecision.HOLD)
    }

    @Test
    fun `a late refusal is still a refusal and a late outage keeps its cause`(): Unit = runBlocking {
        var now = 0L
        val lateReject = chain(
            mapOf(
                AdmissionStep.STANDING to AdmissionCheck { _, _ ->
                    now += budget.toNanos() * 2
                    decided(AdmissionDecision.REJECT, "ACCOUNT_CLOSED")
                },
            ),
            ticker = { now },
        ).admit("payment")
        assertThat(lateReject.decision).isEqualTo(AdmissionDecision.REJECT)
        assertThat(lateReject.reasons).containsExactly(AdmissionReasonCode("ACCOUNT_CLOSED"))

        now = 0L
        val lateOutage = chain(
            mapOf(
                AdmissionStep.STANDING to AdmissionCheck { _, _ ->
                    now += budget.toNanos() * 2
                    StepOutcome.Unavailable(UnavailableCause.DEPENDENCY_DOWN)
                },
            ),
            ticker = { now },
        ).admit("payment")
        assertThat(lateOutage[AdmissionStep.STANDING].outcome)
            .isEqualTo(StepOutcome.Unavailable(UnavailableCause.DEPENDENCY_DOWN))
    }

    @Test
    fun `exactly on the budget is still in time`(): Unit = runBlocking {
        var now = 0L
        val result = chain(
            mapOf(
                AdmissionStep.PAYEE_VERIFICATION to AdmissionCheck { _, _ ->
                    now = budget.toNanos()
                    decided(AdmissionDecision.ALLOW)
                },
            ),
            ticker = { now },
        ).admit("payment")
        assertThat(result.decision).isEqualTo(AdmissionDecision.ALLOW)
    }

    @Test
    fun `resolve refuses a set of outcomes with a step missing`() {
        val partial = mapOf<AdmissionStep, StepOutcome>(AdmissionStep.STANDING to decided(AdmissionDecision.ALLOW))
        assertThatThrownBy { PaymentAdmissionChain.resolve(partial, UnavailablePolicy.HOLD) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("FRAUD")
    }

    @Test
    fun `the default ticker is monotonic wall time`(): Unit = runBlocking {
        val builder = PaymentAdmissionChain.Builder<String>(Duration.ofMinutes(1), UnavailablePolicy.HOLD)
        AdmissionStep.entries.forEach { step -> builder.check(step) { _, _ -> decided(AdmissionDecision.ALLOW) } }
        assertThat(builder.build().admit("payment").decision).isEqualTo(AdmissionDecision.ALLOW)
    }
}
