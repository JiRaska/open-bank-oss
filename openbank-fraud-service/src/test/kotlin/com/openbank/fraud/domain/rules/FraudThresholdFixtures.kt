// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.domain.rules

import java.math.BigDecimal

/**
 * The six figures `FraudRuleEngine.kt` carried as constants before ADR-0347, written out literally
 * — never derived from the migration — so a drift in the `V9` seed shows up as a disagreement
 * (`FraudThresholdSeedIT` compares the two) instead of being copied into the expectation.
 */
object FraudThresholdFixtures {
    val SEED_V1 = FraudThresholdSet(
        version = 1,
        thresholds = mapOf(
            FraudThresholdSet.LARGE_SINGLE_TRANSACTION to
                mapOf("CZK" to BigDecimal("500000"), "EUR" to BigDecimal("20000")),
            FraudThresholdSet.VELOCITY_H1_AMOUNT to mapOf("CZK" to BigDecimal("1000000"), "EUR" to BigDecimal("40000")),
            FraudThresholdSet.NEW_PAYEE_HIGH_AMOUNT to
                mapOf("CZK" to BigDecimal("250000"), "EUR" to BigDecimal("10000")),
        ),
    )

    /** `V9__fraud_threshold_set.sql`'s content_hash literal. */
    const val SEED_V1_HASH = "928c0ecd3ef7bb12281210d5f33515de41936bb0bddb612368e139436ac1ccd4"
}
