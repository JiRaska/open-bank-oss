// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.loyalty.domain

import java.time.Duration

/**
 * One reviewed earn rule: completing [source] earns [leaves], and the resulting lot lives for
 * [validity] before it expires (ADR-0282 D5 — a published, fixed expiry, never a surprise).
 */
data class EarnRule(val source: LeafEarnSource, val leaves: Leaves, val validity: Duration = DEFAULT_VALIDITY) {
    init {
        require(!leaves.isZero()) { "earn rule for ${source.id} awards zero leaves" }
        require(!validity.isNegative && !validity.isZero) { "earn rule for ${source.id} has non-positive validity" }
    }

    companion object {
        /** ADR-0282 D5's proposed 24 months, expressed in days so the value is exact. */
        val DEFAULT_VALIDITY: Duration = Duration.ofDays(VALIDITY_DAYS)
        private const val VALIDITY_DAYS = 730L
    }
}

/**
 * The reviewed earn catalogue. Adding an entry is a pull request against ADR-0282 D3's rule —
 * never a runtime or admin-ui action, the same discipline `ChallengeCatalog` and campaign's
 * `TemplateCatalog` already use.
 *
 * [RULE_VERSION] is frozen onto every [LeafLedgerEntry] at award time, so changing an amount here
 * never silently reattributes history to a rule version that did not decide it. It is a named,
 * reviewable constant rather than the service's `version.txt`, which moves on unrelated releases.
 */
object EarnCatalog {
    const val RULE_VERSION: String = "v1"

    val ALL: Map<String, EarnRule> = listOf(
        EarnRule(LeafEarnSource.SavingsRateSustained, Leaves.of(SAVINGS_RATE_LEAVES)),
        EarnRule(LeafEarnSource.EmergencyBufferReached, Leaves.of(EMERGENCY_BUFFER_LEAVES)),
        EarnRule(LeafEarnSource.OnTimeRepayment, Leaves.of(ON_TIME_REPAYMENT_LEAVES)),
        EarnRule(LeafEarnSource.SavingsGoalReached, Leaves.of(SAVINGS_GOAL_LEAVES)),
        EarnRule(LeafEarnSource.CurrencyDiversification, Leaves.of(DIVERSIFICATION_LEAVES)),
        EarnRule(LeafEarnSource.EducationalContentCompletion, Leaves.of(EDUCATION_LEAVES)),
        EarnRule(LeafEarnSource.LoginStreak, Leaves.of(LOGIN_STREAK_LEAVES)),
        EarnRule(LeafEarnSource.TenureAnniversary, Leaves.of(TENURE_LEAVES)),
        EarnRule(LeafEarnSource.FeedbackGiven, Leaves.of(FEEDBACK_LEAVES)),
        EarnRule(LeafEarnSource.QualifiedReferral, Leaves.of(REFERRAL_LEAVES)),
    ).associateBy { it.source.id }

    fun ruleFor(source: LeafEarnSource): EarnRule = requireNotNull(ALL[source.id]) {
        // Unreachable while ALL is built from LeafEarnSource itself, and asserted by
        // EarnCatalogTest — a variant added to the sealed class without a rule fails there,
        // not in production.
        "no earn rule for source ${source.id}"
    }

    private const val SAVINGS_RATE_LEAVES = 120
    private const val EMERGENCY_BUFFER_LEAVES = 200
    private const val ON_TIME_REPAYMENT_LEAVES = 40
    private const val SAVINGS_GOAL_LEAVES = 150
    private const val DIVERSIFICATION_LEAVES = 60
    private const val EDUCATION_LEAVES = 50
    private const val LOGIN_STREAK_LEAVES = 20
    private const val TENURE_LEAVES = 100
    private const val FEEDBACK_LEAVES = 15
    private const val REFERRAL_LEAVES = 250
}
