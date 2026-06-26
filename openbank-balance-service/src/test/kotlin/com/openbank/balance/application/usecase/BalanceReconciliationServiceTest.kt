// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.balance.application.usecase

import com.openbank.balance.application.port.out.LedgerControlBalancePort
import com.openbank.balance.application.port.out.ReconciliationRecordRepository
import com.openbank.balance.domain.reconciliation.ReconciliationReport
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class BalanceReconciliationServiceTest {

    private val asOf = LocalDate.of(2026, 1, 31)

    @Test
    fun `reconcile compares ledger vs booked, persists the run and reports no drift when they agree`(): Unit =
        runBlocking {
            val ledger = FakeLedgerControl(mapOf("CZK" to BigDecimal("1000.00"), "EUR" to BigDecimal("50.00")))
            val balanceRepo = FakeSumRepository(mapOf("CZK" to BigDecimal("1000.00"), "EUR" to BigDecimal("50.00")))
            val records = FakeRecordRepository()
            val service = BalanceReconciliationService(balanceRepo, ledger, records, java.time.Clock.systemUTC())

            val report = service.reconcile(asOf)

            assertFalse(report.hasDrift)
            assertEquals(asOf, ledger.askedFor)
            assertEquals(1, records.saved.size)
            assertSame(report, records.saved.single())
        }

    @Test
    fun `reconcile flags drift when ledger and booked disagree`(): Unit = runBlocking {
        val ledger = FakeLedgerControl(mapOf("CZK" to BigDecimal("1000.00")))
        val balanceRepo = FakeSumRepository(mapOf("CZK" to BigDecimal("990.00")))
        val records = FakeRecordRepository()
        val service = BalanceReconciliationService(balanceRepo, ledger, records, java.time.Clock.systemUTC())

        val report = service.reconcile(asOf)

        assertTrue(report.hasDrift)
        assertEquals(listOf("CZK"), report.driftedCurrencies)
        assertEquals(0, report.currencies.single().difference.compareTo(BigDecimal("-10.00")))
        assertEquals(1, records.saved.size)
    }

    @Test
    fun `latest delegates to the record repository`(): Unit = runBlocking {
        val records = FakeRecordRepository()
        val service =
            BalanceReconciliationService(
                FakeSumRepository(emptyMap()),
                FakeLedgerControl(emptyMap()),
                records,
                java.time.Clock.systemUTC(),
            )
        assertEquals(null, service.latest())

        service.reconcile(asOf)
        assertEquals(records.saved.single(), service.latest())
    }

    private class FakeLedgerControl(private val byCcy: Map<String, BigDecimal>) : LedgerControlBalancePort {
        var askedFor: LocalDate? = null
        override suspend fun depositControlBalanceByCurrency(asOf: LocalDate): Map<String, BigDecimal> {
            askedFor = asOf
            return byCcy
        }
    }

    private class FakeSumRepository(private val sums: Map<String, BigDecimal>) :
        com.openbank.balance.application.port.out.BalanceRepository {
        override suspend fun findByAccountIdAndCurrency(accountId: UUID, currency: String) = null
        override suspend fun findAllByAccountId(accountId: UUID) =
            emptyList<com.openbank.balance.domain.model.Balance>()
        override suspend fun save(balance: com.openbank.balance.domain.model.Balance) = balance
        override suspend fun update(balance: com.openbank.balance.domain.model.Balance) = balance
        override suspend fun sumBookedByCurrency(): Map<String, BigDecimal> = sums
        override suspend fun sumBookedDeltaAfter(
            accountId: UUID,
            currency: String,
            asOf: java.time.LocalDate,
        ): BigDecimal = BigDecimal.ZERO
    }

    private class FakeRecordRepository : ReconciliationRecordRepository {
        val saved = mutableListOf<ReconciliationReport>()
        override suspend fun save(report: ReconciliationReport): ReconciliationReport = report.also { saved += it }
        override suspend fun findLatest(): ReconciliationReport? = saved.lastOrNull()
    }
}
