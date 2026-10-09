// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.statecontribution

import com.openbank.pension.application.port.out.ReturnReport
import com.openbank.pension.application.port.out.StateContributionReturnRepository
import com.openbank.pension.application.usecase.StateContributionReturnService
import com.openbank.pension.domain.contribution.ContributionChannel
import com.openbank.pension.domain.contribution.IncomingPayment
import com.openbank.pension.domain.incentive.ClaimStatus
import com.openbank.pension.domain.incentive.LedgerEntryKind
import com.openbank.pension.domain.statecontribution.ReturnCause
import com.openbank.pension.domain.statecontribution.ReturnStatus
import com.openbank.pension.domain.statecontribution.StateContributionReturn
import com.openbank.pension.e2e.support.StateAgencySimulator
import com.openbank.pension.funding.InMemoryFunding
import com.openbank.pension.infrastructure.statecontribution.CzReturnChannel
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.Optional
import java.util.UUID

/**
 * The CZ claim channel end to end over in-memory ports: quarterly filing, result, partial payment,
 * rejection, deadlines and returns.
 */
class StateContributionChannelTest {

    private val f = InMemoryFunding(Instant.parse("2026-02-10T10:00:00Z"))
    private val store = InMemoryReturns()
    private val returns = StateContributionReturnService(
        store,
        f.claims,
        f.directory,
        f.references,
        f.incentiveService,
        f.agency,
        CzReturnChannel(Optional.of("12345678")),
        f.clock,
    )

    private suspend fun pay(contractId: UUID, amount: String, on: LocalDate) {
        f.contributionService.receive(
            IncomingPayment(
                "p-$contractId-$on-$amount",
                BigDecimal(amount),
                "CZK",
                on,
                f.contributionService.paymentReference(contractId),
                ContributionChannel.BANK_TRANSFER,
            ),
        )
    }

    private fun at(date: String) {
        f.now = Instant.parse("${date}T10:00:00Z")
    }

    @Test
    fun `C2 months of a quarter wait for the quarter to close and are filed as ONE application`(): Unit = runBlocking {
        val c = f.contract()
        pay(c.contractId, "1700", LocalDate.of(2026, 1, 10))
        val jan = f.incentiveService.runMonthlyClaims(YearMonth.of(2026, 1))
        assertThat(jan.claimsCreated).isEqualTo(1)
        assertThat(jan.batches).describedAs("Q1 has not closed in February").isEmpty()
        assertThat(f.agency.sent).isEmpty()

        pay(c.contractId, "1000", LocalDate.of(2026, 2, 10))
        at("2026-03-05")
        f.incentiveService.runMonthlyClaims(YearMonth.of(2026, 2))
        pay(c.contractId, "1234", LocalDate.of(2026, 3, 10))
        at("2026-04-05")
        val april = f.incentiveService.runMonthlyClaims(YearMonth.of(2026, 3))

        val batch = april.batches.single()
        assertThat(batch.period).isEqualTo(YearMonth.of(2026, 1))
        val filed = StateAgencySimulator.parse(batch.payload)
        assertThat(filed.map { it.period to BigDecimal(it.claimed) }).containsExactly(
            "2026-01" to BigDecimal("340.00"),
            "2026-02" to BigDecimal("200.00"),
            "2026-03" to BigDecimal("246.00"),
        )
        assertThat(
            batch.payload.lines().first(),
        ).startsWith("H;cz-mf-state-contribution-v1;APPLICATION;12345678;2026;1;3;786")
        // Transmitted only after it was stored, and the reference says nothing was delivered.
        assertThat(f.agency.sent.single().payload).isEqualTo(batch.payload)
        assertThat(f.batchRows.getValue(batch.id).channelReference).startsWith("MANUAL:sha256:")
    }

