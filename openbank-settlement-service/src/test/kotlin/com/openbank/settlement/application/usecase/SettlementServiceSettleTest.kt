// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.application.usecase

import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.settlement.application.port.out.CreditPort
import com.openbank.settlement.application.port.out.DebitPort
import com.openbank.settlement.application.port.out.LedgerPort
import com.openbank.settlement.application.port.out.SettlementRepository
import com.openbank.settlement.domain.model.Settlement
import com.openbank.settlement.domain.model.SettlementStatus
import com.openbank.settlement.infrastructure.temporal.TemporalConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.temporal.client.WorkflowClient
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Coverage for [SettlementService.settle]: the legacy-path claim race and the not-found guard.
 * The Temporal-enabled dispatch path is covered separately in SettlementServiceTemporalSettleTest
 * (driven against a real in-memory TestWorkflowEnvironment rather than mocking Temporal's static
 * [WorkflowClient.start] entry point). SettlementServiceOriginateTest covers originate() with
 * Temporal disabled.
 */
class SettlementServiceSettleTest {

    private val repo: SettlementRepository = mockk(relaxed = true)
    private val debitPort: DebitPort = mockk(relaxed = true)
    private val creditPort: CreditPort = mockk(relaxed = true)
    private val ledgerPort: LedgerPort = mockk(relaxed = true)
    private val temporalConfig: TemporalConfig = mockk(relaxed = true)
    private val auditPublisher: AuditEventPublisher = mockk(relaxed = true)

    private fun pendingSettlement(id: UUID = UUID.randomUUID()) = Settlement(
        id = id,
        payerAccountId = UUID.randomUUID(),
        payeeAccountId = UUID.randomUUID(),
        amount = BigDecimal("10.00"),
        currency = "CZK",
        status = SettlementStatus.PENDING,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )

    @Test
    fun `settle throws when the settlement does not exist`() {
        val workflowClient: WorkflowClient = mockk(relaxed = true)
        val service = SettlementService(
            repo,
            debitPort,
            creditPort,
            ledgerPort,
            temporalConfig,
            workflowClient,
            auditPublisher,
        )
        val id = UUID.randomUUID()
        coEvery { repo.findById(id) } returns null

        assertThatThrownBy { runBlocking { service.settle(id) } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining(id.toString())
    }

    @Test
    fun `settle runs the legacy saga and reaches BOOKED when Temporal is disabled`() {
        val workflowClient: WorkflowClient = mockk(relaxed = true)
        val service = SettlementService(
            repo,
            debitPort,
            creditPort,
            ledgerPort,
            temporalConfig,
            workflowClient,
            auditPublisher,
        )
        val settlement = pendingSettlement()
        coEvery { repo.findById(settlement.id) } returns settlement
        every { temporalConfig.enabled() } returns false
        coEvery { repo.claimForProcessing(settlement.id) } returns true
        coEvery { repo.updateStatus(settlement.id, any()) } returns settlement

        val result = runBlocking { service.settle(settlement.id) }

        assertThat(result).isEqualTo(SettlementStatus.BOOKED)
        coVerify { debitPort.debit(settlement.id) }
        coVerify { creditPort.credit(settlement.id) }
        coVerify { ledgerPort.book(settlement.id) }
    }

    @Test
    fun `settle loses the legacy claim race and returns the current status without re-processing`() {
        val workflowClient: WorkflowClient = mockk(relaxed = true)
        val service = SettlementService(
            repo,
            debitPort,
            creditPort,
            ledgerPort,
            temporalConfig,
            workflowClient,
            auditPublisher,
        )
        val settlement = pendingSettlement().copy(status = SettlementStatus.DEBITED)
        coEvery { repo.findById(settlement.id) } returnsMany listOf(
            settlement.copy(status = SettlementStatus.PENDING),
            settlement,
        )
        every { temporalConfig.enabled() } returns false
        coEvery { repo.claimForProcessing(settlement.id) } returns false

        val result = runBlocking { service.settle(settlement.id) }

        assertThat(result).isEqualTo(SettlementStatus.DEBITED)
        coVerify(exactly = 0) { debitPort.debit(any()) }
    }

    @Test
    fun `settle rejects when the legacy saga throws mid-flight`() {
        val workflowClient: WorkflowClient = mockk(relaxed = true)
        val service = SettlementService(
            repo,
            debitPort,
            creditPort,
            ledgerPort,
            temporalConfig,
            workflowClient,
            auditPublisher,
        )
        val settlement = pendingSettlement()
        coEvery { repo.findById(settlement.id) } returns settlement
        every { temporalConfig.enabled() } returns false
        coEvery { repo.claimForProcessing(settlement.id) } returns true
        coEvery { debitPort.debit(settlement.id) } throws RuntimeException("balance-service down")
        coEvery { repo.updateStatus(settlement.id, SettlementStatus.REJECTED) } returns settlement

        val result = runBlocking { service.settle(settlement.id) }

        assertThat(result).isEqualTo(SettlementStatus.REJECTED)
        coVerify { repo.updateStatus(settlement.id, SettlementStatus.REJECTED) }
        coVerify(exactly = 0) { creditPort.credit(any()) }
    }
}
