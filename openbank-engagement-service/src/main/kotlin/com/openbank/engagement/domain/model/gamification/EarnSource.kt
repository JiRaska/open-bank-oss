// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.domain.model.gamification

/**
 * ADR-0220 D3 — the closed catalogue of reasons a party may earn [Points]. Sealed, not a string
 * or an open enum: ADR-0220's own "Alternatives considered" rejects "gamify engagement with credit
 * products" *absolutely*, and D3 rule 1 says "no challenge may reward credit uptake, credit
 * utilisation or any risk-increasing behaviour" — a sealed hierarchy makes that a compile-time
 * property of this catalogue rather than a runtime check someone has to remember to write. A new
 * earn reason is a pull request that touches this file, reviewable against D3 rule 1 by the same
 * discipline `SurfaceCatalog`/`ChallengeCatalog` already use for content.
 *
 * A `when` over [EarnSource] with no `else` branch does not compile once a variant is added
 * without updating every consumer — exhaustiveness is asserted directly in `EarnSourceTest`,
 * not left to reviewer attention.
 *
 * Mirrors [com.openbank.engagement.domain.model.AdverseState]'s KDoc convention: declaring a
 * variant here is not the same claim as "a consumer exists that awards it". Only
 * [EducationalContentCompletion] has a real trigger in this slice — reused directly from this
 * service's own already-wired `EngagementEvent` `CONVERSION` stream (`AwardGamificationPointsUseCase`),
 * not a new Kafka topic invented for this ADR. `SavingsGoalDeposit`, `LoginStreak` and
 * `OnTimeRepayment` are declared because ADR-0220 D3 names them as the intended full signal set,
 * so the catalogue does not need widening the day a real consumer for one of them is built — but
 * as of this slice none of the three has a producing consumer wired in this service, and nothing
 * in this package attributes a [Points] award to them. Verify what is wired the same way the
 * `AdverseState` KDoc tells you to: the `@Incoming` consumers in `infrastructure/kafka`, not this
 * comment.
 */
sealed class EarnSource(val id: String) {
    object EducationalContentCompletion : EarnSource("EDUCATIONAL_CONTENT_COMPLETION")
    object SavingsGoalDeposit : EarnSource("SAVINGS_GOAL_DEPOSIT")
    object LoginStreak : EarnSource("LOGIN_STREAK")
    object OnTimeRepayment : EarnSource("ON_TIME_REPAYMENT")

    companion object {
        // `by lazy`, not an eager `val` — a known Kotlin/JVM class-init-order trap for a sealed
        // class whose companion references sibling nested `object` subclasses declared in the
        // SAME enclosing class: touching `EarnSource.ALL` first triggers `EarnSource`'s <clinit>,
        // which can run the companion's initializer before every nested object singleton has
        // finished assigning its own static field, so an eager list captures `null` entries
        // (measured directly: `EarnSourceTest` failed with `NullPointerException` /
        // `NoWhenBranchMatchedException` against the eager form before this fix). Deferring with
        // `lazy` runs the list-build after the class is fully initialized, at first real access.
        val ALL: List<EarnSource> by lazy {
            listOf(EducationalContentCompletion, SavingsGoalDeposit, LoginStreak, OnTimeRepayment)
        }

        fun byId(id: String): EarnSource? = ALL.find { it.id == id }
    }
}