    @Test
    fun `P1 partial payment and rejection are reconciled per line from one aggregate payment`(): Unit = runBlocking {
        val a = f.contract()
        val b = f.contract()
        val d = f.contract()
        pay(a.contractId, "1700", LocalDate.of(2026, 1, 10))
        pay(b.contractId, "1700", LocalDate.of(2026, 1, 10))
        pay(d.contractId, "1700", LocalDate.of(2026, 1, 10))
        at("2026-04-05")
        val batch = f.incentiveService.runMonthlyClaims(YearMonth.of(2026, 1)).batches.single()
        val byContract = f.claimRows.values.associateBy { it.contractId }
        val result = StateAgencySimulator.receipt(
            batch.payload,
            reject = mapOf(byContract.getValue(b.contractId).id.toString() to "OLD_AGE_PENSIONER"),
            partial = mapOf(byContract.getValue(d.contractId).id.toString() to BigDecimal("200.00")),
        )
        assertThat(result.lines().first()).endsWith(";540.00")
        f.incentiveService.reconcileReceiptFile(batch.id, result)

        val claims = f.claimRows.values.associateBy { it.contractId }
        assertThat(claims.getValue(a.contractId).receivedAmount).isEqualByComparingTo("340")
        assertThat(claims.getValue(b.contractId).status).isEqualTo(ClaimStatus.REJECTED)
        assertThat(claims.getValue(b.contractId).rejectionReason).startsWith("OLD_AGE_PENSIONER:")
        assertThat(claims.getValue(d.contractId).status).isEqualTo(ClaimStatus.RECEIVED)
        assertThat(claims.getValue(d.contractId).receivedAmount).isEqualByComparingTo("200")
        assertThat(claims.getValue(d.contractId).rejectionReason).isEqualTo("AMOUNT_RECOMPUTED")
        assertThat(
            f.ledgerRows.filter {
                it.kind == LedgerEntryKind.RECEIVED
            }.sumOf { it.amount },
        ).isEqualByComparingTo("540")
    }

    @Test
    fun `deadline miss - a claim PENDING after its filing month and an unpaid one are counted`(): Unit = runBlocking {
        val c = f.contract()
        pay(c.contractId, "1700", LocalDate.of(2026, 1, 10))
        f.incentiveService.generateClaims(YearMonth.of(2026, 1))
        assertThat(returns.deadlines(LocalDate.of(2026, 4, 30)).claimsPastFilingDeadline).isZero()
        assertThat(returns.deadlines(LocalDate.of(2026, 5, 1)).claimsPastFilingDeadline).isEqualTo(1)

        at("2026-04-05")
        f.incentiveService.submitPending()
        val d = returns.deadlines(LocalDate.of(2026, 6, 1))
        assertThat(d.claimsPastFilingDeadline).isZero()
        assertThat(d.claimsPastExpectedPayment).describedAs("Q1 must be paid by 31 May").isEqualTo(1)
    }

    @Test
    fun `R1 ineligibility found later - return reported, refused, re-reported, confirmed, settled`(): Unit =
        runBlocking {
            val c = f.contract()
            pay(c.contractId, "1700", LocalDate.of(2026, 1, 10))
            pay(c.contractId, "1700", LocalDate.of(2026, 2, 10))
            at("2026-04-05")
            f.incentiveService.generateClaims(YearMonth.of(2026, 1))
            f.incentiveService.generateClaims(YearMonth.of(2026, 2))
            val batch = f.incentiveService.submitPending().first.single()
            f.incentiveService.reconcileReceiptFile(batch.id, StateAgencySimulator.receipt(batch.payload))

            at("2026-06-03")
            val owed = returns.registerIneligibility(c.contractId, YearMonth.of(2026, 2))
            assertThat(owed).hasSize(1)
            assertThat(owed.single().cause).isEqualTo(ReturnCause.INELIGIBILITY_DISCOVERED)
            assertThat(owed.single().dueBy).isEqualTo(LocalDate.of(2026, 7, 31))
            assertThat(returns.registerIneligibility(c.contractId, YearMonth.of(2026, 2)).map { it.id })
                .describedAs("idempotent per claim").isEqualTo(owed.map { it.id })

            val first = requireNotNull(returns.fileReturnReport(YearMonth.of(2026, 6)))
            assertThat(
                StateAgencySimulator.parseReturns(first.payload).single().returnId,
            ).isEqualTo(owed.single().id.toString())
            assertThat(returns.fileReturnReport(YearMonth.of(2026, 6))).describedAs("nothing DUE any more").isNull()
            returns.applyReturnResult(
                first.id,
                StateAgencySimulator.returnResult(
                    first.payload,
                    refuse = mapOf(
                        owed.single().id.toString() to "DATA_ERROR",
                    ),
                ),
            )
            assertThat(store.rows.getValue(owed.single().id).status).isEqualTo(ReturnStatus.DUE)

            val second = requireNotNull(returns.fileReturnReport(YearMonth.of(2026, 7)))
            returns.applyReturnResult(second.id, StateAgencySimulator.returnResult(second.payload))
            assertThat(store.rows.getValue(owed.single().id).status).isEqualTo(ReturnStatus.CONFIRMED)
            assertThat(
                f.ledgerRows.none {
                    it.kind == LedgerEntryKind.RETURNED
                },
            ).describedAs("not paid back yet").isTrue()

            val settled = returns.settle(owed.single().id)
            assertThat(settled.status).isEqualTo(ReturnStatus.SETTLED)
            assertThat(f.ledgerRows.single { it.kind == LedgerEntryKind.RETURNED }.amount).isEqualByComparingTo("340")
            assertThat(
                f.claimRows.values.single {
                    it.period == YearMonth.of(2026, 2)
                }.status,
            ).isEqualTo(ClaimStatus.RETURNED)
            assertThat(returns.settle(owed.single().id)).describedAs("settling twice is a no-op").isEqualTo(settled)
            assertThat(f.ledgerRows.count { it.kind == LedgerEntryKind.RETURNED }).isEqualTo(1)
        }

