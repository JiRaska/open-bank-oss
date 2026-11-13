// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.application

import com.openbank.treasury.application.port.out.ExposureGuard
import com.openbank.treasury.domain.model.LimitCheck
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** The guard every limit-consuming write takes is built from LimitCheck's own figures. */
class ExposureGuardTest {
    @Test
    fun `an FX check guards the CZK line and nets the CZK counter-amount`() {
        // LimitCheck.of puts an FX spot on its limit currency (CZK) with its counter-amount.
        val fx = LimitCheck("SIMBK-C", "CZK", BigDecimal("500000000.00"), BigDecimal.ZERO, BigDecimal("300000000.00"))
        val g = ExposureGuard.of(fx)
        assertThat(g.currency).isEqualTo("CZK")
        assertThat(g.maxOthers).isEqualByComparingTo("200000000.00")
    }

    @Test
    fun `an EUR check guards the EUR line, and a senior override bound replaces the limit`() {
        val eur = LimitCheck("SIMBK-C", "EUR", BigDecimal("10000000.00"), BigDecimal.ZERO, BigDecimal("4000000.00"))
        assertThat(ExposureGuard.of(eur).currency).isEqualTo("EUR")
        assertThat(ExposureGuard.of(eur, BigDecimal("12000000.00")).maxOthers).isEqualByComparingTo("8000000.00")
    }
}
