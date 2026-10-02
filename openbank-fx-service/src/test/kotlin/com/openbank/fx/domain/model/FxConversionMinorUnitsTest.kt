// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.math.BigDecimal

/**
 * Pinned conversions across currencies whose minor-unit exponents differ. Before the fix,
 * source minor units were multiplied straight into target minor units, so EUR 100.00 -> JPY at
 * 160 came out as 1,600,000 yen instead of 16,000 (verified failing on the old code).
 */
class FxConversionMinorUnitsTest {

    @ParameterizedTest(name = "{0} {1} -> {2} at {3} = {4}")
    @CsvSource(
        // same-digit pairs: unchanged from the old arithmetic
        "10000, EUR, CZK, 25.15,      251500",
        "251500, CZK, EUR, 0.03976143, 10000",
        "12345, USD, EUR, 0.9200,     11357",
        // EUR(2) <-> JPY(0)
        "10000, EUR, JPY, 160,        16000",
        "10001, EUR, JPY, 160.5,      16052",
        "16000, JPY, EUR, 0.00625,    10000",
        "1, JPY, EUR, 0.00625,        1",
        // CZK(2) -> JPY(0) and JPY -> CZK
        "100000, CZK, JPY, 6.4,       6400",
        "6400, JPY, CZK, 0.15625,     100000",
        // three-digit minor units: EUR(2) -> KWD(3)
        "10000, EUR, KWD, 0.33,       33000",
    )
    fun `converts between currencies by both fraction digits`(
        fromMinor: Long,
        from: String,
        to: String,
        rate: String,
        expectedToMinor: Long,
    ) {
        assertThat(FxConversionMath.convertedAmountMinorUnits(fromMinor, from, to, BigDecimal(rate)))
            .isEqualTo(expectedToMinor)
    }

    @ParameterizedTest
    @CsvSource("10000, 50", "1, 0", "100, 1")
    fun `fee stays in source minor units`(fromMinor: Long, expectedFee: Long) {
        assertThat(FxConversionMath.feeMinorUnits(fromMinor)).isEqualTo(expectedFee)
    }
}