    @Test
    fun `R2 an early exit owes its return six months on and is idempotent`(): Unit = runBlocking {
        val c = f.contract()
        val r =
            requireNotNull(
                returns.registerTermination(
                    c.contractId,
                    BigDecimal("680.00"),
                    LocalDate.of(2026, 3, 15),
                    "term-1",
                ),
            )
        assertThat(r.dueBy).isEqualTo(LocalDate.of(2026, 9, 30))
        assertThat(
            returns.registerTermination(
                c.contractId,
                BigDecimal("680.00"),
                LocalDate.of(2026, 3, 15),
                "term-1",
            )!!.id,
        )
            .describedAs("a replayed exit activity registers nothing twice").isEqualTo(r.id)
        assertThat(
            returns.registerTermination(c.contractId, BigDecimal.ZERO, LocalDate.of(2026, 3, 15), "term-2"),
        ).isNull()

        at("2026-10-01")
        assertThat(returns.deadlines().returnsOverdue).isEqualTo(1)
    }

    /** Two received months (Jan, Feb: 340 each), reconciled; ledger net 680. */
    private suspend fun twoReceivedMonths(): UUID {
        val c = f.contract()
        pay(c.contractId, "1700", LocalDate.of(2026, 1, 10))
        pay(c.contractId, "1700", LocalDate.of(2026, 2, 10))
        at("2026-04-05")
        f.incentiveService.generateClaims(YearMonth.of(2026, 1))
        f.incentiveService.generateClaims(YearMonth.of(2026, 2))
        val batch = f.incentiveService.submitPending().first.single()
        f.incentiveService.reconcileReceiptFile(batch.id, StateAgencySimulator.receipt(batch.payload))
        at("2026-06-03")
        return c.contractId
    }

    /** What S5's executed exit does through IncentiveLedgerClawbackAdapter. */
    private suspend fun exit(contractId: UUID, key: String) {
        val balance = f.incentiveService.clawbackBalance(contractId, LocalDate.of(2026, 6, 3)).stateIncentivesToReturn
        f.incentiveService.settleClawback(contractId, balance, key)
        returns.registerTermination(contractId, balance, LocalDate.of(2026, 6, 3), key)
    }

    private fun owedToAgency(contractId: UUID) =
        store.rows.values.filter { it.contractId == contractId }.fold(BigDecimal.ZERO) { a, r -> a + r.amount }

    private fun returnedInLedger(contractId: UUID) = f.ledgerRows
        .filter { it.contractId == contractId && it.kind == LedgerEntryKind.RETURNED }
        .fold(BigDecimal.ZERO) { a, e -> a + e.amount }

