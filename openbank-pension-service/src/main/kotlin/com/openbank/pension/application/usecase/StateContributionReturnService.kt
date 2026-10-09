// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.application.usecase

import com.openbank.pension.application.port.out.AgencyDocument
import com.openbank.pension.application.port.out.AgencyDocumentKind
import com.openbank.pension.application.port.out.ContractFundingDirectory
import com.openbank.pension.application.port.out.ContractNotFoundException
import com.openbank.pension.application.port.out.ContractReferenceRepository
import com.openbank.pension.application.port.out.IncentiveClaimRepository
import com.openbank.pension.application.port.out.ReturnReport
import com.openbank.pension.application.port.out.StateAgencyGateway
import com.openbank.pension.application.port.out.StateContributionReturnChannel
import com.openbank.pension.application.port.out.StateContributionReturnRepository
import com.openbank.pension.domain.incentive.ClaimStatus
import com.openbank.pension.domain.statecontribution.CzStateContributionCalendar
import com.openbank.pension.domain.statecontribution.ReturnCause
import com.openbank.pension.domain.statecontribution.ReturnReportLine
import com.openbank.pension.domain.statecontribution.ReturnResultLine
import com.openbank.pension.domain.statecontribution.ReturnStatus
import com.openbank.pension.domain.statecontribution.StateContributionReturn
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.UUID

class ReturnNotFoundException(id: UUID) : RuntimeException("state contribution return $id not found")

class ReturnReportNotFoundException(id: UUID) : RuntimeException("return report $id not found")

/** What the daily deadline check found (ZDPS §16(2), §18). Every count is a missed or near deadline. */
data class StateContributionDeadlines(
    /** PENDING claims whose quarter's filing deadline has passed. They go with a later application (§16(4)). */
    val claimsPastFilingDeadline: Int,
    /** SUBMITTED claims whose expected payment date (§18(1)) has passed without a result. */
    val claimsPastExpectedPayment: Int,
    /** Returns not settled after their statutory due date (§18(2)/(3)). */
    val returnsOverdue: Int,
)

/**
 * State-contribution returns, or vratky (ADR-0334, #12382; ZDPS §18). The service registers what
 * is owed, reports it monthly, applies the agency's result and settles. The flow is
 * `DUE → REPORTED → CONFIRMED → SETTLED`, and a refused line goes back to DUE for the next report.
 *
 * Two triggers:
 * - an executed early exit (S5 clawback, §18(3)): the S5 ledger already holds the RETURNED entries,
 *   so settling here writes no ledger row;
 * - ineligibility discovered later (§18(2)): one return per RECEIVED claim from the first
 *   ineligible month. Settling it writes the RETURNED ledger entry through
 *   [IncentiveService.returnClaim], because the money leaves the contract only then.
 */
