// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.application.usecase

import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.settlement.application.port.`in`.OriginateSettlementCommand
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
import io.mockk.slot
import io.temporal.client.WorkflowClient
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class SettlementServiceOriginateTest {

    private val repo: SettlementRepository = mockk(relaxed = true)
    private val debitPort: DebitPort = mockk(relaxed = true)
    private val creditPort: CreditPort = mockk(relaxed = true)
    private val ledgerPort: LedgerPort = mockk(relaxed = true)
    private val temporalConfig: TemporalConfig = mockk(relaxed = true)
    private val workflowClient: WorkflowClient = mockk(relaxed = true)
    private val auditPublisher: AuditEventPublisher = mockk(relaxed = true)

    private val service = SettlementService(
        repo,
        debitPort,
        creditPort,
        ledgerPort,
        temporalConfig,
        workflowClient,
        auditPublisher,
    )

    @Test
    fun `originate persists a PENDING settlement from the command and starts settlement`() {
        // Temporal off → settle() runs the legacy in-process saga (ports are relaxed mocks).
        every { temporalConfig.enabled() } returns false
        val created = slot<Settlement>()
        // No existing settlement for the key (dedup miss) until create captures it.
        coEvery { repo.findById(any()) } answers { if (created.isCaptured) created.captured else null }
        coEvery { repo.create(capture(created)) } answers { created.captured }
        // This caller wins the atomic PENDING → DEBITED claim, so the legacy saga proceeds.
        coEvery { repo.claimForProcessing(any()) } returns true

        val payer = UUID.randomUUID()
        val payee = UUID.randomUUID()
        val result = runBlocking {
            service.originate(OriginateSettlementCommand("e2e-key-1", payer, payee, BigDecimal("250.00"), "CZK"))
        }

        // The persisted settlement reflects the command and starts PENDING.
        assertThat(created.captured.payerAccountId).isEqualTo(payer)
        assertThat(created.captured.payeeAccountId).isEqualTo(payee)
        assertThat(created.captured.amount).isEqualByComparingTo(BigDecimal("250.00"))
        assertThat(created.captured.currency).isEqualTo("CZK")
        assertThat(created.captured.status).isEqualTo(SettlementStatus.PENDING)
        assertThat(result.id).isEqualTo(created.captured.id)

        // settle() was invoked (legacy path debits the payer).
        coVerify { repo.create(any()) }
        coVerify { debitPort.debit(created.captured.id) }
    }

    @Test
    fun `originate is idempotent — a repeated key returns the original without re-settling`() {
        every { temporalConfig.enabled() } returns false
        val now = Instant.now()
        val existing = Settlement(
            id = UUID.nameUUIDFromBytes("settlement:dup-key".toByteArray()),
            payerAccountId = UUID.randomUUID(),
            payeeAccountId = UUID.randomUUID(),
            amount = BigDecimal("10.00"),
            currency = "CZK",
            status = SettlementStatus.BOOKED,
            createdAt = now,
            updatedAt = now,
        )
        coEvery { repo.findById(existing.id) } returns existing

        val result = runBlocking {
            service.originate(
                OriginateSettlementCommand("dup-key", UUID.randomUUID(), UUID.randomUUID(), BigDecimal("10.00"), "CZK"),
            )
        }

        assertThat(result.id).isEqualTo(existing.id)
        coVerify(exactly = 0) { repo.create(any()) }
        coVerify(exactly = 0) { debitPort.debit(any()) }
    }
}
