// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * ADR-0252 phase 1 (#8615). The default is the whole safety argument for choosing a dimension over
 * dedicated GL accounts, so it is asserted rather than assumed.
 */
class LedgerScopeTest {

    @Test
    fun `an absent or blank selector means REAL_ONLY`() {
        assertThat(LedgerScope.parse(null)).isEqualTo(LedgerScope.REAL_ONLY)
        assertThat(LedgerScope.parse("")).isEqualTo(LedgerScope.REAL_ONLY)
        assertThat(LedgerScope.parse("   ")).isEqualTo(LedgerScope.REAL_ONLY)
    }

    @Test
    fun `each population can be selected explicitly, case-insensitively`() {
        assertThat(LedgerScope.parse("REAL_ONLY")).isEqualTo(LedgerScope.REAL_ONLY)
        assertThat(LedgerScope.parse("synthetic_only")).isEqualTo(LedgerScope.SYNTHETIC_ONLY)
        assertThat(LedgerScope.parse(" All ")).isEqualTo(LedgerScope.ALL)
    }

    @Test
    fun `an unrecognised selector is refused, never silently answered as real`() {
        // A typo answering REAL_ONLY would hand a caller data they did not ask for and could not
        // tell apart from data they did. libs-runtime maps this to 400.
        assertThatThrownBy { LedgerScope.parse("rael") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("rael")
    }
}
