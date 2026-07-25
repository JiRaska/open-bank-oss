// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.infrastructure.schedule

import com.openbank.fx.application.port.`in`.CnbIngestionResult
import com.openbank.fx.application.port.`in`.CnbRateIngestionUseCase
import com.openbank.fx.application.port.`in`.IngestCnbFixingCommand
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.LocalDate

class CnbRateIngestionSchedulerTest {

    private val useCase: CnbRateIngestionUseCase = mockk()
    private val scheduler = CnbRateIngestionScheduler(useCase)

    @Test
    fun `ingestDailyFixing requests the latest fixing (no explicit date)`() {
        coEvery { useCase.ingest(IngestCnbFixingCommand(date = null)) } returns
            CnbIngestionResult(LocalDate.of(2026, 5, 30), 104, 3, 0, listOf("EUR", "USD", "GBP"))

        runBlocking { scheduler.ingestDailyFixing() }

        coVerify(exactly = 1) { useCase.ingest(IngestCnbFixingCommand(date = null)) }
    }

    @Test
    fun `ingestDailyFixing swallows a use-case failure without propagating`() {
        coEvery { useCase.ingest(any()) } throws RuntimeException("ČNB feed unreachable")

        // Must not throw — the scheduler must never crash the Quarkus scheduler thread.
        runBlocking { scheduler.ingestDailyFixing() }

        coVerify(exactly = 1) { useCase.ingest(any()) }
    }
}
