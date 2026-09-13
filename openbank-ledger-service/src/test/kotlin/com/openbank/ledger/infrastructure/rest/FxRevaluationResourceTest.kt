// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure.rest

import com.openbank.ledger.application.port.`in`.FxRevaluationResult
import com.openbank.ledger.application.port.`in`.FxRevaluationUseCase
import com.openbank.ledger.application.port.`in`.RevalueFxCommand
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * Regression for #8832: the api-fuzz lane found `POST /api/v1/ledger/fx-revaluation?date=…`
 * variants answering 500. A blank `date` must mean "today (Europe/Prague)", and a malformed one is
 * a client error — [IllegalArgumentException] so libs-runtime renders 400, never a raw
 * [java.time.format.DateTimeParseException] surfacing as a 500.
 */
class FxRevaluationResourceTest {

    private class FakeUseCase : FxRevaluationUseCase {
        var seenDate: LocalDate? = null

        override suspend fun revalue(command: RevalueFxCommand): FxRevaluationResult {
            seenDate = command.date
            return FxRevaluationResult(command.date, posted = false, journalId = null, movements = emptyMap())
        }
    }

    @Test
    fun `a blank date falls back to today instead of throwing`(): Unit = runBlocking {
        val useCase = FakeUseCase()
        val response = FxRevaluationResource(useCase).revalue("")

        assertEquals(200, response.status)
        assertEquals(LocalDate.now(java.time.ZoneId.of("Europe/Prague")), useCase.seenDate)
    }

    @Test
    fun `a malformed date is an IllegalArgumentException, not a DateTimeParseException`(): Unit = runBlocking {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { FxRevaluationResource(FakeUseCase()).revalue("32.13._2026") }
        }
        assertEquals(true, ex.message?.contains("date"))
    }

    @Test
    fun `a well-formed date is parsed and passed through`(): Unit = runBlocking {
        val useCase = FakeUseCase()
        FxRevaluationResource(useCase).revalue("2026-08-31")

        assertEquals(LocalDate.of(2026, 8, 31), useCase.seenDate)
    }
}
