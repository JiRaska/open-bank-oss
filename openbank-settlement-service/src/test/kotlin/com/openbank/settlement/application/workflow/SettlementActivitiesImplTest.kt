// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.application.workflow

import com.openbank.settlement.application.port.out.CreditPort
import com.openbank.settlement.application.port.out.DebitPort
import com.openbank.settlement.application.port.out.LedgerPort
import com.openbank.settlement.application.port.out.SettlementRepository
import com.openbank.settlement.domain.model.SettlementStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class SettlementActivitiesImplTest {

    private val settlementRepository: SettlementRepository = mockk(relaxed = true)
    private val debitPort: DebitPort = mockk(relaxed = true)
    private val creditPort: CreditPort = mockk(relaxed = true)
    private val ledgerPort: LedgerPort = mockk(relaxed = true)

    private lateinit var activities: SettlementActivitiesImpl

    @BeforeEach
    fun setUp() {
        // TestableActivities overrides runOnVertxContext to run synchronously — the production impl
        // needs a real Vert.x context (VertxContextSupport), which a plain unit test does not have.
        activities = TestableActivities(settlementRepository, debitPort, creditPort, ledgerPort)
        coEvery { settlementRepository.updateStatus(any(), any()) } returns mockk()
    }

    @Test
    fun `debitPayer calls debit port and sets DEBITED status`() {
        val id = UUID.randomUUID()
        activities.debitPayer(id)
        coVerify { debitPort.debit(id) }
        coVerify { settlementRepository.updateStatus(id, SettlementStatus.DEBITED) }
    }

    @Test
    fun `creditPayee calls credit port and sets CREDITED status`() {
        val id = UUID.randomUUID()
        activities.creditPayee(id)
        coVerify { creditPort.credit(id) }
        coVerify { settlementRepository.updateStatus(id, SettlementStatus.CREDITED) }
    }

    @Test
    fun `bookToLedger calls ledger port and sets BOOKED status`() {
        val id = UUID.randomUUID()
        activities.bookToLedger(id)
        coVerify { ledgerPort.book(id) }
        coVerify { settlementRepository.updateStatus(id, SettlementStatus.BOOKED) }
    }

    @Test
    fun `reverseDebit does not call debit port and sets REVERSED status`() {
        val id = UUID.randomUUID()
        activities.reverseDebit(id)
        coVerify(exactly = 0) { debitPort.debit(any()) }
        coVerify { settlementRepository.updateStatus(id, SettlementStatus.REVERSED) }
    }

    @Test
    fun `reverseCredit does not call credit port and sets CREDITED_REVERSED status`() {
        val id = UUID.randomUUID()
        activities.reverseCredit(id)
        coVerify(exactly = 0) { creditPort.credit(any()) }
        coVerify { settlementRepository.updateStatus(id, SettlementStatus.CREDITED_REVERSED) }
    }

    @Test
    fun `reverseBookToLedger does not call ledger port and sets LEDGER_REVERSED status`() {
        val id = UUID.randomUUID()
        activities.reverseBookToLedger(id)
        coVerify(exactly = 0) { ledgerPort.book(any()) }
        coVerify { settlementRepository.updateStatus(id, SettlementStatus.LEDGER_REVERSED) }
    }

    @Test
    fun `rejectSettlement sets REJECTED status without port calls`() {
        val id = UUID.randomUUID()
        activities.rejectSettlement(id)
        coVerify(exactly = 0) { debitPort.debit(any()) }
        coVerify(exactly = 0) { creditPort.credit(any()) }
        coVerify { settlementRepository.updateStatus(id, SettlementStatus.REJECTED) }
    }
}

/** Runs the activity bodies synchronously, bypassing the real Vert.x-context bridge. */
private class TestableActivities(
    settlementRepository: SettlementRepository,
    debitPort: DebitPort,
    creditPort: CreditPort,
    ledgerPort: LedgerPort,
) : SettlementActivitiesImpl(settlementRepository, debitPort, creditPort, ledgerPort) {
    override fun <T> runOnVertxContext(block: suspend () -> T): T = runBlocking { block() }
}
