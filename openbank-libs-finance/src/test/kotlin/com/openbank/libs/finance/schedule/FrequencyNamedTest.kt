// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.finance.schedule

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FrequencyNamedTest {
    @Test
    fun `every named code round-trips and only named frequencies have a code`() {
        assertThat(
            Frequency.NAMED.keys,
        ).containsExactly("ONCE", "DAILY", "WEEKLY", "MONTHLY", "QUARTERLY", "SEMI_ANNUAL", "ANNUAL")
        Frequency.NAMED.forEach { (code, f) -> assertThat(Frequency.codeOf(f)).isEqualTo(code) }
        assertThat(Frequency.named("ONCE")).isEqualTo(Frequency.Zero)
        assertThat(Frequency.named("WEEKLY")).isEqualTo(Frequency.Weeks(1))
        assertThat(Frequency.codeOf(Frequency.Months(3))).isEqualTo("QUARTERLY")
        assertThat(Frequency.named("HOURLY")).isNull()
        assertThat(Frequency.codeOf(Frequency.Months(2))).isNull()
    }
}
