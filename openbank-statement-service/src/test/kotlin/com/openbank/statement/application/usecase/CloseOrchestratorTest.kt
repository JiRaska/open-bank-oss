// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.statement.application.usecase

import com.openbank.libs.testing.audit.AuditEventTime
import com.openbank.statement.Fixtures
import com.openbank.statement.application.port.`in`.ClosePocketUseCase
import com.openbank.statement.application.port.out.AccountInfoPort
import com.openbank.statement.application.port.out.AccountRegistry
import com.openbank.statement.application.port.out.CloseMetricsPort
import com.openbank.statement.application.port.out.CloseRunRepository
import com.openbank.statement.application.port.out.PocketAccountInfo
import com.openbank.statement.application.port.out.StatementOutbox
import com.openbank.statement.application.port.out.StatementOutboxMessage
import com.openbank.statement.application.port.out.StatementPeriodRepository
import com.openbank.statement.domain.model.CloseFailure
import com.openbank.statement.domain.model.CloseRun
import com.openbank.statement.domain.model.CloseRunStatus
import com.openbank.statement.domain.model.CloseTrigger
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
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

    /**
     * #3914: red before the payload gained `occurredAt`. `failedAt` and `occurredAt` must be the
     * SAME clock read, not two — one failure has one "when", and two reads would let them drift
     * under load while looking correct in a fixed-clock test.
     */
    @Test
    fun `the close-failed payload carries the failure instant as the audit event time`() {
        // The close_failed event is emitted only from the PER-POCKET failure path — a failure at
        // accountInfo.pocketAccount (as the test above uses) is recorded but emits no event. The
        // stub therefore has to fail inside closePocketMonth, which is where emitCloseFailed lives.
        val accountId = UUID.randomUUID()
        every { accountRegistry.allAccountIds() } returns Uni.createFrom().item(listOf(accountId))
        every { accountInfo.pocketAccount(accountId) } returns
            Uni.createFrom().item(PocketAccountInfo(accountId, "CZ6500000000000000000001", "Holder", listOf("CZK")))
        every { periods.latestClosedPeriodTo(accountId, "CZK") } returns Uni.createFrom().nullItem()
        every { closePocket.closePocketMonth(accountId, "CZK", any(), any()) } returns
            Uni.createFrom().failure(RuntimeException("connection refused"))
        // Echo the real CloseFailure back rather than a relaxed mock: emitCloseFailed is chained
        // off this Uni with flatMap, so a stub that does not actually EMIT leaves the outbox
        // append unreached and the test green-looking for the wrong reason.
        every { runs.recordFailure(any()) } answers { Uni.createFrom().item(firstArg<CloseFailure>()) }
        val msg = slot<StatementOutboxMessage>()
        every { outbox.append(any()) } returns Uni.createFrom().voidItem()

        orchestrator.runClose(CloseTrigger.SCHEDULED).subscribe().withSubscriber(
            io.smallrye.mutiny.helpers.test.UniAssertSubscriber.create(),
        ).awaitItem()

        verify { outbox.append(capture(msg)) }
        AuditEventTime.assertRecordedAsEventTime(msg.captured.payload, Instant.parse("2026-06-20T10:00:00Z"))
    }
}
