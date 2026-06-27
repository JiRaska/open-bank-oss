// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep

import com.openbank.finrep.application.port.out.TrialBalanceLineDto
import com.openbank.finrep.domain.mapper.F0200Mapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class F0200MapperTest {

    @Test
    fun `F02_00 maps income expense and derives net profit`() {
        val lines = listOf(
            TrialBalanceLineDto(code = "4000", accountType = "INCOME", net = BigDecimal("120000")),
            TrialBalanceLineDto(code = "5000", accountType = "EXPENSE", net = BigDecimal("80000")),
        )
        val template = F0200Mapper.map(lines, LocalDate.of(2026, 6, 30))

        assertThat(template.templateId).isEqualTo("F02.00")
        assertThat(template.cells).anyMatch { it.rowRef == "r010" && it.value == BigDecimal("120000") }
        assertThat(template.cells).anyMatch { it.rowRef == "r030" && it.value == BigDecimal("80000") }
        assertThat(template.cells).anyMatch { it.rowRef == "r450" && it.value == BigDecimal("40000") }
    }
}
