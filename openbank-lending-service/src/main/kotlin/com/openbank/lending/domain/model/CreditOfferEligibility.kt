// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.domain.model

import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * ADR-0269 rules 1 and 2 — the gate every credit *offer* passes before it can reach a customer.
 *
 * Two independent conditions, evaluated in this order and both fail-closed:
 *
 *  1. **Consent.** `credit_offers` is off by default. Without it there is no offer, full stop.
 *  2. **The distress floor.** Even WITH consent, an offer is suppressed while the borrower is in
 *     financial difficulty. Consent is permission to be offered; it is not permission to be
 *     targeted while drowning, and the customer who is most likely to accept a loan during a
 *     cashflow crisis is the one least able to repay it.
 *
 * This is a pure evaluator with no framework, clock or I/O of its own — the caller supplies both
 * the signals and `now`, so the whole table is exercisable from a unit test (ADR-0213's precedent:
 * credit policy is a versioned decision table evaluated in memory, never a service call graph).
 *
 * ## Why absence of a signal suppresses
 *
 * [BorrowerDistressSignals.complete] is false when any input could not be read. That case yields
 * [CreditOfferSuppressionCode.SIGNALS_UNAVAILABLE] rather than an offer: a marketing gate that
 * degrades open under failure is a gate that stops existing exactly when the upstream it depends
 * on is unhealthy. The blast radius of failing closed is a missed offer; of failing open, an offer
 * to someone in arrears.
 */
enum class CreditOfferSuppressionCode {
    /** No `credit_offers` consent. The default state for every customer. */
    CONSENT_ABSENT,

    /** One or more inputs could not be read. Fail-closed — see the class docs. */
    SIGNALS_UNAVAILABLE,

    /** Past-due amount on any existing facility. */
    ARREARS,

    /** Current account balance is negative or the overdraft is drawn. */
    NEGATIVE_BALANCE,

    /** An enforcement (execution) order is on file. */
    ENFORCEMENT,

    /** An insolvency proceeding is on file. */
    INSOLVENCY,

    /** The customer is in a hardship/forbearance arrangement. */
    HARDSHIP,

    /** An affordability assessment failed recently enough to still be binding. */
    AFFORDABILITY_COOLING,

    /** 360-derived buffer is below the configured floor — thin cover, not yet distress. */
    BUFFER_BELOW_FLOOR,

    /**
     * The 360 profile has too few observed months to be evidence.
     *
     * Distinct from [SIGNALS_UNAVAILABLE] on purpose: that one is a fault worth alerting on, this
     * one is a customer the bank has simply not seen for long enough. Collapsing them would bury a
     * genuine outage inside the normal traffic of new customers.
     */
    HISTORY_TOO_THIN,

    /** A credit-marketing contact already went out inside the frequency window. */
    FREQUENCY_CAP,

    /** Nothing about the inputs changed since the last contact, so there is nothing to say. */
    NO_MATERIAL_CHANGE,
}

/**
 * The distress inputs, read at evaluation time. Every field is deliberately a fact about the
 * borrower rather than a verdict: the verdict is this file's job, and keeping the two apart is what
 * lets the thresholds move without the callers moving.
 *
 * [complete] is the caller's honest statement that it managed to read all of them. A caller that
 * silently substitutes defaults for an unreachable upstream defeats the fail-closed rule, so the
 * flag exists to make that substitution impossible to express by accident.
 */
data class BorrowerDistressSignals(
    val hasArrears: Boolean,
    val hasNegativeBalance: Boolean,
    val hasEnforcementOrder: Boolean,
    val hasInsolvencyProceeding: Boolean,
    val inHardshipArrangement: Boolean,
    val lastAffordabilityFailureAt: Instant?,
    val bufferDays: Int?,
    /**
     * Whole months of cash-flow history behind the 360 figures. A three-week-old customer and a
     * five-year customer can produce the same median, so the count travels with the numbers rather
     * than being discarded at the source.
     */
    val monthsObserved: Int?,
    val lastCreditContactAt: Instant?,
    val inputsChangedSinceLastContact: Boolean,
    val complete: Boolean,
)

/**
 * Who started the conversation — the axis ADR-0269's pull-only rule turns on.
 *
 * PUSH is the bank speaking unprompted and needs consent; PULL is the customer asking and does not.
 * Modelled as a type rather than a boolean parameter so a call site cannot get it backwards
 * silently: `evaluate(partyId, PUSH)` reads as what it is, `evaluate(partyId, true)` does not.
 */
enum class OfferSurface { PUSH, PULL }

/** Thresholds, versioned as one object so a change is a single reviewable diff (ADR-0213 shape). */
data class CreditOfferPolicy(
    val version: Int,
    val minimumBufferDays: Int,
    val affordabilityCoolingDays: Long,
    val contactFrequencyDays: Long,
    val minimumMonthsObserved: Int,
) {
    init {
        require(version > 0) { "policy version must be positive" }
        require(minimumBufferDays >= 0) { "minimumBufferDays must not be negative" }
        require(affordabilityCoolingDays >= 0) { "affordabilityCoolingDays must not be negative" }
        require(contactFrequencyDays >= 0) { "contactFrequencyDays must not be negative" }
        require(minimumMonthsObserved >= 0) { "minimumMonthsObserved must not be negative" }
    }

    companion object {
        /**
         * v1 defaults. 30 days of contact spacing and a 14-day affordability cooling window are the
         * ADR's own numbers; the 30-day buffer floor is one month of cover, the point below which a
         * new instalment is being offered to someone with no room for a bad month.
         */
        val V1 = CreditOfferPolicy(
            version = 1,
            minimumBufferDays = 30,
            affordabilityCoolingDays = 14,
            contactFrequencyDays = 30,
            // Three whole months: enough for one unusual month not to be the whole picture, and
            // the point at which a median stops being an anecdote.
            minimumMonthsObserved = 3,
        )
    }
}

