// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.finance.deal

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class DealKindTest {
    @Test
    fun `parses the published names, absent means ACTUAL, unknown is refused`() {
        assertThat(DealKind.parse(" planned ")).isEqualTo(DealKind.PLANNED)
        assertThat(DealKind.parse("HYPOTHETICAL")).isEqualTo(DealKind.HYPOTHETICAL)
        assertThat(DealKind.parse(null)).isEqualTo(DealKind.ACTUAL)
        assertThatThrownBy { DealKind.parse("FORECAST") }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
