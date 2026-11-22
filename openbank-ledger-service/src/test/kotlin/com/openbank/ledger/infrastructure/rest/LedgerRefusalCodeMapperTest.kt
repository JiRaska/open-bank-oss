// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.ledger.infrastructure.rest

import com.openbank.ledger.application.usecase.ClosedFiscalPeriodException
import com.openbank.ledger.application.usecase.ClosedPeriodConflictException
import com.openbank.ledger.application.usecase.FrozenPeriodException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The three posting locks are all 409; the `code` is what lets a caller tell them apart. */
class LedgerRefusalCodeMapperTest {

    @Test
    fun `frozen period 409 keeps the error text and adds a literal machine code`() {
        FrozenPeriodExceptionMapper().toResponse(FrozenPeriodException("period 2026-05 is FROZEN")).use { r ->
            assertThat(r.status).isEqualTo(409)
            assertThat(r.entity).isEqualTo(
                mapOf("error" to "period 2026-05 is FROZEN", "code" to "ACCOUNTING_PERIOD_FROZEN"),
            )
        }
    }

    @Test
    fun `closed fiscal year 409 carries its own distinct code`() {
        ClosedFiscalPeriodExceptionMapper().toResponse(ClosedFiscalPeriodException("year 2025 closed")).use { r ->
            assertThat(r.status).isEqualTo(409)
            assertThat(r.entity).isEqualTo(
                mapOf("error" to "year 2025 closed", "code" to "ACCOUNTING_YEAR_CLOSED"),
            )
        }
    }

    @Test
    fun `negative - an unrelated 409 stays uncoded so the code is not a catch-all`() {
        ClosedPeriodConflictExceptionMapper().toResponse(ClosedPeriodConflictException("hash drift")).use { r ->
            assertThat(r.status).isEqualTo(409)
            assertThat(r.entity).isEqualTo(mapOf("error" to "hash drift"))
        }
    }
}
