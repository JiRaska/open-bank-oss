// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.funding

import com.openbank.pension.application.port.out.ActivationOutcome
import com.openbank.pension.application.port.out.ClaimBatchRepository
import com.openbank.pension.application.port.out.ContractFundingDirectory
import com.openbank.pension.application.port.out.ContractFundingView
import com.openbank.pension.application.port.out.ContractReferenceRepository
import com.openbank.pension.application.port.out.ContributionRepository
import com.openbank.pension.application.port.out.EmployerDirectoryPort
import com.openbank.pension.application.port.out.EmployerEnrolmentRepository
import com.openbank.pension.application.port.out.FundAdministrationPort
import com.openbank.pension.application.port.out.IncentiveClaimRepository
import com.openbank.pension.application.port.out.IncentiveLedgerRepository
import com.openbank.pension.application.port.out.MandateRequest
import com.openbank.pension.application.port.out.OnboardingActivationPort
import com.openbank.pension.application.port.out.PaymentMandatePort
import com.openbank.pension.application.port.out.Redemption
import com.openbank.pension.application.port.out.TaxCertificateDocumentPort
import com.openbank.pension.application.port.out.TaxYearSummaryRepository
import com.openbank.pension.application.port.out.UnmatchedPaymentRepository
import com.openbank.pension.application.port.out.Valuation
import com.openbank.pension.application.usecase.ContributionService
import com.openbank.pension.application.usecase.IncentiveService
import com.openbank.pension.domain.contribution.Contribution
import com.openbank.pension.domain.contribution.UnmatchedPayment
import com.openbank.pension.domain.contribution.UnmatchedStatus
import com.openbank.pension.domain.incentive.ClaimBatch
import com.openbank.pension.domain.incentive.ClaimStatus
import com.openbank.pension.domain.incentive.IncentiveClaim
import com.openbank.pension.domain.incentive.IncentiveLedgerEntry
import com.openbank.pension.domain.incentive.TaxYearSummary
import com.openbank.pension.infrastructure.notification.RecordingParticipantNotifier
import com.openbank.pension.infrastructure.pack.JurisdictionPackLoader
import com.openbank.pension.infrastructure.statecontribution.CzMfStateContributionClaimAdapter
import com.openbank.pension.infrastructure.statecontribution.RecordingStateAgencyGateway
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * In-memory ports with the same idempotency semantics as the SQL store (unique keys honoured).
 * The default date is in April: Q1 2026 has closed, so the CZ channel may file January's claims
 * (ZDPS §16(2)). [now] can be moved to walk through filing and return deadlines.
 */
class InMemoryFunding(var now: Instant = Instant.parse("2026-04-10T10:00:00Z")) {
    val clock: Clock = object : Clock() {
        override fun getZone(): java.time.ZoneId = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId?): Clock = this

