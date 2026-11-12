// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.fincrime.admission

/**
 * The controls an outbound payment passes before any irrevocable step (ADR-0374 D1).
 *
 * **Declaration order IS the execution order.** [PaymentAdmissionChain] iterates [entries]; a rail
 * binds a check to a step and has no way to say when it runs. Cheap, deterministic, local checks
 * come first, so a payment that must be refused costs no remote call and tells a later control
 * nothing. Reordering these constants is a behaviour change for every rail.
 */
enum class AdmissionStep {
    /** Debtor party and account may transact: not frozen, blocked or closed. */
    STANDING,

    /** Per-payment and cumulative limits of the debtor and channel. */
    LIMITS,

    /** Counterparty against sanctions lists. */
    SANCTIONS,

    /** Jurisdiction, counterparty and amount-versus-profile rules. */
    AML_DESTINATION_RISK,

    /** Behavioural fraud verdict. */
    FRAUD,

    /** Verification of Payee, where the scheme requires it. */
    PAYEE_VERIFICATION,
}

/**
 * What a control says about a payment (ADR-0374 D2). Declaration order is severity, least first:
 * [combine] is `max` over it, and the chain result is the most severe step decision.
 */
enum class AdmissionDecision {
    ALLOW,

    /** Proceed only after a strong-authentication step-up. */
    CHALLENGE,

    /** Park the payment for a human decision; nothing irrevocable happens until release. */
    HOLD,
    REJECT,
    ;

    /** Most severe of the two. Associative, commutative and idempotent, with [ALLOW] as identity. */
    fun combine(other: AdmissionDecision): AdmissionDecision = if (other > this) other else this
}

/**
 * Machine-readable reason of a step outcome, e.g. `ACCOUNT_FROZEN`. Upper snake case and bounded, so
 * it is safe as a metric tag, a case alert code and a stored column without further escaping.
 */
@JvmInline
value class AdmissionReasonCode(val value: String) {
    init {
        require(PATTERN.matches(value)) { "Admission reason code must match ${PATTERN.pattern}" }
    }

    override fun toString(): String = value

    companion object {
        private val PATTERN = Regex("[A-Z][A-Z0-9_]{1,63}")
    }
}

/**
 * What a step based its answer on, as it will be stored with the hold and shown to the reviewer.
 * Bounded on purpose: it travels in an outbox row and a case, so a control cannot attach a document.
 * Keys are lower snake case; iteration order is the key order, so equal evidence renders equally.
 */
class AdmissionEvidence private constructor(val attributes: Map<String, String>) {

    override fun equals(other: Any?): Boolean = other is AdmissionEvidence && other.attributes == attributes

    override fun hashCode(): Int = attributes.hashCode()

    override fun toString(): String = "AdmissionEvidence($attributes)"

    companion object {
        const val MAX_ATTRIBUTES = 16
        const val MAX_VALUE_LENGTH = 256
        private val KEY = Regex("[a-z][a-z0-9_]{0,47}")

        val NONE = AdmissionEvidence(emptyMap())

        fun of(attributes: Map<String, String>): AdmissionEvidence {
            require(attributes.size <= MAX_ATTRIBUTES) { "At most $MAX_ATTRIBUTES evidence attributes" }
            attributes.forEach { (key, value) ->
                require(KEY.matches(key)) { "Evidence key must match ${KEY.pattern}" }
                require(value.length <= MAX_VALUE_LENGTH) { "Evidence value of '$key' exceeds $MAX_VALUE_LENGTH" }
            }
            return AdmissionEvidence(attributes.toSortedMap())
        }

        fun of(vararg attributes: Pair<String, String>): AdmissionEvidence = of(attributes.toMap())
    }
}

/** Why a step could not answer. Every one of them is "no answer", never "a clean answer". */
enum class UnavailableCause {
    /** The dependency timed out or refused. */
    DEPENDENCY_DOWN,

    /** The step needed a fact the rail could not supply, or got one too stale to trust. */
    MISSING_DATA,

    /** The rail's time budget ran out before or while the step ran (ADR-0374 D10). */
    BUDGET_EXCEEDED,

    /** The check itself threw. */
    CHECK_FAILED,
}

/**
 * The answer of one step. Four shapes that never collapse into each other: a control that did not
 * run is [NotApplicable] or [NotReached], a control that could not answer is [Unavailable], and
 * only a control that looked and decided is [Decided]. None of the three is an `ALLOW`.
 */
sealed interface StepOutcome {

    data class Decided(
        val decision: AdmissionDecision,
        val reason: AdmissionReasonCode,
        val evidence: AdmissionEvidence = AdmissionEvidence.NONE,
    ) : StepOutcome

    data class Unavailable(val cause: UnavailableCause, val evidence: AdmissionEvidence = AdmissionEvidence.NONE) :
        StepOutcome

    /** The step does not apply to this payment or is not bound on this rail; [reason] says which. */
    data class NotApplicable(val reason: AdmissionReasonCode) : StepOutcome

    /** An earlier step ended the admission before this one ran. Produced by the chain only. */
    data object NotReached : StepOutcome
}

/**
 * What a rail does with a control that cannot answer. There is deliberately no third value: an
 * unavailable control never admits a payment (ADR-0374 D2).
 */
enum class UnavailablePolicy(val decision: AdmissionDecision) {
    /** Outbound money: park it until the control can answer or a human decides. */
    HOLD(AdmissionDecision.HOLD),

    /** Rails with a scheme deadline: refuse in time rather than hold past it (ADR-0374 D10). */
    REJECT(AdmissionDecision.REJECT),
}
