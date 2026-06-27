// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep

import com.openbank.finrep.application.port.out.TrialBalanceLineDto
import com.openbank.finrep.domain.mapper.F0101Mapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class F0101MapperTest {

    @Test
    fun `F01_01 maps assets liabilities and derives equity`() {
        val lines = listOf(
            TrialBalanceLineDto(code = "1000", accountType = "ASSET", net = BigDecimal("500000")),
            TrialBalanceLineDto(code = "2000", accountType = "LIABILITY", net = BigDecimal("300000")),
        )
        val template = F0101Mapper.map(lines, LocalDate.of(2026, 6, 30))

        assertThat(template.templateId).isEqualTo("F01.01")
        assertThat(template.cells).anyMatch { it.rowRef == "r010" && it.value == BigDecimal("500000") }
        assertThat(template.cells).anyMatch { it.rowRef == "r380" && it.value == BigDecimal("300000") }
        assertThat(template.cells).anyMatch { it.rowRef == "r490" && it.value == BigDecimal("200000") }
    }
}