    @Test
    fun `ineligibility then exit - the exit nets off the open return and nothing is returned twice`(): Unit =
        runBlocking {
            val c = twoReceivedMonths()
            val inel = returns.registerIneligibility(c, YearMonth.of(2026, 2)).single()
            exit(c, "exit-a")
            val exitReturn = store.rows.values.single { it.cause == ReturnCause.CONTRACT_TERMINATED }
            assertThat(
                exitReturn.amount,
            ).describedAs("680 balance less the open 340 ineligibility").isEqualByComparingTo("340")
            assertThat(store.coveredMonths(c)).containsExactlyInAnyOrder(YearMonth.of(2026, 1), YearMonth.of(2026, 2))

            val report = requireNotNull(returns.fileReturnReport(YearMonth.of(2026, 6)))
            returns.applyReturnResult(report.id, StateAgencySimulator.returnResult(report.payload))
            returns.settle(inel.id)
            returns.settle(exitReturn.id)
            assertThat(owedToAgency(c)).isEqualByComparingTo("680")
            assertThat(returnedInLedger(c)).describedAs("the ledger holds the 680 once").isEqualByComparingTo("680")
            assertThat(f.incentiveService.status(c).balances.single().net).isEqualByComparingTo("0")
        }

    @Test
    fun `exit then ineligibility - a month the exit already returns is not owed a second time`(): Unit = runBlocking {
        val c = twoReceivedMonths()
        exit(c, "exit-b")
        assertThat(returns.registerIneligibility(c, YearMonth.of(2026, 2))).isEmpty()
        assertThat(owedToAgency(c)).isEqualByComparingTo("680")
        assertThat(returnedInLedger(c)).isEqualByComparingTo("680")
    }

    @Test
    fun `nothing is filed without the company ICO - the channel fails closed`(): Unit = runBlocking {
        assertThatThrownBy { CzReturnChannel(Optional.empty()).render(YearMonth.of(2026, 5), emptyList()) }
            .isInstanceOf(IllegalStateException::class.java)
    }
}

/** Same idempotency and atomicity semantics as `PgStateContributionReturns`. */
class InMemoryReturns : StateContributionReturnRepository {
    val rows = linkedMapOf<UUID, StateContributionReturn>()
    private val reportRows = linkedMapOf<UUID, ReturnReport>()

    /** (contract, month) → return id: the same uniqueness as the V10 primary key. */
    val months = linkedMapOf<Pair<UUID, YearMonth>, UUID>()

    override suspend fun bySourceKey(sourceKey: String) = rows.values.firstOrNull { it.sourceKey == sourceKey }

    override suspend fun insertWithMonths(item: StateContributionReturn, months: Map<YearMonth, BigDecimal>): Boolean {
        if (bySourceKey(item.sourceKey) != null) return false
        if (months.keys.any { (item.contractId to it) in this.months }) return false
        rows[item.id] = item
        months.keys.forEach { this.months[item.contractId to it] = item.id }
        return true
    }

    override suspend fun coveredMonths(contractId: UUID) =
        months.keys.filter { it.first == contractId }.map { it.second }.toSet()

    override suspend fun findById(id: UUID) = rows[id]

    override suspend fun byStatus(status: ReturnStatus) = rows.values.filter { it.status == status }

    override suspend fun byContract(contractId: UUID) = rows.values.filter { it.contractId == contractId }

    override suspend fun update(item: StateContributionReturn) {
        rows[item.id] = item
    }

    override suspend fun fileReportAtomically(report: ReturnReport, at: Instant): Boolean {
        if (report.returnIds.any { rows[it]?.status != ReturnStatus.DUE }) return false
        report.returnIds.forEach { rows[it] = rows.getValue(it).report(report.id, at) }
        reportRows[report.id] = report
        return true
    }

    override suspend fun findReport(id: UUID) = reportRows[id]

    override suspend fun reports() = reportRows.values.reversed()

    override suspend fun markReportResultApplied(id: UUID) {
        reportRows[id] = reportRows.getValue(id).copy(resultApplied = true)
    }

    override suspend fun setReportChannelReference(id: UUID, reference: String) {
        reportRows[id] = reportRows.getValue(id).copy(channelReference = reference)
    }
}
