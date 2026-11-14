// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.fincrime.admission

import io.kotest.common.ExperimentalKotest
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.shuffle
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * The properties ADR-0374 D1/D2 rest on, over random step outcomes and random binding orders: the
 * caller cannot change the order, an unavailable control never admits, and severity is a
 * commutative, associative maximum.
 */
@OptIn(ExperimentalKotest::class)
class PaymentAdmissionChainPropertyTest {
    private val config = PropTestConfig(seed = 20261111L, iterations = 1_000)
    private val stepCount = AdmissionStep.entries.size

    private val outcome: Arb<StepOutcome> = arbitrary {
        when (Arb.element("decided", "decided", "decided", "unavailable", "not_applicable").bind()) {
            "decided" -> StepOutcome.Decided(Arb.enum<AdmissionDecision>().bind(), AdmissionReasonCode("R_TEST"))
            "unavailable" -> StepOutcome.Unavailable(Arb.enum<UnavailableCause>().bind())
            else -> StepOutcome.NotApplicable(AdmissionReasonCode("N_A"))
        }
    }
    private val outcomes: Arb<List<StepOutcome>> = Arb.list(outcome, stepCount..stepCount)
    private val bindingOrder: Arb<List<AdmissionStep>> = Arb.shuffle(AdmissionStep.entries.toList())

    private class Run(val result: AdmissionResult, val called: List<AdmissionStep>)

    /** Build a chain binding the steps in [order], each answering its entry of [answers]. */
    private suspend fun admit(order: List<AdmissionStep>, answers: List<StepOutcome>, policy: UnavailablePolicy): Run {
        val called = mutableListOf<AdmissionStep>()
        val builder = PaymentAdmissionChain.Builder<Unit>(Duration.ofSeconds(1), policy).ticker { 0L }
        order.forEach { step ->
            builder.check(step) { _, _ ->
                called += step
                answers[step.ordinal]
            }
        }
        return Run(builder.build().admit(Unit), called)
    }

    @Test
    fun `combine is associative, commutative and idempotent with ALLOW as identity`(): Unit = runBlocking {
        val decision = Arb.enum<AdmissionDecision>()
        checkAll(config, decision, decision, decision) { a, b, c ->
            assertThat(a.combine(b).combine(c)).isEqualTo(a.combine(b.combine(c)))
            assertThat(a.combine(b)).isEqualTo(b.combine(a))
            assertThat(a.combine(a)).isEqualTo(a)
            assertThat(a.combine(AdmissionDecision.ALLOW)).isEqualTo(a)
            assertThat(a.combine(b)).isGreaterThanOrEqualTo(a).isGreaterThanOrEqualTo(b)
        }
    }

    @Test
    fun `the caller cannot change the order the controls run in`(): Unit = runBlocking {
        checkAll(config, bindingOrder, outcomes, Arb.enum<UnavailablePolicy>()) { order, answers, policy ->
            val run = admit(order, answers, policy)

            // Whatever order the rail bound them in, the calls are a prefix of the declared order.
            assertThat(run.called).isEqualTo(AdmissionStep.entries.take(run.called.size))
            assertThat(run.result.steps.map { it.step }).isEqualTo(AdmissionStep.entries)
            // And the result does not depend on the binding order at all.
            val canonical = admit(AdmissionStep.entries, answers, policy)
            assertThat(run.result.decision).isEqualTo(canonical.result.decision)
            assertThat(run.result.steps).isEqualTo(canonical.result.steps)
        }
    }

    @Test
    fun `an unavailable control never yields ALLOW or CHALLENGE`(): Unit = runBlocking {
        checkAll(config, bindingOrder, outcomes, Arb.enum<UnavailablePolicy>()) { order, answers, policy ->
            val result = admit(order, answers, policy).result
            if (result.degraded) {
                assertThat(result.decision).isGreaterThanOrEqualTo(AdmissionDecision.HOLD)
                assertThat(result.decision).isGreaterThanOrEqualTo(policy.decision)
            }
        }
    }

    @Test
    fun `ALLOW needs a control that allowed and none that said or meant anything else`(): Unit = runBlocking {
        checkAll(config, bindingOrder, outcomes, Arb.enum<UnavailablePolicy>()) { order, answers, policy ->
            val result = admit(order, answers, policy).result
            val allAllowOrNotApplicable = answers.all {
                it is StepOutcome.NotApplicable || (it is StepOutcome.Decided && it.decision == AdmissionDecision.ALLOW)
            }
            val someoneDecided = answers.any { it is StepOutcome.Decided }
            assertThat(result.decision == AdmissionDecision.ALLOW).isEqualTo(allAllowOrNotApplicable && someoneDecided)
        }
    }

    @Test
    fun `the result is the most severe contribution and a REJECT ends the admission`(): Unit = runBlocking {
        checkAll(config, bindingOrder, outcomes, Arb.enum<UnavailablePolicy>()) { order, answers, policy ->
            val run = admit(order, answers, policy)
            val firstReject = answers.indexOfFirst {
                PaymentAdmissionChain.contribution(it, policy) == AdmissionDecision.REJECT
            }
            val reached = if (firstReject < 0) stepCount else firstReject + 1

            assertThat(run.called).hasSize(reached)
            assertThat(run.result.steps.take(reached).map { it.outcome }).isEqualTo(answers.take(reached))
            assertThat(run.result.steps.drop(reached).map { it.outcome }).allMatch { it == StepOutcome.NotReached }

            val contributions = answers.take(reached).mapNotNull { PaymentAdmissionChain.contribution(it, policy) }
            if (answers.take(reached).any { it is StepOutcome.Decided }) {
                assertThat(run.result.decision).isEqualTo(contributions.max())
            } else {
                assertThat(run.result.decision).isGreaterThanOrEqualTo(policy.decision)
            }
            assertThat(run.result.reasons).isNotEmpty()
        }
    }

    @Test
    fun `resolving recorded outcomes gives what running the chain gave`(): Unit = runBlocking {
        checkAll(config, outcomes, Arb.enum<UnavailablePolicy>()) { answers, policy ->
            val ran = admit(AdmissionStep.entries, answers, policy).result
            val resolved = PaymentAdmissionChain.resolve(ran.steps.associate { it.step to it.outcome }, policy)

            assertThat(resolved.decision).isEqualTo(ran.decision)
            assertThat(resolved.steps).isEqualTo(ran.steps)
        }
    }

    @Test
    fun `no answer admits a payment once the budget is spent`(): Unit = runBlocking {
        checkAll(config, outcomes, Arb.enum<AdmissionStep>()) { answers, slowStep ->
            var now = 0L
            val budget = Duration.ofMillis(50)
            val builder = PaymentAdmissionChain.Builder<Unit>(budget, UnavailablePolicy.HOLD).ticker { now }
            AdmissionStep.entries.forEach { step ->
                builder.check(step) { _, _ ->
                    if (step == slowStep) now += budget.toNanos() + 1
                    answers[step.ordinal]
                }
            }
            val result = builder.build().admit(Unit)

            // The slow step is reached unless an earlier one rejected; if it is, nothing admits.
            if (result[slowStep].outcome != StepOutcome.NotReached) {
                assertThat(result.decision).isGreaterThanOrEqualTo(AdmissionDecision.HOLD)
            }
        }
    }
}
