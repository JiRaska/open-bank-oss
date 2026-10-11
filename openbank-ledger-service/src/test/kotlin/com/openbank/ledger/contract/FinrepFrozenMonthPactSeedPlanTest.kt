// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.ledger.contract

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.LocalDate

class FinrepFrozenMonthPactSeedPlanTest {
    @Test
    fun `legacy requests select one frozen month`() {
        assertThat(
            FinrepFrozenMonthPactSeed.requestPlan(
                listOf("/api/v1/ledger/periods", "/api/v1/ledger/periods/MONTH/2026-06-30/frozen-trial-balance"),
            ),
        )
            .isEqualTo(LocalDate.parse("2026-06-30") to false)
    }

    @Test
    fun `current requests select current year evidence`() {
        assertThat(
            FinrepFrozenMonthPactSeed.requestPlan(
                listOf(
                    "/api/v1/ledger/periods",
                    "/api/v1/ledger/periods/MONTH/2000-06-30/frozen-trial-balance",
                    "/api/v1/ledger/periods/MONTH/2000-06-30/frozen-year-to-date-trial-balance",
                ),
            ),
        )
            .isEqualTo(LocalDate.parse("2000-06-30") to true)
    }

    @Test
    fun `missing reporting month is refused`() {
        assertThatThrownBy { FinrepFrozenMonthPactSeed.requestPlan(listOf("/api/v1/ledger/periods")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `ambiguous reporting months are refused`() {
        assertThatThrownBy {
            FinrepFrozenMonthPactSeed.requestPlan(
                listOf("/MONTH/2000-06-30/frozen-trial-balance", "/MONTH/2026-06-30/frozen-trial-balance"),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
