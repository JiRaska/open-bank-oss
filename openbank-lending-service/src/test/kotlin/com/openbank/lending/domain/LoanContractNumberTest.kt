// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.lending.domain

import com.openbank.lending.domain.model.LoanContractNumber
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class LoanContractNumberTest {

    @Test
    fun `formats prefix, year and a six-digit zero-padded sequence`() {
        assertThat(LoanContractNumber.format(2026, 123)).isEqualTo("UV-2026-000123")
        assertThat(LoanContractNumber.format(2026, 1)).isEqualTo("UV-2026-000001")
        assertThat(LoanContractNumber.format(2026, 999_999)).isEqualTo("UV-2026-999999")
    }

    @Test
    fun `a sequence past six digits grows instead of being truncated`() {
        // Postgres lpad() truncates; V24's format function guards against it, and so must this.
        assertThat(LoanContractNumber.format(2026, 1_000_000)).isEqualTo("UV-2026-1000000")
        assertThat(LoanContractNumber.isValid("UV-2026-1000000")).isTrue()
    }

    @Test
    fun `validates the shape the database CHECK enforces`() {
        assertThat(LoanContractNumber.isValid("UV-2026-000123")).isTrue()
        assertThat(LoanContractNumber.isValid("UV-2026-12345")).isFalse()
        assertThat(LoanContractNumber.isValid("UV-26-000123")).isFalse()
        assertThat(LoanContractNumber.isValid("XX-2026-000123")).isFalse()
        assertThat(LoanContractNumber.isValid("UV-2026-000123 ")).isFalse()
        assertThat(LoanContractNumber.yearOf("UV-2031-000007")).isEqualTo(2031)
        assertThat(LoanContractNumber.yearOf("nope")).isNull()
    }

    @Test
    fun `rejects a non-positive sequence and a year without four digits`() {
        assertThatThrownBy { LoanContractNumber.format(2026, 0) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { LoanContractNumber.format(999, 1) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { LoanContractNumber.format(10_000, 1) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
