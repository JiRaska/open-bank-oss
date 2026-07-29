// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.statement.application.usecase

import com.openbank.statement.Fixtures
import com.openbank.statement.application.port.`in`.ClosePocketUseCase
import com.openbank.statement.application.port.out.AccountInfoPort
import com.openbank.statement.application.port.out.AccountRegistry
import com.openbank.statement.application.port.out.CloseMetricsPort
import com.openbank.statement.application.port.out.CloseRunRepository
import com.openbank.statement.application.port.out.StatementOutbox
import com.openbank.statement.application.port.out.StatementPeriodRepository
import com.openbank.statement.domain.model.CloseRun
import com.openbank.statement.domain.model.CloseRunStatus
import com.openbank.statement.domain.model.CloseTrigger
import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class CloseOrchestratorTest {

    private val accountRegistry = mockk<AccountRegistry>()
    private val accountInfo = mockk<AccountInfoPort>()
    private val periods = mockk<StatementPeriodRepository>()
    private val closePocket = mockk<ClosePocketUseCase>()
    private val runs = mockk<CloseRunRepository>(relaxed = true)
    private val outbox = mockk<StatementOutbox>(relaxed = true)
    private val metrics = mockk<CloseMetricsPort>(relaxed = true)

    private lateinit var orchestrator: CloseOrchestrator

    @BeforeEach
    fun setUp() {
        orchestrator =
            CloseOrchestrator(
                accountRegistry,
                accountInfo,
                periods,
                closePocket,
                runs,
                outbox,
                metrics,
                Clock.systemUTC(),
            )
        orchestrator.clock = { Instant.parse("2026-06-20T10:00:00Z") }
        orchestrator.today = { LocalDate.parse("2026-06-20") }

        val stubRun = CloseRun(
            id = UUID.randomUUID(), trigger = CloseTrigger.SCHEDULED, status = CloseRunStatus.RUNNING,
            periodFrom = LocalDate.parse("2026-05-01"), periodTo = LocalDate.parse("2026-05-31"),
            accountsEnumerated = 0, pocketsClosed = 0, pocketsFailed = 0, pocketsSkipped = 0,
            startedAt = Instant.parse("2026-06-20T10:00:00Z"), finishedAt = null,
        )
        every { runs.startRun(any()) } returns Uni.createFrom().item(stubRun)
        every { runs.finishRun(any()) } answers { Uni.createFrom().item(firstArg<CloseRun>()) }
    }

    @Test
    fun `debris account with blank IBAN is counted as skipped not failed`() {
        val accountId = Fixtures.ACCOUNT_ID
        every { accountRegistry.allAccountIds() } returns Uni.createFrom().item(listOf(accountId))
        every { accountInfo.pocketAccount(accountId) } returns
            Uni.createFrom().failure(NotViableAccountException(accountId, "blank IBAN — debris account (#862)"))

        val result = orchestrator.runClose(CloseTrigger.SCHEDULED).subscribe().withSubscriber(
            io.smallrye.mutiny.helpers.test.UniAssertSubscriber.create(),
        ).awaitItem().item

        assertThat(result.status).isEqualTo(CloseRunStatus.COMPLETED)
        assertThat(result.pocketsSkipped).isEqualTo(1)
        assertThat(result.pocketsFailed).isEqualTo(0)
    }

    @Test
    fun `transient upstream failure is counted as failed not skipped`() {
        val accountId = UUID.randomUUID()
        every { accountRegistry.allAccountIds() } returns Uni.createFrom().item(listOf(accountId))
        every { accountInfo.pocketAccount(accountId) } returns
            Uni.createFrom().failure(RuntimeException("connection refused"))
        every { runs.recordFailure(any()) } returns Uni.createFrom().item(mockk(relaxed = true))

        val result = orchestrator.runClose(CloseTrigger.SCHEDULED).subscribe().withSubscriber(
            io.smallrye.mutiny.helpers.test.UniAssertSubscriber.create(),
        ).awaitItem().item

        assertThat(result.status).isEqualTo(CloseRunStatus.COMPLETED_WITH_FAILURES)
        assertThat(result.pocketsFailed).isEqualTo(1)
        assertThat(result.pocketsSkipped).isEqualTo(0)
    }
}