@Suppress("LongParameterList", "TooManyFunctions")
class StateContributionReturnService(
    private val returns: StateContributionReturnRepository,
    private val claims: IncentiveClaimRepository,
    private val directory: ContractFundingDirectory,
    private val references: ContractReferenceRepository,
    private val incentives: IncentiveService,
    private val gateway: StateAgencyGateway,
    private val channel: StateContributionReturnChannel,
    private val clock: Clock,
) {
    private val log = Logger.getLogger(StateContributionReturnService::class.java)

    /**
     * Registers the return an executed early exit owes (§18(3)). Idempotent on [sourceKey].
     *
     * [amount] is what S5 wrote back to the ledger: the whole net state balance. Part of that
     * balance may already be owed through an ineligibility return that is registered but not yet
     * settled (§18(2)). That part is netted off, so MF is paid each crown once. The months the
     * exit covers are recorded under the (contract, month) key that ineligibility also uses.
     */
    suspend fun registerTermination(
        contractId: UUID,
        amount: BigDecimal,
        terminatedOn: LocalDate,
        sourceKey: String,
    ): StateContributionReturn? {
        if (amount.signum() <= 0) return null
        val contract = directory.find(contractId) ?: throw ContractNotFoundException(contractId)
        repeat(REGISTRATION_ATTEMPTS) {
            returns.bySourceKey("exit:$sourceKey")?.let { return it }
            val open = returns.byContract(contractId)
                .filter { it.cause == ReturnCause.INELIGIBILITY_DISCOVERED && it.status != ReturnStatus.SETTLED }
            val net = amount - open.fold(BigDecimal.ZERO) { a, r -> a + r.amount }
            if (net.signum() <= 0) return null
            val covered = returns.coveredMonths(contractId)
            val months = claims.byContract(contractId)
                .filter { it.status == ClaimStatus.RECEIVED && it.period !in covered }
                .associate { it.period to requireNotNull(it.receivedAmount) }
            val item = newReturn(
                contractId,
                null,
                ReturnCause.CONTRACT_TERMINATED,
                net,
                contract.currency,
                terminatedOn,
                CzStateContributionCalendar.terminationReturnDue(terminatedOn),
                "exit:$sourceKey",
            )
            if (returns.insertWithMonths(item, months)) return item
        }
        error("could not register the exit return of $contractId: concurrent registrations kept winning")
    }

    /**
     * The participant was not entitled from [ineligibleFrom] on, for example because an old-age
     * pension was granted (§13(1)). Every RECEIVED claim from that month is owed back by the §18(2)
     * deadline. A month already covered by another return (for example an executed exit) is
     * skipped, because the (contract, month) key is unique. Idempotent per claim.
     */
    suspend fun registerIneligibility(contractId: UUID, ineligibleFrom: YearMonth): List<StateContributionReturn> {
        val contract = directory.find(contractId) ?: throw ContractNotFoundException(contractId)
        val today = today()
        return claims.byContract(contractId)
            .filter { it.status == ClaimStatus.RECEIVED && it.period >= ineligibleFrom }
            .mapNotNull { claim ->
                val key = "ineligible:${claim.id}"
                returns.bySourceKey(key) ?: run {
                    val item = newReturn(
                        contractId,
                        claim.id,
                        ReturnCause.INELIGIBILITY_DISCOVERED,
                        requireNotNull(claim.receivedAmount),
                        contract.currency,
                        today,
                        CzStateContributionCalendar.unlawfulReturnDue(today),
                        key,
                    )
                    if (returns.insertWithMonths(item, mapOf(claim.period to item.amount))) {
                        item
                    } else {
                        log.infof(
                            "month %s of %s is already covered by another return; not owed twice",
                            claim.period,
                            contractId,
                        )
                        null
                    }
                }
            }
    }

    /**
     * Files the return report for [month] with every DUE return (§18(4), by the 10th). Returns
     * null when nothing is due. The report is stored before it is transmitted.
     */
    suspend fun fileReturnReport(month: YearMonth): ReturnReport? {
        val due = returns.byStatus(ReturnStatus.DUE)
        if (due.isEmpty()) return null
        val lines = due.map { r ->
            ReturnReportLine(
                r.id,
                references.referenceFor(r.contractId),
                r.cause,
                r.amount,
                r.claimId?.let { claims.findById(it)?.period },
            )
        }
        val report =
            ReturnReport(UUID.randomUUID(), month, due.map { it.id }, channel.render(month, lines), null, false, now())
        if (!returns.fileReportAtomically(report, now())) {
            log.warnf("return report for %s lost a race with a concurrent run; nothing reported twice", month)
            return null
        }
        val receipt = gateway.transmit(
            AgencyDocument(
                AgencyDocumentKind.RETURN_REPORT,
                channel.format,
                "VR-$month-${report.id}.txt",
                report.payload,
            ),
        )
        returns.setReportChannelReference(report.id, receipt.channelReference)
        return report.copy(channelReference = receipt.channelReference)
    }

    /** Applies the agency's result to a report (§18(6)): confirmed → CONFIRMED, refused → DUE. Idempotent. */
    suspend fun applyReturnResult(reportId: UUID, payload: String): ReturnReport {
        val report = returns.findReport(reportId) ?: throw ReturnReportNotFoundException(reportId)
        val lines = channel.parseResult(payload)
        lines.forEach {
            require(it.returnId in report.returnIds) { "return ${it.returnId} is not in report $reportId" }
        }
        lines.forEach { line ->
            val item = returns.findById(line.returnId) ?: throw ReturnNotFoundException(line.returnId)
            if (item.status != ReturnStatus.REPORTED || item.reportId != reportId) return@forEach
            when (line) {
                is ReturnResultLine.Confirmed -> returns.update(item.confirm(now()))
                is ReturnResultLine.Refused -> {
                    log.warnf("agency refused return %s: %s", item.id, line.reason)
                    returns.update(item.reopen(now()))
                }
            }
        }
        returns.markReportResultApplied(reportId)
        return requireNotNull(returns.findReport(reportId))
    }

    /** The money went back to the agency (§18(7)). An ineligibility return also writes the RETURNED ledger entry. */
    suspend fun settle(returnId: UUID): StateContributionReturn {
        val item = returns.findById(returnId) ?: throw ReturnNotFoundException(returnId)
        if (item.status == ReturnStatus.SETTLED) return item
        val settled = item.settle(now())
        item.claimId?.let { claimId ->
            val claim = claims.findById(claimId)
            if (claim?.status == ClaimStatus.RECEIVED) {
                val exitWroteLedger = returns.byContract(item.contractId).any {
                    it.cause ==
                        ReturnCause.CONTRACT_TERMINATED
                }
                // After an exit, S5 has already written the whole balance back to the ledger
                // (this month included). Writing it again would return it twice.
                if (exitWroteLedger) claims.update(claim.markReturned(now())) else incentives.returnClaim(claimId)
            }
        }
        returns.update(settled)
        return settled
    }

    suspend fun byContract(contractId: UUID): List<StateContributionReturn> = returns.byContract(contractId)

    suspend fun list(status: ReturnStatus?): List<StateContributionReturn> =
        if (status != null) returns.byStatus(status) else ReturnStatus.entries.flatMap { returns.byStatus(it) }

    suspend fun reports(): List<ReturnReport> = returns.reports()

    /** The deadline check behind the daily job (§16(2), §18(1)–(3)). It changes nothing. */
    suspend fun deadlines(today: LocalDate = today()): StateContributionDeadlines {
        val pastFiling = claims.byStatus(ClaimStatus.PENDING).count {
            today.isAfter(CzStateContributionCalendar.filingDeadline(it.period))
        }
        val pastPayment = claims.byStatus(ClaimStatus.SUBMITTED).count {
            today.isAfter(CzStateContributionCalendar.expectedPaymentBy(it.period))
        }
        val overdue = ReturnStatus.entries.filter { it != ReturnStatus.SETTLED }
            .flatMap { returns.byStatus(it) }
            .count { it.overdue(today) }
        return StateContributionDeadlines(pastFiling, pastPayment, overdue)
    }

    @Suppress("LongParameterList")
    private fun newReturn(
        contractId: UUID,
        claimId: UUID?,
        cause: ReturnCause,
        amount: BigDecimal,
        currency: String,
        discoveredOn: LocalDate,
        dueBy: LocalDate,
        sourceKey: String,
    ) = StateContributionReturn(
        UUID.randomUUID(), contractId, claimId, cause, amount, currency, discoveredOn, dueBy, sourceKey,
        ReturnStatus.DUE, null, now(), now(),
    )

    private fun now(): Instant = clock.instant()

    private companion object {
        const val REGISTRATION_ATTEMPTS = 3
    }

    private fun today(): LocalDate = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
}
