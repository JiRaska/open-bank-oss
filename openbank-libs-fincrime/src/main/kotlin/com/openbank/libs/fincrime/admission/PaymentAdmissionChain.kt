// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.fincrime.admission

import java.time.Duration
import java.util.EnumMap
import java.util.concurrent.CancellationException

/** Monotonic time source of the chain's budget; injected so the chain itself reads no clock. */
fun interface AdmissionTicker {
    fun nanos(): Long
}

/**
 * One control, adapted by a rail. [remaining] is what is left of the rail's budget when the step
 * starts; an adapter that calls out must use it as its own timeout, because the chain cannot
 * pre-empt a call that is already running — it can only refuse the late answer.
 *
 * A check reports trouble as [StepOutcome.Unavailable]. If it throws instead, the chain records
 * [UnavailableCause.CHECK_FAILED]; a thrown exception never admits a payment.
 */
fun interface AdmissionCheck<in F> {
    suspend fun evaluate(facts: F, remaining: Duration): StepOutcome
}

/** One step as the chain ran it: what it answered and what that answer counted as. */
data class StepRecord(
    val step: AdmissionStep,
    val outcome: StepOutcome,
    /** The decision this step contributed, or null when it contributed none. */
    val effective: AdmissionDecision?,
)

/**
 * The result of one admission. [steps] always holds every [AdmissionStep], in declaration order, so
 * the record shows which controls ran, which did not apply and which were never reached. Built by
 * [PaymentAdmissionChain] only, so [decision] always follows from [steps].
 */
class AdmissionResult internal constructor(val decision: AdmissionDecision, val steps: List<StepRecord>) {
    /** The steps whose own contribution equals the final [decision]: what a reviewer is shown. */
    val decidedBy: List<StepRecord> get() = steps.filter { it.effective == decision }

    /** True when at least one control could not answer. */
    val degraded: Boolean get() = steps.any { it.outcome is StepOutcome.Unavailable }

    /**
     * Reason codes behind [decision], in step order. A control that could not answer reads
     * `UNAVAILABLE_<cause>`; an admission in which nothing decided reads
     * [PaymentAdmissionChain.NO_CONTROL_DECIDED].
     */
    val reasons: List<AdmissionReasonCode>
        get() = decidedBy.mapNotNull {
            when (val outcome = it.outcome) {
                is StepOutcome.Decided -> outcome.reason
                is StepOutcome.Unavailable -> AdmissionReasonCode("UNAVAILABLE_${outcome.cause.name}")
                is StepOutcome.NotApplicable, StepOutcome.NotReached -> null
            }
        }.ifEmpty { listOf(PaymentAdmissionChain.NO_CONTROL_DECIDED) }

    operator fun get(step: AdmissionStep): StepRecord = steps[step.ordinal]

    override fun toString(): String = "AdmissionResult(decision=$decision, steps=$steps)"
}

/**
 * The ordered pre-execution control chain (ADR-0374 D1, D2, D10).
 *
 *  - Steps run in [AdmissionStep] declaration order. The rail binds checks by step and cannot
 *    reorder, omit or repeat one: [Builder.build] refuses a chain with an unbound step.
 *  - The result is the most severe contribution ([AdmissionDecision.combine]).
 *  - A `REJECT` contribution ends the admission; later steps are [StepOutcome.NotReached].
 *  - [StepOutcome.Unavailable] contributes [UnavailablePolicy.decision], never less.
 *  - A step that answers after the budget ran out is unavailable, unless it answered `HOLD` or
 *    `REJECT`: a late refusal is still a refusal. A step that would start with no budget left is not
 *    called.
 *  - An admission in which no step decided is not an `ALLOW`: it resolves as unavailable.
 *
 * The chain holds no state between calls and performs no I/O of its own.
 */