/** The evaluation outcome. [Suppressed] always names a reason — an unexplained refusal is not auditable. */
sealed interface CreditOfferDecision {
    val policyVersion: Int

    data class Allowed(override val policyVersion: Int) : CreditOfferDecision

    data class Suppressed(override val policyVersion: Int, val code: CreditOfferSuppressionCode) : CreditOfferDecision
}

object CreditOfferEligibility {

    /**
     * Evaluate the gate. [hasOffersConsent] is the customer's `credit_offers` state as read from
     * consent-service; there is no default-true path into this function.
     *
     * Order matters only for which reason code a caller sees, never for whether an offer is allowed:
     * every condition below is disqualifying on its own. Consent is checked first because it is the
     * one the customer controls, and the distress codes are ordered hardest-fact-first so the
     * recorded reason is the most defensible one available.
     */
    fun evaluate(
        hasOffersConsent: Boolean,
        signals: BorrowerDistressSignals,
        now: Instant,
        policy: CreditOfferPolicy = CreditOfferPolicy.V1,
        surface: OfferSurface = OfferSurface.PUSH,
    ): CreditOfferDecision {
        if (!hasOffersConsent) {
            return CreditOfferDecision.Suppressed(policy.version, CreditOfferSuppressionCode.CONSENT_ABSENT)
        }
        if (!signals.complete) {
            return CreditOfferDecision.Suppressed(policy.version, CreditOfferSuppressionCode.SIGNALS_UNAVAILABLE)
        }
        val distress = distressCode(signals, now, policy, surface)
            ?: return CreditOfferDecision.Allowed(policy.version)
        return CreditOfferDecision.Suppressed(policy.version, distress)
    }

    /**
     * The distress table itself: the first matching row, or null when none matches.
     *
     * Ordered hardest-fact-first so the recorded reason is the most defensible one available — a
     * borrower who is both insolvent and overdrawn is suppressed as INSOLVENCY, which is the fact
     * that would be quoted back in a complaint. The ordering never changes *whether* an offer is
     * allowed: every row below is disqualifying on its own.
     */
    private fun distressCode(
        signals: BorrowerDistressSignals,
        now: Instant,
        policy: CreditOfferPolicy,
        surface: OfferSurface,
    ): CreditOfferSuppressionCode? {
        // Hard facts and a binding affordability refusal stop BOTH surfaces. These are the cases
        // where the answer itself is the harm, no matter who started the conversation.
        hardFactCode(signals)?.let { return it }
        if (withinDays(signals.lastAffordabilityFailureAt, now, policy.affordabilityCoolingDays)) {
            return CreditOfferSuppressionCode.AFFORDABILITY_COOLING
        }
        // Everything below is about the bank speaking UNPROMPTED, and none of it is a reason to
        // refuse an answer the customer just asked for:
        //
        //  - a thin buffer means "do not go looking for this customer with an offer"; it does not
        //    mean "refuse to tell them what a loan would cost", which would leave someone who is
        //    managing carefully unable to plan at all;
        //  - a frequency cap limits how often the BANK initiates, and cannot silence a reply;
        //  - materiality asks whether there is anything new to SAY, which is meaningless when the
        //    customer just asked the question.
        if (surface == OfferSurface.PULL) return null

        // A push needs evidence, and a median over one or two months is not evidence. A pull is
        // unaffected: answering "what would this cost" does not rest on knowing the customer.
        val months = signals.monthsObserved ?: return CreditOfferSuppressionCode.HISTORY_TOO_THIN
        if (months < policy.minimumMonthsObserved) return CreditOfferSuppressionCode.HISTORY_TOO_THIN

        // A missing buffer is not a healthy buffer. Unknown reads as below the floor.
        val buffer = signals.bufferDays ?: return CreditOfferSuppressionCode.BUFFER_BELOW_FLOOR
        if (buffer < policy.minimumBufferDays) return CreditOfferSuppressionCode.BUFFER_BELOW_FLOOR

        return contactCode(signals, now, policy)
    }

    /** Facts on file, independent of any threshold. */
    private fun hardFactCode(signals: BorrowerDistressSignals): CreditOfferSuppressionCode? = when {
        signals.hasInsolvencyProceeding -> CreditOfferSuppressionCode.INSOLVENCY
        signals.hasEnforcementOrder -> CreditOfferSuppressionCode.ENFORCEMENT
        signals.hasArrears -> CreditOfferSuppressionCode.ARREARS
        signals.inHardshipArrangement -> CreditOfferSuppressionCode.HARDSHIP
        signals.hasNegativeBalance -> CreditOfferSuppressionCode.NEGATIVE_BALANCE
        else -> null
    }

    /**
     * Spacing and materiality. Outside the frequency window a repeat contact still needs something
     * new to say; a periodic reminder that nothing changed is a nudge, and ADR-0269 rules those out.
     */
    private fun contactCode(
        signals: BorrowerDistressSignals,
        now: Instant,
        policy: CreditOfferPolicy,
    ): CreditOfferSuppressionCode? = when {
        withinDays(signals.lastCreditContactAt, now, policy.contactFrequencyDays) ->
            CreditOfferSuppressionCode.FREQUENCY_CAP
        signals.lastCreditContactAt != null && !signals.inputsChangedSinceLastContact ->
            CreditOfferSuppressionCode.NO_MATERIAL_CHANGE
        else -> null
    }

    private fun withinDays(at: Instant?, now: Instant, days: Long): Boolean =
        at != null && ChronoUnit.DAYS.between(at, now) < days
}
