// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.infrastructure.rest

import com.openbank.balance.application.port.`in`.ReconcileBalancesUseCase
import com.openbank.balance.domain.reconciliation.ReconciliationReport
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Regression for #8832: the api-fuzz lane found `POST /api/v1/balances/reconciliation?asOf=`
 * answering 500 — a blank value reached `LocalDate.parse`, and a malformed one would have too. A
 * blank `asOf` means "omitted" (default today); a malformed one is a client error and must surface
 * as [IllegalArgumentException] so libs-runtime renders 400, never a raw [java.time.format.DateTimeParseException].
 */
class ReconciliationResourceTest {

    private val clock = Clock.fixed(Instant.parse("2026-09-05T00:00:00Z"), ZoneOffset.UTC)

    private class FakeUseCase : ReconcileBalancesUseCase {
        var seenAsOf: LocalDate? = null

        override suspend fun reconcile(asOf: LocalDate): ReconciliationReport {
            seenAsOf = asOf
            return ReconciliationReport(
                asOf = asOf,
                generatedAt = OffsetDateTime.parse("2026-09-05T00:00:00Z"),
                tolerance = BigDecimal.ZERO,
                currencies = emptyList(),
            )
        }

        override suspend fun latest(): ReconciliationReport? = null
    }

    @Test
    fun `a blank asOf falls back to today instead of throwing`(): Unit = runBlocking {
        val useCase = FakeUseCase()
        val response = ReconciliationResource(useCase, clock).run("")

        assertEquals(201, response.status)
        assertEquals(LocalDate.of(2026, 9, 5), useCase.seenAsOf)
    }

    @Test
    fun `a malformed asOf is an IllegalArgumentException, not a DateTimeParseException`(): Unit = runBlocking {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { ReconciliationResource(FakeUseCase(), clock).run("not-a-date") }
        }
        assertEquals(true, ex.message?.contains("asOf"))
    }

    @Test
    fun `a well-formed asOf is parsed and passed through`(): Unit = runBlocking {
        val useCase = FakeUseCase()
        ReconciliationResource(useCase, clock).run("2026-08-31")

        assertEquals(LocalDate.of(2026, 8, 31), useCase.seenAsOf)
    }
}
