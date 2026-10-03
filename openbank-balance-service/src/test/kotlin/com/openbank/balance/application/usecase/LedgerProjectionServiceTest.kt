// SPDX-License-Identifier: Apache-2.0
package com.openbank.balance.application.usecase

import com.openbank.balance.application.port.`in`.AccountBookedChange
import com.openbank.balance.application.port.out.LedgerProjectionPort
import com.openbank.balance.domain.model.Balance
import com.openbank.libs.observability.DomainMetrics
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class LedgerProjectionServiceTest {
    private val change = AccountBookedChange(
        UUID.randomUUID(),
        "CZK",
        BigDecimal.TEN,
        UUID.randomUUID(),
        UUID.randomUUID(),
        LocalDate.of(2026, 1, 1),
        1,
    )
    private val port = mockk<LedgerProjectionPort>()
    private val metrics = mockk<DomainMetrics>(relaxed = true)
    private val service = LedgerProjectionService(port, metrics)

    @Test
    fun `first application passes all projection identifiers and records one revaluation`(): Unit = runBlocking {
        coEvery { port.applyBookedDelta(any(), any(), any(), any(), any(), any(), any()) } returns mockk<Balance>()
        service.apply(change)
        coVerify(exactly = 1) {
            port.applyBookedDelta(
                change.journalEntryId,
                change.accountId,
                change.currency,
                change.delta,
                change.transactionId,
                change.entryDate,
                "system:balance-service:ledger-projection",
            )
        }
        verify(exactly = 1) { metrics.balanceRevaluated("CZK") }
    }

    @Test
    fun `duplicate still reaches atomic port for cover recovery without a second revaluation`(): Unit = runBlocking {
        coEvery { port.applyBookedDelta(any(), any(), any(), any(), any(), any(), any()) } returns null
        service.apply(change)
        coVerify(exactly = 1) { port.applyBookedDelta(any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { metrics.balanceRevaluated(any()) }
    }
}
