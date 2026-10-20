// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.domain.rules

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class FraudThresholdSetTest {

    private val seed = FraudThresholdFixtures.SEED_V1.thresholds

    @Test
    fun `seed content hash matches the literal in the V9 migration`() {
        assertThat(FraudThresholdFixtures.SEED_V1.contentHash).isEqualTo(FraudThresholdFixtures.SEED_V1_HASH)
    }

    @Test
    fun `content hash ignores map order, trailing zeros and the version`() {
        val reordered = FraudThresholdSet(
            version = 9,
            thresholds = seed.entries.reversed().associate { (rule, byCcy) ->
                rule to byCcy.entries.reversed().associate { (ccy, amount) -> ccy to amount.setScale(4) }
            },
        )
        assertThat(reordered.contentHash).isEqualTo(FraudThresholdFixtures.SEED_V1_HASH)
    }

    @Test
    fun `content hash changes when any figure changes`() {
        val changed = FraudThresholdSet(
            1,
            seed + (FraudThresholdSet.VELOCITY_H1_AMOUNT to mapOf("CZK" to BigDecimal("1000001"))),
        )
        assertThat(changed.contentHash).isNotEqualTo(FraudThresholdFixtures.SEED_V1_HASH)
    }

    @Test
    fun `rejects an unknown or a missing rule key`() {
        assertThatThrownBy { FraudThresholdSet(1, seed + ("made-up-rule" to emptyMap())) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { FraudThresholdSet(1, seed - FraudThresholdSet.NEW_PAYEE_HIGH_AMOUNT) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `rejects a non-ISO currency, a non-positive amount, excess scale and version 0`() {
        fun with(ccy: String, amount: String) =
            seed + (FraudThresholdSet.LARGE_SINGLE_TRANSACTION to mapOf(ccy to BigDecimal(amount)))
        assertThatThrownBy { FraudThresholdSet(1, with("czk", "1")) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { FraudThresholdSet(1, with("CZK", "0")) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            FraudThresholdSet(1, with("CZK", "-5"))
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            FraudThresholdSet(1, with("CZK", "1.00001"))
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { FraudThresholdSet(0, seed) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `an empty currency map is legal and means fail closed for every currency`() {
        val set = FraudThresholdSet(1, seed + (FraudThresholdSet.LARGE_SINGLE_TRANSACTION to emptyMap()))
        assertThat(set.thresholdFor(FraudThresholdSet.LARGE_SINGLE_TRANSACTION, "CZK")).isNull()
    }
}
