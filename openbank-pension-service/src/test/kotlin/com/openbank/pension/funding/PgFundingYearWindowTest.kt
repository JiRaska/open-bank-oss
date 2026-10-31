// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.funding

import com.openbank.pension.infrastructure.persistence.funding.taxYearWindow
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.DateTimeException
import java.time.LocalDate

class PgFundingYearWindowTest {
    @Test
    fun `tax year uses a valid exclusive next year`() {
        assertThat(taxYearWindow(2026)).isEqualTo(LocalDate.of(2026, 1, 1) to LocalDate.of(2027, 1, 1))
    }

    @Test
    fun `year beyond LocalDate bounds is rejected rather than wrapped`() {
        assertThatThrownBy { taxYearWindow(Int.MAX_VALUE) }.isInstanceOf(DateTimeException::class.java)
        assertThatThrownBy { taxYearWindow(LocalDate.MAX.year) }.isInstanceOf(DateTimeException::class.java)
    }
}
