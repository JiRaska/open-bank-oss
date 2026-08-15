// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.application.usecase

import com.openbank.balance.application.port.out.LedgerControlBalancePort
import com.openbank.balance.application.port.out.ReconciliationRecordRepository
import com.openbank.balance.domain.reconciliation.ReconciliationReport
import com.openbank.libs.observability.DomainMetrics
import io.mockk.mockk
import io.mockk.verify
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
            val metrics = mockk<DomainMetrics>(relaxed = true)
            val service =
                BalanceReconciliationService(balanceRepo, ledger, records, java.time.Clock.systemUTC(), metrics)

            val report = service.reconcile(asOf)

            assertFalse(report.hasDrift)
            assertEquals(asOf, ledger.askedFor)
            assertEquals(1, records.saved.size)
            assertSame(report, records.saved.single())
        }

    @Test
    fun `reconcile uses the value-date basis so a future-value-dated journal is not drift`(): Unit = runBlocking {
        // A future-value-dated credit batch (e.g. welcome bonuses value-dated tomorrow) is already
        // booked in the running total but NOT yet in the ledger control, which is value-dated.
        // The current running total is +500 over the control; the value-date-correct total ties out.
        val ledger = FakeLedgerControl(mapOf("CZK" to BigDecimal("1000.00")))
        val balanceRepo = FakeSumRepository(
            sums = mapOf("CZK" to BigDecimal("1500.00")),
            valueDatedSums = mapOf("CZK" to BigDecimal("1000.00")),
        )
        val records = FakeRecordRepository()
        val metrics = mockk<DomainMetrics>(relaxed = true)
        val service =
            BalanceReconciliationService(balanceRepo, ledger, records, java.time.Clock.systemUTC(), metrics)

        val report = service.reconcile(asOf)

        assertFalse(report.hasDrift, "value-date-correct sub-ledger sum must tie out to the control")
        assertEquals(asOf, balanceRepo.askedAsOf, "reconciliation must query the sub-ledger as of the tie-out date")
        assertEquals(0, report.currencies.single().difference.compareTo(BigDecimal.ZERO))
    }

    @Test
    fun `reconcile reports the future-value-dated pipeline without turning it into drift`(): Unit = runBlocking {
        // ADR-0178 Phase 3. Same scenario as the value-date test above: 1500 booked, of which 500 is
        // value-dated forward, so the value-date-correct sum (1000) ties out to the control (1000).
        // The 500 must be REPORTED as the pipeline and must NOT move `difference` — the tie-out sides
        // already exclude it, so subtracting it again would re-create the false drift Phase 1 removed.
        val ledger = FakeLedgerControl(mapOf("CZK" to BigDecimal("1000.00")))
        val balanceRepo = FakeSumRepository(
            sums = mapOf("CZK" to BigDecimal("1500.00")),
            valueDatedSums = mapOf("CZK" to BigDecimal("1000.00")),
            futureValueDated = mapOf("CZK" to BigDecimal("500.00")),
        )
        val records = FakeRecordRepository()
        val service = BalanceReconciliationService(
            balanceRepo,
            ledger,
            records,
            java.time.Clock.systemUTC(),
            mockk<DomainMetrics>(relaxed = true),
        )

        val report = service.reconcile(asOf)

        val czk = report.currencies.single()
        assertEquals(0, czk.futureValueDatedPipeline.compareTo(BigDecimal("500.00")), "pipeline must be surfaced")
        assertEquals(0, czk.difference.compareTo(BigDecimal.ZERO), "pipeline must not leak into drift")
        assertFalse(report.hasDrift, "a purely future-value-dated batch must raise ZERO alerts")
        assertEquals(asOf, balanceRepo.askedPipelineAsOf, "pipeline must be read as of the tie-out date")
    }

    @Test
    fun `a genuine divergence still drifts even while a pipeline is outstanding`(): Unit = runBlocking {
        // The pipeline is explanatory only: a real 10.00 shortfall on the value-date basis must still
        // alert, and must not be masked by an unrelated future-value-dated batch sitting in the tail.
        val ledger = FakeLedgerControl(mapOf("CZK" to BigDecimal("1000.00")))
        val balanceRepo = FakeSumRepository(
            sums = mapOf("CZK" to BigDecimal("1490.00")),
            valueDatedSums = mapOf("CZK" to BigDecimal("990.00")),
            futureValueDated = mapOf("CZK" to BigDecimal("500.00")),
        )
        val service = BalanceReconciliationService(
            balanceRepo,
            ledger,
            FakeRecordRepository(),
            java.time.Clock.systemUTC(),
            mockk<DomainMetrics>(relaxed = true),
        )

        val report = service.reconcile(asOf)

        assertTrue(report.hasDrift, "unexplained drift must still alert while a pipeline is outstanding")
        val czk = report.currencies.single()
        assertEquals(0, czk.difference.compareTo(BigDecimal("-10.00")), "drift is the value-date-basis gap only")
        assertEquals(0, czk.futureValueDatedPipeline.compareTo(BigDecimal("500.00")))
    }

    @Test
    fun `reconcile flags drift when ledger and booked disagree`(): Unit = runBlocking {
        val ledger = FakeLedgerControl(mapOf("CZK" to BigDecimal("1000.00")))
        val balanceRepo = FakeSumRepository(mapOf("CZK" to BigDecimal("990.00")))
        val records = FakeRecordRepository()
        val metrics = mockk<DomainMetrics>(relaxed = true)
        val service = BalanceReconciliationService(balanceRepo, ledger, records, java.time.Clock.systemUTC(), metrics)

        val report = service.reconcile(asOf)

        assertTrue(report.hasDrift)
        assertEquals(listOf("CZK"), report.driftedCurrencies)
        assertEquals(0, report.currencies.single().difference.compareTo(BigDecimal("-10.00")))
        assertEquals(1, records.saved.size)
    }

    @Test
    fun `reconcile publishes the drift gauge for every currency, including within-tolerance ones`(): Unit =
        runBlocking {
            val ledger = FakeLedgerControl(mapOf("CZK" to BigDecimal("1000.00"), "EUR" to BigDecimal("50.00")))
            val balanceRepo = FakeSumRepository(mapOf("CZK" to BigDecimal("990.00"), "EUR" to BigDecimal("50.00")))
            val records = FakeRecordRepository()
            val metrics = mockk<DomainMetrics>(relaxed = true)
            val service =
                BalanceReconciliationService(balanceRepo, ledger, records, java.time.Clock.systemUTC(), metrics)

            service.reconcile(asOf)

            verify(exactly = 1) {
                metrics.recordReconciliationDrift(
                    "balance_deposit_control",
                    "CZK",
                    match { it.compareTo(BigDecimal("-10.00")) == 0 },
                )
            }
            // EUR is within tolerance (difference = 0) but must still be recorded — a quiet currency
            // must not read as "not yet reconciled" on the gauge.
            verify(exactly = 1) {
                metrics.recordReconciliationDrift(
                    "balance_deposit_control",
                    "EUR",
                    match {
                        it.compareTo(BigDecimal.ZERO) ==
                            0
                    },
                )
            }
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
                mockk<DomainMetrics>(relaxed = true),
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

    // [sums] is the raw current running total (`sumBookedByCurrency`); [valueDatedSums] is the
    // value-date-correct total the reconciliation actually consumes (`sumBookedByCurrencyAsOf`).
    // They differ exactly when a future-value-dated journal is already booked but not yet effective;
    // by default they coincide so the older tests exercise the agree/disagree paths unchanged.
    private class FakeSumRepository(
        private val sums: Map<String, BigDecimal>,
        private val valueDatedSums: Map<String, BigDecimal> = sums,
        private val futureValueDated: Map<String, BigDecimal> = emptyMap(),
    ) : com.openbank.balance.application.port.out.BalanceRepository {
        var askedAsOf: LocalDate? = null
        var askedPipelineAsOf: LocalDate? = null
        override suspend fun findByAccountIdAndCurrency(accountId: UUID, currency: String) = null
        override suspend fun findAllByAccountId(accountId: UUID) =
            emptyList<com.openbank.balance.domain.model.Balance>()
        override suspend fun save(balance: com.openbank.balance.domain.model.Balance) = balance
        override suspend fun update(balance: com.openbank.balance.domain.model.Balance) = balance
        override suspend fun sumBookedByCurrency(): Map<String, BigDecimal> = sums
        override suspend fun sumBookedByCurrencyAsOf(asOf: LocalDate): Map<String, BigDecimal> {
            askedAsOf = asOf
            return valueDatedSums
        }
        override suspend fun sumBookedDeltaAfter(
            accountId: UUID,
            currency: String,
            asOf: java.time.LocalDate,
        ): BigDecimal = BigDecimal.ZERO
        override suspend fun sumFutureValueDatedByCurrency(asOf: LocalDate): Map<String, BigDecimal> {
            askedPipelineAsOf = asOf
            return futureValueDated
        }
        override suspend fun sumNotYetEffectiveCredit(accountId: UUID, currency: String, asOf: LocalDate): BigDecimal =
            BigDecimal.ZERO
        override suspend fun findCreditsMaturingOn(
            date: LocalDate,
        ): List<com.openbank.balance.application.port.out.AccountCurrency> = emptyList()
    }

    private class FakeRecordRepository : ReconciliationRecordRepository {
        val saved = mutableListOf<ReconciliationReport>()
        override suspend fun save(report: ReconciliationReport): ReconciliationReport = report.also { saved += it }
        override suspend fun findLatest(): ReconciliationReport? = saved.lastOrNull()
    }
}
