// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.application.usecase

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.pension.application.port.out.ClaimBatchRepository
import com.openbank.pension.application.port.out.ClaimReceiptLine
import com.openbank.pension.application.port.out.ContractFundingDirectory
import com.openbank.pension.application.port.out.ContractFundingView
import com.openbank.pension.application.port.out.ContractNotFoundException
import com.openbank.pension.application.port.out.ContractReferenceRepository
import com.openbank.pension.application.port.out.ContributionRepository
import com.openbank.pension.application.port.out.IncentiveClaimRepository
import com.openbank.pension.application.port.out.IncentiveLedgerRepository
import com.openbank.pension.application.port.out.ParticipantNotificationKind
import com.openbank.pension.application.port.out.ParticipantNotifier
import com.openbank.pension.application.port.out.StateIncentiveClaimPort
import com.openbank.pension.application.port.out.TaxCertificateDocumentPort
import com.openbank.pension.application.port.out.TaxYearSummaryRepository
import com.openbank.pension.domain.contribution.ContributionChannel
import com.openbank.pension.domain.contribution.ContributionSource
import com.openbank.pension.domain.contribution.IncomingPayment
import com.openbank.pension.domain.incentive.ClaimBatch
import com.openbank.pension.domain.incentive.ClaimBatchStatus
import com.openbank.pension.domain.incentive.ClaimStatus
import com.openbank.pension.domain.incentive.ClawbackBalance
import com.openbank.pension.domain.incentive.ClawbackItem
import com.openbank.pension.domain.incentive.ClawbackKind
import com.openbank.pension.domain.incentive.ContractYearInput
import com.openbank.pension.domain.incentive.IncentiveClaim
import com.openbank.pension.domain.incentive.IncentiveEngine
import com.openbank.pension.domain.incentive.IncentiveLedgerEntry
import com.openbank.pension.domain.incentive.LedgerEntryKind
import com.openbank.pension.domain.incentive.TaxYearSummary
import com.openbank.pension.domain.model.ProductLine
import com.openbank.pension.domain.pack.JurisdictionPack
import com.openbank.pension.domain.pack.JurisdictionPackRegistry
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.UUID

class ClaimBatchNotFoundException(id: UUID) : RuntimeException("claim batch $id not found")

class IncentiveClaimNotFoundException(id: UUID) : RuntimeException("incentive claim $id not found")

data class IncentiveBalance(val incentiveId: String, val received: BigDecimal, val returned: BigDecimal) {
    val net: BigDecimal get() = received - returned
}

data class IncentiveStatus(val claims: List<IncentiveClaim>, val balances: List<IncentiveBalance>)

data class ClaimRunResult(val claimsCreated: Int, val batches: List<ClaimBatch>, val unfiledFormats: Set<String>)

/**
 * State incentives (ADR-0334 S3): monthly claim generation from actual contributions, filing
 * through the claim channel adapter the pack names, receipt reconciliation, rejections, returns,
 * the clawback ledger, and the per-contract tax year.
 */
