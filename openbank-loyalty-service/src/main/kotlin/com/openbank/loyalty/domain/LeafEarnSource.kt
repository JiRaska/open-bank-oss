// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.loyalty.domain

/**
 * ADR-0282 D3 — the closed catalogue of reasons a party may earn [Leaves]. Every variant is a
 * financial-health signal.
 *
 * **No variant may reference spend volume, card usage, credit uptake, credit utilisation,
 * overdraft, or any product whose eligibility ADR-0142 decides.** ADR-0220 D3 rule 1 forbids
 * rewarding risk-increasing behaviour and its "Alternatives considered" rejects gamified credit
 * absolutely; a sealed hierarchy makes that a compile-time property of the catalogue rather than a
 * runtime check someone has to remember to write. A `when` over [LeafEarnSource] with no `else`
 * stops compiling the moment a variant is added without updating every consumer, and
 * `LeafEarnSourceTest` asserts the exhaustiveness directly.
 *
 * [OnTimeRepayment] is the one credit-adjacent variant and is deliberately included: ADR-0220 D3
 * names it, and it rewards *reducing* exposure, which is the opposite of the behaviour rule 1
 * exists to keep unrewarded. It is earned on a repayment that was already made on time — never on
 * taking a loan, drawing one down, or carrying a balance.
 *
 * Declaring a variant here is not the same claim as "a producer exists that awards it". This slice
 * ships the ledger and its earn path; which signals are actually wired is answered by the
 * consumers under `infrastructure`, never by this comment.
 */
sealed class LeafEarnSource(val id: String) {
    /** A rolling savings rate sustained above the catalogue threshold. */
    object SavingsRateSustained : LeafEarnSource("SAVINGS_RATE_SUSTAINED")

    /** An emergency buffer reaching the catalogue's months-of-outflow threshold. */
    object EmergencyBufferReached : LeafEarnSource("EMERGENCY_BUFFER_REACHED")

    /** A repayment made on time — rewards reducing exposure, never taking or using credit. */
    object OnTimeRepayment : LeafEarnSource("ON_TIME_REPAYMENT")

    /** A savings goal (ADR-0153) reached. */
    object SavingsGoalReached : LeafEarnSource("SAVINGS_GOAL_REACHED")

    /** Holding balances across more than one currency pocket (ADR-0109). */
    object CurrencyDiversification : LeafEarnSource("CURRENCY_DIVERSIFICATION")

    /** Educational content completed — the one signal engagement-service already produces. */
    object EducationalContentCompletion : LeafEarnSource("EDUCATIONAL_CONTENT_COMPLETION")

    /** A login streak reaching the catalogue threshold. */
    object LoginStreak : LeafEarnSource("LOGIN_STREAK")

    /** A tenure anniversary with the bank. */
    object TenureAnniversary : LeafEarnSource("TENURE_ANNIVERSARY")

    /** Product feedback given (the ADR-0210 screen-feedback stream). */
    object FeedbackGiven : LeafEarnSource("FEEDBACK_GIVEN")

    /** A referral that qualified under ADR-0266's fixed-reward lifecycle. */
    object QualifiedReferral : LeafEarnSource("QUALIFIED_REFERRAL")

    companion object {
        /**
         * `by lazy`, not an eager `val` — the Kotlin/JVM class-init-order trap for a sealed class
         * whose companion references sibling nested `object` subclasses declared in the SAME
         * enclosing class. Touching `ALL` first triggers this class's `<clinit>`, which can run
         * the companion initializer before every nested singleton has assigned its static field,
         * so an eager list captures `null` entries. `openbank-engagement-service`'s `EarnSource`
         * measured exactly that failure (NullPointerException / NoWhenBranchMatchedException)
         * before deferring; this catalogue is larger, so the trap is strictly more likely here.
         */
        val ALL: List<LeafEarnSource> by lazy {
            listOf(
                SavingsRateSustained,
                EmergencyBufferReached,
                OnTimeRepayment,
                SavingsGoalReached,
                CurrencyDiversification,
                EducationalContentCompletion,
                LoginStreak,
                TenureAnniversary,
                FeedbackGiven,
                QualifiedReferral,
            )
        }

        fun byId(id: String): LeafEarnSource? = ALL.find { it.id == id }
    }
}
