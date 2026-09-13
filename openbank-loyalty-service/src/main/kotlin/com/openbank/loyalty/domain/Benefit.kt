// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.loyalty.domain

import java.time.Duration

/**
 * Which bank-side engine actually delivers a benefit once it is granted (ADR-0282 D4).
 *
 * Naming the engine on the catalogue entry is what keeps this service honest about its own reach.
 * All three engines live in money-path services, and **this service calls none of them in this
 * slice**: a grant here is a durable, reviewable instruction that the benefit is owed, not
 * evidence that it was applied. [BenefitGrant.status] says which of the two has happened, and the
 * two are different enum values on purpose — a granted-but-undelivered benefit must never read as
 * a delivered one.
 */
enum class BenefitEngine {
    /** A fee waiver applied by `openbank-billing-service` through `WaiverEvaluator` (ADR-0143). */
    FEE_WAIVER,

    /**
     * An interest bonus expressed as an `InterestTier` on the catalog product and read by
     * `openbank-interest-service` through `CatalogInterestProfile`.
     */
    INTEREST_BONUS,

    /** One conversion at the reference rate with no margin, applied by `openbank-fx-service`. */
    FX_REFERENCE_RATE,
}

/**
 * One reviewed benefit a party may redeem Lístky for. A catalogue entry is a pull request — never
 * free text, never an admin-ui action (ADR-0282 D4).
 *
 * [price] is denominated in [Leaves] and in nothing else. There is deliberately no currency field
 * and no exchange rate anywhere in this file: a Lístek that had a price in korunas would be a
 * unit of account, and ADR-0282 D1's closed loop depends on it not being one.
 */
data class Benefit(
    val id: String,
    val engine: BenefitEngine,
    val price: Leaves,
    val validity: Duration,
    val description: String,
) {
    init {
        require(id.isNotBlank()) { "benefit id must not be blank" }
        require(!price.isZero()) { "a benefit priced at zero leaves is not a redemption" }
        require(!validity.isNegative && !validity.isZero) { "benefit '$id' has non-positive validity" }
        require(description.isNotBlank()) { "benefit '$id' must describe what the party receives" }
    }
}

/** The reviewed benefit catalogue — ADR-0282 D4's first three entries, one per engine. */
object BenefitCatalog {
    val ALL: Map<String, Benefit> = listOf(
        Benefit(
            id = "MONTHLY_MAINTENANCE_FEE_WAIVER",
            engine = BenefitEngine.FEE_WAIVER,
            price = Leaves.of(FEE_WAIVER_PRICE),
            validity = Duration.ofDays(NINETY_DAYS),
            description = "Monthly account maintenance fee waived for one billing cycle",
        ),
        Benefit(
            id = "SAVINGS_RATE_BONUS_90D",
            engine = BenefitEngine.INTEREST_BONUS,
            price = Leaves.of(INTEREST_BONUS_PRICE),
            validity = Duration.ofDays(NINETY_DAYS),
            description = "A bonus savings interest tier for 90 days",
        ),
        Benefit(
            id = "FX_REFERENCE_RATE_ONE_CONVERSION",
            engine = BenefitEngine.FX_REFERENCE_RATE,
            price = Leaves.of(FX_PRICE),
            validity = Duration.ofDays(THIRTY_DAYS),
            description = "One currency conversion at the reference rate with no bank margin",
        ),
    ).associateBy { it.id }

    fun find(id: String): Benefit? = ALL[id]

    private const val FEE_WAIVER_PRICE = 300
    private const val INTEREST_BONUS_PRICE = 800
    private const val FX_PRICE = 450
    private const val NINETY_DAYS = 90L
    private const val THIRTY_DAYS = 30L
}