        override fun instant(): Instant = now
    }
    val contracts = linkedMapOf<UUID, ContractFundingView>()
    val refs = linkedMapOf<UUID, String>()
    val contributionRows = mutableListOf<Contribution>()
    val unmatchedRows = linkedMapOf<UUID, UnmatchedPayment>()
    val claimRows = linkedMapOf<UUID, IncentiveClaim>()
    val batchRows = linkedMapOf<UUID, ClaimBatch>()
    val ledgerRows = mutableListOf<IncentiveLedgerEntry>()
    val finals = linkedMapOf<Pair<UUID, Int>, TaxYearSummary>()
    val external = linkedMapOf<Pair<UUID, Int>, Map<String, BigDecimal>>()
    val subscriptions = mutableListOf<String>()
    var fundDown = false
    var verifiedEmployers = mutableSetOf<UUID>()

    fun contract(
        participant: UUID = UUID.randomUUID(),
        status: String = "ACTIVE",
        productLine: String = "DPS",
        createdAt: Instant = Instant.parse("2025-01-01T00:00:00Z"),
    ): ContractFundingView = ContractFundingView(
        UUID.randomUUID(),
        participant,
        "CZ",
        productLine,
        if (productLine ==
            "DPS"
        ) {
            2
        } else {
            1
        },
        status,
        "CZK",
        createdAt,
    )
        .also { contracts[it.contractId] = it }

    val directory = object : ContractFundingDirectory {
        override suspend fun find(contractId: UUID) = contracts[contractId]
        override suspend fun byParticipant(participantPartyId: UUID) = contracts.values.filter {
            it.participantPartyId == participantPartyId
        }.sortedWith(compareBy({ it.createdAt }, { it.contractId }))
        override suspend fun fundable() = contracts.values.filter { it.status in setOf("ACTIVE", "SUSPENDED") }
    }

    val references = object : ContractReferenceRepository {
        override suspend fun referenceFor(contractId: UUID) =
            refs.getOrPut(contractId) { (1_000_000_001 + refs.size).toString() }
        override suspend fun contractFor(reference: String) = refs.entries.firstOrNull { it.value == reference }?.key
    }

    val contributions = object : ContributionRepository {
        override suspend fun insertIfAbsent(contribution: Contribution): Pair<Contribution, Boolean> {
            contributionRows.firstOrNull { it.paymentId == contribution.paymentId }?.let { return it to false }
            contributionRows += contribution
            return contribution to true
        }
        override suspend fun setSubscriptionOrder(contributionId: UUID, orderId: String) {
            val i = contributionRows.indexOfFirst { it.id == contributionId }
            contributionRows[i] = contributionRows[i].copy(subscriptionOrderId = orderId)
        }
        override suspend fun byContract(contractId: UUID) =
            contributionRows.filter { it.contractId == contractId }.sortedBy { it.valueDate }
        override suspend fun byContractAndYear(contractId: UUID, taxYear: Int) = byContract(contractId).filter {
            it.taxYear ==
                taxYear
        }
        override suspend fun byContractAndRange(contractId: UUID, from: LocalDate, toExclusive: LocalDate) =
            byContract(contractId).filter { !it.valueDate.isBefore(from) && it.valueDate.isBefore(toExclusive) }
    }

    val unmatched = object : UnmatchedPaymentRepository {
        override suspend fun insertIfAbsent(payment: UnmatchedPayment): UnmatchedPayment =
            unmatchedRows.values.firstOrNull { it.payment.paymentId == payment.payment.paymentId }
                ?: payment.also { unmatchedRows[it.id] = it }
        override suspend fun findById(id: UUID) = unmatchedRows[id]
        override suspend fun list(status: UnmatchedStatus?) = unmatchedRows.values.filter {
            status == null ||
                it.status == status
        }
        override suspend fun update(payment: UnmatchedPayment) {
            unmatchedRows[payment.id] = payment
        }
    }

    val claims = object : IncentiveClaimRepository {
        override suspend fun insertIfAbsent(claim: IncentiveClaim): Pair<IncentiveClaim, Boolean> {
            claimRows.values.firstOrNull {
                it.contractId == claim.contractId &&
                    it.incentiveId == claim.incentiveId &&
                    it.period == claim.period
            }
                ?.let { return it to false }
            claimRows[claim.id] = claim
            return claim to true
        }
        override suspend fun findById(id: UUID) = claimRows[id]
        override suspend fun byStatus(status: ClaimStatus) = claimRows.values.filter { it.status == status }
        override suspend fun byContract(contractId: UUID) = claimRows.values.filter { it.contractId == contractId }
        override suspend fun update(claim: IncentiveClaim) {
            claimRows[claim.id] = claim
        }
    }

    val batches = object : ClaimBatchRepository {
        override suspend fun fileAtomically(batch: ClaimBatch, at: Instant): Boolean {
            if (batch.claimIds.any { claimRows.getValue(it).status != ClaimStatus.PENDING }) return false
            batch.claimIds.forEach { claimRows[it] = claimRows.getValue(it).submit(batch.id, at) }
            batchRows[batch.id] = batch
            return true
        }
        override suspend fun findById(id: UUID) = batchRows[id]
        override suspend fun list() = batchRows.values.toList()
        override suspend fun update(batch: ClaimBatch) {
            batchRows[batch.id] = batch
        }
    }

    val ledger = object : IncentiveLedgerRepository {
        override suspend fun append(entry: IncentiveLedgerEntry) {
            if (entry.idempotencyKey != null && ledgerRows.any { it.idempotencyKey == entry.idempotencyKey }) return
            ledgerRows += entry
        }
        override suspend fun byContract(contractId: UUID) = ledgerRows.filter { it.contractId == contractId }
    }

    val summaries = object : TaxYearSummaryRepository {
        override suspend fun findFinal(contractId: UUID, taxYear: Int) = finals[contractId to taxYear]
        override suspend fun saveFinal(summary: TaxYearSummary) {
            finals.putIfAbsent(summary.contractId to summary.taxYear, summary)
        }
        override suspend fun externalCapUsage(participantPartyId: UUID, taxYear: Int) = external[
            participantPartyId to
                taxYear,
        ].orEmpty()
        override suspend fun declareExternalCapUsage(
            participantPartyId: UUID,
            taxYear: Int,
            usage: Map<String, BigDecimal>,
        ) {
            external[participantPartyId to taxYear] = external[participantPartyId to taxYear].orEmpty() + usage
        }
    }

    val enrolled = mutableSetOf<Pair<UUID, UUID>>()

    val enrolments = object : EmployerEnrolmentRepository {
        override suspend fun enrol(contractId: UUID, employerPartyId: UUID) {
            enrolled += contractId to employerPartyId
        }
        override suspend fun isEnrolled(contractId: UUID, employerPartyId: UUID) =
            (contractId to employerPartyId) in enrolled
    }

    /** Contracts whose SIGNED onboarding awaits its first contribution (S2's answer). */
    val awaitingOnboarding = mutableSetOf<UUID>()

    /** First-contribution signals sent to onboarding. Onboarding, not this fake, would activate. */
    val activationSignals = mutableListOf<UUID>()

    val activation = object : OnboardingActivationPort {
        override suspend fun awaitsFirstContribution(contractId: UUID) = contractId in awaitingOnboarding

        override suspend fun firstContributionReceived(contractId: UUID): ActivationOutcome {
            if (contractId !in awaitingOnboarding) return ActivationOutcome.NOT_AWAITING
            activationSignals += contractId
            return ActivationOutcome.SIGNALLED
        }
    }

    val fund = object : FundAdministrationPort {
        override suspend fun subscribe(
            contractId: UUID,
            amount: BigDecimal,
            currency: String,
            idempotencyKey: String,
        ): String {
            check(!fundDown) { "fund administrator unavailable" }
            subscriptions += idempotencyKey
            return "order-$idempotencyKey"
        }

        override suspend fun valuation(contractId: UUID, currency: String) =
            Valuation(BigDecimal.ZERO, currency, LocalDate.now(clock))

        override suspend fun redeem(contractId: UUID, amount: BigDecimal, currency: String, idempotencyKey: String) =
            Redemption(idempotencyKey, amount)

        override suspend fun reverseRedemption(contractId: UUID, redemption: Redemption, currency: String) = Unit
    }

    val employers = object : EmployerDirectoryPort {
        override suspend fun isVerifiedEmployer(employerPartyId: UUID) = employerPartyId in verifiedEmployers
    }

    val mandates = object : PaymentMandatePort {
        override suspend fun setUp(request: MandateRequest) = "mandate-${request.reference}"

        override suspend fun cancel(kind: com.openbank.pension.domain.contribution.MandateKind, externalId: String) =
            Unit
    }

    val documents = object : TaxCertificateDocumentPort {
        override suspend fun generate(summary: TaxYearSummary, participantPartyId: UUID, contractReference: String) =
            "doc-${summary.contractId}-${summary.taxYear}"
    }

    val contributionService =
        ContributionService(
            directory, references, contributions, unmatched, fund, employers, mandates, enrolments, activation, clock,
        )

    val notifier = RecordingParticipantNotifier()

    /** What the CZ channel handed to the (recording) state agency gateway. */
    val agency = RecordingStateAgencyGateway()

    val claimChannel = CzMfStateContributionClaimAdapter(agency, directory, java.util.Optional.of("12345678"))

    val incentiveService = IncentiveService(
        directory, references, contributions, claims, batches, ledger, summaries, JurisdictionPackLoader.loadRegistry(),
        listOf(claimChannel), documents, contributionService, clock, notifier,
    )
}