// One use case per S3 incentive concern would scatter a single lifecycle (claim -> file -> receive ->
// return -> clawback) over many classes; the ports are the seams, not the method count.
@Suppress("LongParameterList", "TooManyFunctions")
class IncentiveService(
    private val directory: ContractFundingDirectory,
    private val references: ContractReferenceRepository,
    private val contributions: ContributionRepository,
    private val claims: IncentiveClaimRepository,
    private val batches: ClaimBatchRepository,
    private val ledger: IncentiveLedgerRepository,
    private val summaries: TaxYearSummaryRepository,
    private val registry: JurisdictionPackRegistry,
    private val channels: List<StateIncentiveClaimPort>,
    private val documents: TaxCertificateDocumentPort,
    private val contributionService: ContributionService,
    private val clock: Clock,
    private val notifier: ParticipantNotifier,
) {
    private val log = Logger.getLogger(IncentiveService::class.java)

    /**
     * The monthly job: claims for [period] (normally the month just closed) for every fundable
     * contract, then one batch per claim format for everything PENDING. Re-running it is harmless:
     * claims are unique per period and only PENDING claims are filed.
     */
    suspend fun runMonthlyClaims(period: YearMonth): ClaimRunResult {
        val created = generateClaims(period)
        val (filed, unfiled) = submitPending()
        return ClaimRunResult(created, filed, unfiled)
    }

    suspend fun generateClaims(period: YearMonth): Int =
        directory.fundable().sumOf { contract -> generateClaimsFor(contract, period) }

    private suspend fun generateClaimsFor(contract: ContractFundingView, period: YearMonth): Int {
        val pack = packOf(contract)
        val rules = IncentiveEngine.claimableRules(pack)
        if (rules.isEmpty()) return 0
        val from = period.atDay(1).minusMonths(MAX_PERIOD_MONTHS)
        val window = contributions.byContractAndRange(contract.contractId, from, period.plusMonths(1).atDay(1))
        // Only claim a period once it has fully closed: its last month is `period`.
        val closing = rules.map { it.period }.toSet().filter { rulePeriod ->
            val start = IncentiveEngine.periodStart(period, rulePeriod)
            start.plusMonths((MONTHS_PER_YEAR / rulePeriod.periodsPerYear).toLong() - 1) == period
        }
        val drafts = closing.flatMap { rulePeriod ->
            IncentiveEngine.claimsFor(pack, IncentiveEngine.periodStart(period, rulePeriod), window)
                .filter { draft -> pack.incentives.first { it.id == draft.incentiveId }.period == rulePeriod }
        }
        return drafts.count { draft ->
            claims.insertIfAbsent(
                IncentiveClaim(
                    id = Ids.newId(),
                    contractId = contract.contractId,
                    incentiveId = draft.incentiveId,
                    period = draft.period,
                    basis = draft.basis,
                    claimedAmount = draft.amount,
                    currency = contract.currency,
                    status = ClaimStatus.PENDING,
                    createdAt = now(),
                    updatedAt = now(),
                ),
            ).second
        }
    }

    /** Files every PENDING claim, one batch per (format, period). Formats with no adapter stay PENDING. */
    suspend fun submitPending(): Pair<List<ClaimBatch>, Set<String>> {
        val pending = claims.byStatus(ClaimStatus.PENDING)
        val unfiled = mutableSetOf<String>()
        val filed = mutableListOf<ClaimBatch>()
        val today = today()
        val byFormat = pending.groupBy { formatOf(it) }.mapValues { (format, list) ->
            // A claim is filed only once its filing period is open (CZ: after the quarter, ZDPS §16(2)).
            val adapter = channels.firstOrNull { it.claimFormat == format }
            if (adapter == null) list else list.filter { adapter.fileable(it.period, today) }
        }.filterValues { it.isNotEmpty() }
        byFormat.forEach { (format, ofFormat) ->
            if (channels.none { it.claimFormat == format }) {
                unfiled += format
                log.warnf("no claim channel adapter for format '%s'; %d claim(s) stay PENDING", format, ofFormat.size)
            }
        }
        // The adapter decides the filing period a claim month belongs to (CZ: the quarter, ZDPS §16(2)).
        byFormat.flatMap { (format, ofFormat) ->
            val adapter = channels.firstOrNull { it.claimFormat == format } ?: return@flatMap emptyList()
            ofFormat.groupBy {
                adapter.filingPeriod(it.period)
            }.map { (period, group) -> Triple(adapter, period, group) }
        }.forEach { (adapter, period, group) ->
            val format = adapter.claimFormat
            val refs = group.associate { it.contractId to references.referenceFor(it.contractId) }
            val rendered = adapter.submit(period, group, refs)
            val batch = ClaimBatch(
                id = Ids.newId(),
                claimFormat = format,
                period = period,
                claimIds = group.map { it.id },
                payload = rendered.payload,
                channelReference = rendered.channelReference,
                status = ClaimBatchStatus.SUBMITTED,
                createdAt = now(),
            )
            // Atomic: a concurrent run that filed any of these claims first makes this a no-op,
            // so no claim is ever filed twice (the rendered payload is simply discarded).
            if (batches.fileAtomically(batch, now())) {
                // Transmit only what was durably filed: a lost race never reaches the agency.
                val reference = adapter.transmit(batch)
                val stored = if (reference != null) batch.copy(channelReference = reference) else batch
                if (stored != batch) batches.update(stored)
                filed += stored
            } else {
                log.warnf(
                    "claim batch for %s/%s lost a race with a concurrent run; nothing filed twice",
                    format,
                    period,
                )
            }
        }
        return filed to unfiled
    }

    suspend fun listBatches(): List<ClaimBatch> = batches.list()

    /** Applies the agency's receipt file to a batch, parsed by the adapter that rendered it. */
    suspend fun reconcileReceiptFile(batchId: UUID, payload: String): ClaimBatch {
        val batch = batches.findById(batchId) ?: throw ClaimBatchNotFoundException(batchId)
        val adapter = channels.firstOrNull { it.claimFormat == batch.claimFormat }
            ?: error("no claim channel adapter for format '${batch.claimFormat}'")
        return reconcile(batchId, adapter.parseReceipt(payload))
    }

    /**
     * Reconciles receipt lines: accepted → RECEIVED, a ledger credit, and the money credited to
     * the contract as a STATE contribution (which places its subscription); refused → REJECTED.
     * A line for a claim outside the batch is refused whole — never applied to the wrong batch.
     */
    suspend fun reconcile(batchId: UUID, lines: List<ClaimReceiptLine>): ClaimBatch {
        val batch = batches.findById(batchId) ?: throw ClaimBatchNotFoundException(batchId)
        lines.forEach { require(it.claimId in batch.claimIds) { "claim ${it.claimId} is not in batch $batchId" } }
        lines.forEach { line ->
            val claim = claims.findById(line.claimId) ?: throw IncentiveClaimNotFoundException(line.claimId)
            if (claim.status != ClaimStatus.SUBMITTED) return@forEach // already reconciled: idempotent
            if (line.accepted) {
                val amount = requireNotNull(line.amount) { "accepted line for ${line.claimId} needs an amount" }
                val received = receiveWithNote(claim, amount, line.reason)
                claims.update(received)
                ledger.append(ledgerEntry(received, LedgerEntryKind.RECEIVED, amount))
                contributionService.creditIncentive(
                    claim.contractId,
                    claim.id,
                    IncomingPayment(
                        paymentId = "incentive:${claim.id}",
                        amount = amount,
                        currency = claim.currency,
                        valueDate = today(),
                        reference = null,
                        channel = ContributionChannel.STATE_INCENTIVE,
                    ),
                )
                notifyIncentive(ParticipantNotificationKind.INCENTIVE_RECEIVED, received, amount)
            } else {
                claims.update(claim.reject(line.reason ?: "rejected without a stated reason", now()))
            }
        }
        val open = batch.claimIds.mapNotNull { claims.findById(it) }.any { it.status == ClaimStatus.SUBMITTED }
        val updated = if (open) batch else batch.copy(status = ClaimBatchStatus.RECONCILED)
        if (updated != batch) batches.update(updated)
        return updated
    }

    /** A partial payment keeps the agency's reason code next to the shortfall. */
    private fun receiveWithNote(claim: IncentiveClaim, amount: BigDecimal, reason: String?): IncentiveClaim {
        val received = claim.receive(amount, now())
        return if (amount < claim.claimedAmount && reason != null) received.copy(rejectionReason = reason) else received
    }

    /** A received incentive goes back to the agency (correction or clawback). */
    suspend fun returnClaim(claimId: UUID): IncentiveClaim {
        val claim = claims.findById(claimId) ?: throw IncentiveClaimNotFoundException(claimId)
        val returned = claim.markReturned(now())
        claims.update(returned)
        ledger.append(ledgerEntry(returned, LedgerEntryKind.RETURNED, requireNotNull(claim.receivedAmount)))
        notifyIncentive(ParticipantNotificationKind.INCENTIVE_RETURNED, returned, requireNotNull(claim.receivedAmount))
        return returned
    }

    suspend fun status(contractId: UUID): IncentiveStatus {
        requireContract(contractId)
        val entries = ledger.byContract(contractId)
        val balances = entries.groupBy { it.incentiveId }.map { (id, list) ->
            IncentiveBalance(
                incentiveId = id,
                received = list.filter { it.kind == LedgerEntryKind.RECEIVED }.fold(BigDecimal.ZERO) { a, e ->
                    a +
                        e.amount
                },
                returned = list.filter { it.kind == LedgerEntryKind.RETURNED }.fold(BigDecimal.ZERO) { a, e ->
                    a +
                        e.amount
                },
            )
        }
        return IncentiveStatus(claims.byContract(contractId), balances)
    }

    /** What early termination on [on] would have to return (read by S5). */
    suspend fun clawbackPreview(contractId: UUID, on: LocalDate): List<ClawbackItem> {
        val contract = requireContract(contractId)
        val firstYear = contributions.byContract(contractId).minOfOrNull { it.taxYear } ?: on.year
        val deductible = (firstYear..on.year).associateWith { year -> taxYear(contract, year).deductibleAmount }
        return IncentiveEngine.clawback(packOf(contract), ledger.byContract(contractId), deductible, on.year)
    }

    /** The S5 `IncentiveClawbackPort.balance` query: what an exit on [asOf] must return, and the tax bases. */
    suspend fun clawbackBalance(contractId: UUID, asOf: LocalDate): ClawbackBalance {
        val contract = requireContract(contractId)
        val all = contributions.byContract(contractId)
        val years = ((all.minOfOrNull { it.taxYear } ?: asOf.year)..asOf.year).map { taxYear(contract, it) }
        val entries = ledger.byContract(contractId)
        val toReturn = IncentiveEngine.clawback(
            packOf(contract),
            entries,
            years.associate {
                it.taxYear to
                    it.deductibleAmount
            },
            asOf.year,
        )
            .filter { it.kind == ClawbackKind.RETURN_TO_AGENCY }
            .fold(BigDecimal.ZERO) { a, i -> a + i.amount }
        val deducted = years.filter { it.deductibleAmount.signum() > 0 }.associate { it.taxYear to it.deductibleAmount }
        val own = all.filter { it.source == ContributionSource.PARTICIPANT }.fold(BigDecimal.ZERO) { a, c ->
            a +
                c.amount
        }
        return ClawbackBalance(
            stateIncentivesToReturn = IncentiveEngine.money(toReturn),
            stateIncentivesReceived = IncentiveEngine.money(
                entries.filter { it.kind == LedgerEntryKind.RECEIVED }.fold(BigDecimal.ZERO) { a, e -> a + e.amount },
            ),
            deductedContributionsByYear = deducted,
            employerExemptByYear = years.filter { it.employerExempt.signum() > 0 }.associate {
                it.taxYear to
                    it.employerExempt
            },
            ownContributionsNotDeducted = IncentiveEngine.money(
                (
                    own -
                        deducted.values.fold(BigDecimal.ZERO, BigDecimal::add)
                    ).max(BigDecimal.ZERO),
            ),
        )
    }

    /**
     * The S5 `IncentiveClawbackPort.settleClawback` command: writes RETURNED ledger entries for
     * [amount], spread over the incentives with a positive balance. Each entry is keyed on
     * [idempotencyKey], so a replayed activity changes nothing. Over-returning is refused.
     */
    suspend fun settleClawback(contractId: UUID, amount: BigDecimal, idempotencyKey: String) {
        require(amount.signum() > 0) { "clawback amount must be positive" }
        val contract = requireContract(contractId)
        val entries = ledger.byContract(contractId)
        if (entries.any { it.idempotencyKey?.startsWith("$idempotencyKey:") == true }) return
        val balances = entries.groupBy { it.incentiveId }.mapValues { (_, l) ->
            l.fold(BigDecimal.ZERO) { a, e ->
                a +
                    e.signed
            }
        }
            .filterValues { it.signum() > 0 }
        require(amount <= balances.values.fold(BigDecimal.ZERO, BigDecimal::add)) {
            "clawback $amount exceeds the incentive balance"
        }
        var left = amount
        balances.forEach { (incentiveId, balance) ->
            if (left.signum() == 0) return@forEach
            val part = left.min(balance)
            left -= part
            val today = today()
            ledger.append(
                IncentiveLedgerEntry(
                    Ids.newId(), contract.contractId, incentiveId, null, LedgerEntryKind.RETURNED, part,
                    today.year, YearMonth.from(today), now(), "$idempotencyKey:$incentiveId",
                ),
            )
        }
    }

    suspend fun taxSummary(contractId: UUID, year: Int): TaxYearSummary {
        val contract = requireContract(contractId)
        return summaries.findFinal(contractId, year) ?: taxYear(contract, year)
    }

    suspend fun declareExternalCapUsage(contractId: UUID, year: Int, usage: Map<String, BigDecimal>) {
        val contract = requireContract(contractId)
        usage.forEach { (group, amount) -> require(amount.signum() >= 0) { "usage for '$group' must not be negative" } }
        check(summaries.findFinal(contractId, year) == null) { "tax year $year is already certified" }
        summaries.declareExternalCapUsage(contract.participantPartyId, year, usage)
    }

    /** Freezes a CLOSED tax year and issues its certificate through document-service. Idempotent. */
    suspend fun issueCertificate(contractId: UUID, year: Int): TaxYearSummary {
        val contract = requireContract(contractId)
        summaries.findFinal(contractId, year)?.let { return it }
        check(year < today().year) { "tax year $year has not closed yet" }
        val live = taxYear(contract, year)
        val reference = references.referenceFor(contractId)
        val documentId = documents.generate(live, contract.participantPartyId, reference)
        val final = live.copy(finalizedAt = now(), certificateDocumentId = documentId)
        summaries.saveFinal(final)
        return final
    }

    private suspend fun taxYear(contract: ContractFundingView, year: Int): TaxYearSummary {
        val siblings = directory.byParticipant(contract.participantPartyId)
        val inputs = siblings.map { c ->
            val yearContributions = contributions.byContractAndYear(c.contractId, year)
            ContractYearInput(
                contractId = c.contractId,
                pack = packOf(c),
                participantAnnual = sum(yearContributions.filter { it.source == ContributionSource.PARTICIPANT }),
                employerContributions = yearContributions.filter { it.source == ContributionSource.EMPLOYER },
            )
        }
        val allocation = IncentiveEngine.allocateTaxYear(
            inputs,
            summaries.externalCapUsage(contract.participantPartyId, year),
        )
            .getValue(contract.contractId)
        val own = contributions.byContractAndYear(contract.contractId, year)
        val stateNet = ledger.byContract(contract.contractId).filter { it.taxYear == year }
            .fold(BigDecimal.ZERO) { a, e -> a + e.signed }
        return TaxYearSummary(
            contractId = contract.contractId,
            taxYear = year,
            currency = contract.currency,
            participantContributions = IncentiveEngine.money(
                sum(
                    own.filter {
                        it.source ==
                            ContributionSource.PARTICIPANT
                    },
                ),
            ),
            employerContributions = IncentiveEngine.money(sum(own.filter { it.source == ContributionSource.EMPLOYER })),
            stateIncentives = IncentiveEngine.money(stateNet),
            transferIn = IncentiveEngine.money(sum(own.filter { it.source == ContributionSource.TRANSFER_IN })),
            deductibleAmount = allocation.deductible,
            indicativeTaxSaving = allocation.indicativeSaving,
            sharedCapUsedElsewhere = allocation.usedElsewhere,
            employerExemptions = allocation.employerExemptions,
        )
    }

    private fun ledgerEntry(claim: IncentiveClaim, kind: LedgerEntryKind, amount: BigDecimal) = IncentiveLedgerEntry(
        id = Ids.newId(),
        contractId = claim.contractId,
        incentiveId = claim.incentiveId,
        claimId = claim.id,
        kind = kind,
        amount = amount,
        taxYear = claim.period.year,
        period = claim.period,
        occurredAt = now(),
    )

    private suspend fun formatOf(claim: IncentiveClaim): String {
        val contract = requireContract(claim.contractId)
        val rule = packOf(contract).incentives.first { it.id == claim.incentiveId }
        return rule.claimFormat ?: UNSPECIFIED_FORMAT
    }

    private fun packOf(c: ContractFundingView): JurisdictionPack =
        registry.pinned(c.jurisdiction, ProductLine.valueOf(c.productLine), c.packVersion)

    /** Informational (#12379): sent after the ledger is written; its outcome never undoes it. */
    private suspend fun notifyIncentive(kind: ParticipantNotificationKind, claim: IncentiveClaim, amount: BigDecimal) {
        val party = requireContract(claim.contractId).participantPartyId
        ParticipantNotices.send(
            notifier,
            ParticipantNotices.incentive(kind, party, claim.contractId, claim.period, amount, claim.currency),
        )
    }

    private suspend fun requireContract(id: UUID): ContractFundingView =
        directory.find(id) ?: throw ContractNotFoundException(id)

    private fun sum(list: List<com.openbank.pension.domain.contribution.Contribution>) =
        list.fold(BigDecimal.ZERO) { a, c -> a + c.amount }

    private fun now(): Instant = clock.instant()

    private fun today(): LocalDate = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)

    private companion object {
        const val MAX_PERIOD_MONTHS = 11L
        const val MONTHS_PER_YEAR = 12
        const val UNSPECIFIED_FORMAT = "unspecified"
    }
}