class PaymentAdmissionChain<F> private constructor(
    private val bindings: Map<AdmissionStep, Binding<F>>,
    private val budget: Duration,
    private val unavailablePolicy: UnavailablePolicy,
    private val ticker: AdmissionTicker,
) {
    private sealed interface Binding<in F> {
        class Check<F>(val check: AdmissionCheck<F>) : Binding<F>
        class Unbound(val reason: AdmissionReasonCode) : Binding<Any?>
    }

    suspend fun admit(facts: F): AdmissionResult {
        val startedAt = ticker.nanos()
        val outcomes = EnumMap<AdmissionStep, StepOutcome>(AdmissionStep::class.java)
        var ended = false
        for (step in AdmissionStep.entries) {
            val outcome = if (ended) StepOutcome.NotReached else run(step, facts, startedAt)
            ended = ended || contribution(outcome, unavailablePolicy) == AdmissionDecision.REJECT
            outcomes[step] = outcome
        }
        return resolve(outcomes, unavailablePolicy)
    }

    private suspend fun run(step: AdmissionStep, facts: F, startedAt: Long): StepOutcome =
        when (val binding = bindings.getValue(step)) {
            is Binding.Unbound -> StepOutcome.NotApplicable(binding.reason)
            is Binding.Check -> {
                val remaining = budget.minusNanos(ticker.nanos() - startedAt)
                if (remaining.isZero || remaining.isNegative) {
                    BUDGET_EXCEEDED
                } else {
                    withinBudget(evaluate(binding.check, facts, remaining), startedAt)
                }
            }
        }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun evaluate(check: AdmissionCheck<F>, facts: F, remaining: Duration): StepOutcome = try {
        check.evaluate(facts, remaining)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        StepOutcome.Unavailable(
            UnavailableCause.CHECK_FAILED,
            AdmissionEvidence.of("exception" to failure.javaClass.name.take(AdmissionEvidence.MAX_VALUE_LENGTH)),
        )
    }

    private fun withinBudget(outcome: StepOutcome, startedAt: Long): StepOutcome {
        val late = ticker.nanos() - startedAt > budget.toNanos()
        val refusal = outcome is StepOutcome.Decided && outcome.decision >= AdmissionDecision.HOLD
        return if (late && !refusal && outcome !is StepOutcome.Unavailable) BUDGET_EXCEEDED else outcome
    }

    class Builder<F>(private val budget: Duration, private val unavailablePolicy: UnavailablePolicy) {
        private val bindings = EnumMap<AdmissionStep, Binding<F>>(AdmissionStep::class.java)
        private var ticker = AdmissionTicker(System::nanoTime)

        init {
            require(!budget.isZero && !budget.isNegative) { "Admission budget must be positive" }
        }

        /** Bind the rail's adapter for [step]. */
        fun check(step: AdmissionStep, check: AdmissionCheck<F>): Builder<F> = bind(step, Binding.Check(check))

        /**
         * State that [step] is not run on this rail, and why. It is recorded on every admission as
         * [StepOutcome.NotApplicable]; it is not an `ALLOW`.
         */
        fun unbound(step: AdmissionStep, reason: AdmissionReasonCode): Builder<F> = bind(step, Binding.Unbound(reason))

        /** Replace the monotonic time source; for tests. */
        fun ticker(ticker: AdmissionTicker): Builder<F> = apply { this.ticker = ticker }

        fun build(): PaymentAdmissionChain<F> {
            val missing = AdmissionStep.entries - bindings.keys
            require(missing.isEmpty()) { "Admission chain leaves steps unbound: $missing" }
            return PaymentAdmissionChain(EnumMap(bindings), budget, unavailablePolicy, ticker)
        }

        private fun bind(step: AdmissionStep, binding: Binding<F>): Builder<F> = apply {
            require(bindings.put(step, binding) == null) { "Admission step $step is bound twice" }
        }
    }

    companion object {
        /** Why an admission with no deciding step resolves as unavailable. */
        val NO_CONTROL_DECIDED = AdmissionReasonCode("NO_CONTROL_DECIDED")

        private val BUDGET_EXCEEDED = StepOutcome.Unavailable(UnavailableCause.BUDGET_EXCEEDED)

        /** What [outcome] counts as under [policy]; null when it contributes nothing. */
        fun contribution(outcome: StepOutcome, policy: UnavailablePolicy): AdmissionDecision? = when (outcome) {
            is StepOutcome.Decided -> outcome.decision
            is StepOutcome.Unavailable -> policy.decision
            is StepOutcome.NotApplicable, StepOutcome.NotReached -> null
        }

        /**
         * Combine step outcomes into the result. Pure, so a caller that runs its steps elsewhere (one
         * Temporal activity per control) resolves them exactly as [admit] does. Every step must be
         * present: a control the caller forgot is an error, not a pass.
         */
        fun resolve(outcomes: Map<AdmissionStep, StepOutcome>, policy: UnavailablePolicy): AdmissionResult {
            val missing = AdmissionStep.entries - outcomes.keys
            require(missing.isEmpty()) { "Admission outcomes are missing steps: $missing" }
            val records = AdmissionStep.entries.map { step ->
                val outcome = outcomes.getValue(step)
                StepRecord(step, outcome, contribution(outcome, policy))
            }
            val floor = if (records.any {
                    it.outcome is StepOutcome.Decided
                }
            ) {
                AdmissionDecision.ALLOW
            } else {
                policy.decision
            }
            val decision = records.mapNotNull { it.effective }.fold(floor, AdmissionDecision::combine)
            return AdmissionResult(decision, records)
        }
    }
}
