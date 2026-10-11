// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.application.usecase

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.pension.application.port.out.ContractFundingDirectory
import com.openbank.pension.application.port.out.ContractFundingView
import com.openbank.pension.application.port.out.ContractNotFoundException
import com.openbank.pension.application.port.out.ContractReferenceRepository
import com.openbank.pension.application.port.out.ContributionRepository
import com.openbank.pension.application.port.out.EmployerDirectoryPort
import com.openbank.pension.application.port.out.EmployerEnrolmentRepository
import com.openbank.pension.application.port.out.FundAdministrationPort
import com.openbank.pension.application.port.out.MandateRequest
import com.openbank.pension.application.port.out.OnboardingActivationPort
import com.openbank.pension.application.port.out.PaymentMandatePort
import com.openbank.pension.application.port.out.UnmatchedPaymentRepository
import com.openbank.pension.domain.contribution.Contribution
import com.openbank.pension.domain.contribution.ContributionChannel
import com.openbank.pension.domain.contribution.ContributionSource
import com.openbank.pension.domain.contribution.EmployerBatch
import com.openbank.pension.domain.contribution.EmployerLineOutcome
import com.openbank.pension.domain.contribution.EmployerLineResult
import com.openbank.pension.domain.contribution.IncomingPayment
import com.openbank.pension.domain.contribution.MoneyBounds
import com.openbank.pension.domain.contribution.UnmatchedPayment
import com.openbank.pension.domain.contribution.UnmatchedReason
import com.openbank.pension.domain.contribution.UnmatchedStatus
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Instant
import java.util.UUID

sealed interface ReceiptOutcome {
    data class Credited(val contribution: Contribution) : ReceiptOutcome

    data class Duplicate(val contribution: Contribution) : ReceiptOutcome

    data class Unmatched(val unmatched: UnmatchedPayment) : ReceiptOutcome
}

class UnmatchedPaymentNotFoundException(id: UUID) : RuntimeException("unmatched payment $id not found")

/**
 * Contribution intake (ADR-0334 S3, §4 Contribute): participant money arriving by standing order,
 * direct debit or transfer is matched to a contract by its payment reference; employer money
 * arrives as a bulk file per employer; anything unattributable waits in an operator queue.
 *
 * Every credit is idempotent on the payment id (an employer line on `paymentId#lineNo`), and every
 * NEW credit places a subscription order with the fund administrator. A failed order does not
 * un-credit the money: the contribution stays without an order id and the subscription sweep
 * places it later, keyed on the same id so the fund side cannot double-buy.
 */
@Suppress("LongParameterList", "TooManyFunctions")
class ContributionService(
    private val directory: ContractFundingDirectory,
    private val references: ContractReferenceRepository,
    private val contributions: ContributionRepository,
    private val unmatched: UnmatchedPaymentRepository,
    private val fund: FundAdministrationPort,
    private val employers: EmployerDirectoryPort,
    private val mandates: PaymentMandatePort,
    private val enrolments: EmployerEnrolmentRepository,
    private val activation: OnboardingActivationPort,
    private val clock: Clock,
) {
    private val log = Logger.getLogger(ContributionService::class.java)

    suspend fun paymentReference(contractId: UUID): String {
        requireContract(contractId)
        return references.referenceFor(contractId)
    }

    suspend fun list(contractId: UUID): List<Contribution> = contributions.byContract(contractId)

    /** A participant payment from the collection account. */
    suspend fun receive(payment: IncomingPayment): ReceiptOutcome {
        val reference = payment.reference?.trim()?.takeIf { it.isNotEmpty() }
            ?: return park(payment, UnmatchedReason.NO_REFERENCE)
        val contractId = references.contractFor(reference) ?: return park(payment, UnmatchedReason.UNKNOWN_REFERENCE)
        val contract = directory.find(contractId) ?: return park(payment, UnmatchedReason.UNKNOWN_REFERENCE)
        rejectionFor(contract, payment.currency)?.let { return park(payment, it) }
        awaitingOnboarding(contract)?.let { return park(payment, it) }
        return credit(contract, payment.paymentId, ContributionSource.PARTICIPANT, payment.channel, payment, null)
    }

    /** Operator decision: the parked payment belongs to [contractId]. */
    suspend fun assignUnmatched(id: UUID, contractId: UUID, actor: String): Contribution {
        val parked = unmatched.findById(id) ?: throw UnmatchedPaymentNotFoundException(id)
        val contract = requireContract(contractId)
        check(rejectionFor(contract, parked.payment.currency) == null && awaitingOnboarding(contract) == null) {
            "contract $contractId cannot receive this payment (status ${contract.status}, currency ${contract.currency})"
        }
        val assigned = parked.assign(contractId, actor, now())
        val outcome =
            credit(
                contract,
                parked.payment.paymentId,
                ContributionSource.PARTICIPANT,
                parked.payment.channel,
                parked.payment,
                null,
            )
        unmatched.update(assigned)
        return when (outcome) {
            is ReceiptOutcome.Credited -> outcome.contribution
            is ReceiptOutcome.Duplicate -> outcome.contribution
            is ReceiptOutcome.Unmatched -> error("credit cannot park")
        }
    }

    /** Operator decision: the parked payment goes back to the payer (the refund itself is the payment hub's). */
    suspend fun returnUnmatched(id: UUID, actor: String): UnmatchedPayment {
        val parked = unmatched.findById(id) ?: throw UnmatchedPaymentNotFoundException(id)
        return parked.returnToPayer(actor, now()).also { unmatched.update(it) }
    }

    suspend fun unmatchedQueue(status: UnmatchedStatus?): List<UnmatchedPayment> = unmatched.list(status)

    /**
     * One employer's bulk file. The employer must be a verified business (kyb); the file must
     * reconcile to the payment ([EmployerBatch] refuses it otherwise). Each line is credited or
     * parked on its own — a line naming an unknown contract does not hold back the others.
     */
    suspend fun receiveEmployerBatch(batch: EmployerBatch): List<EmployerLineResult> {
        require(employers.isVerifiedEmployer(batch.employerPartyId)) {
            "employer ${batch.employerPartyId} is not a verified business party"
        }
        return batch.lines.mapIndexed { index, line ->
            val lineNo = index + 1
            val linePayment = batch.payment.copy(
                paymentId = batch.linePaymentId(lineNo),
                amount = line.amount,
                reference = line.contractReference,
                channel = ContributionChannel.EMPLOYER_BATCH,
            )
            val contract = references.contractFor(line.contractReference.trim())?.let { directory.find(it) }
            val outcome = if (contract == null ||
                rejectionFor(contract, linePayment.currency) != null ||
                awaitingOnboarding(contract) != null
            ) {
                park(linePayment, UnmatchedReason.EMPLOYER_LINE)
            } else if (!enrolments.isEnrolled(contract.contractId, batch.employerPartyId)) {
                park(linePayment, UnmatchedReason.EMPLOYER_NOT_AUTHORISED)
            } else {
                credit(
                    contract,
                    linePayment.paymentId,
                    ContributionSource.EMPLOYER,
                    ContributionChannel.EMPLOYER_BATCH,
                    linePayment,
                    batch.employerPartyId,
                )
            }
            val result = when (outcome) {
                is ReceiptOutcome.Credited -> EmployerLineOutcome.CREDITED
                is ReceiptOutcome.Duplicate -> EmployerLineOutcome.DUPLICATE
                is ReceiptOutcome.Unmatched -> EmployerLineOutcome.UNMATCHED
            }
            EmployerLineResult(lineNo, line.contractReference, line.amount, result)
        }
    }

    /** State incentive money received for a claim — credited like any contribution, source STATE. */
    suspend fun creditIncentive(contractId: UUID, claimId: UUID, payment: IncomingPayment): Contribution {
        val contract = requireContract(contractId)
        val outcome =
            credit(
                contract,
                "incentive:$claimId",
                ContributionSource.STATE,
                ContributionChannel.STATE_INCENTIVE,
                payment,
                null,
            )
        return (outcome as? ReceiptOutcome.Credited)?.contribution ?: (outcome as ReceiptOutcome.Duplicate).contribution
    }

    /** The participant authorises [employerPartyId] to pay into the contract by bulk file. */
    suspend fun isEmployerEnrolled(contractId: UUID, employerPartyId: UUID): Boolean =
        enrolments.isEnrolled(contractId, employerPartyId)

    suspend fun enrolEmployer(contractId: UUID, employerPartyId: UUID) {
        val contract = requireContract(contractId)
        check(contract.status in ACCEPTING) { "contract ${contract.contractId} is ${contract.status}" }
        require(employers.isVerifiedEmployer(employerPartyId)) {
            "employer $employerPartyId is not a verified business party"
        }
        enrolments.enrol(contractId, employerPartyId)
    }

    /**
     * Funds transferred in from another provider (S2 calls this on arrival). Booked as source
     * TRANSFER_IN so the tax year reports them apart from new contributions. NO subscription is
     * placed: S2's transfer completion already buys the units, and a second order would double-buy.
     */
    suspend fun bookTransferIn(
        contractId: UUID,
        transferId: UUID,
        amount: java.math.BigDecimal,
        currency: String,
        valueDate: java.time.LocalDate,
    ): Contribution {
        val contract = requireContract(contractId)
        val (stored, _) = contributions.insertIfAbsent(
            Contribution(
                id = Ids.newId(),
                contractId = contract.contractId,
                paymentId = "transfer-in:$transferId",
                source = ContributionSource.TRANSFER_IN,
                channel = ContributionChannel.TRANSFER,
                amount = amount,
                currency = currency,
                valueDate = valueDate,
                subscriptionOrderId = "transfer-in:$transferId",
                receivedAt = now(),
            ),
        )
        return stored
    }

    /** Sets up the participant's regular payment, quoting the contract's reference so it matches. */
    suspend fun setUpMandate(request: MandateRequest): String {
        val contract = requireContract(request.contractId)
        check(contract.status in ACCEPTING) { "contract ${contract.contractId} is ${contract.status}" }
        require(request.currency == contract.currency) { "mandate currency must be ${contract.currency}" }
        MoneyBounds.requireAmount(request.amount, "mandate amount")
        require(IBAN.matches(request.debtorIban.replace(" ", ""))) { "debtorIban is not a well-formed IBAN" }
        return mandates.setUp(request.copy(reference = references.referenceFor(contract.contractId)))
    }

    /** Places subscription orders for credited money that has none yet (a previous attempt failed). */
    suspend fun placeMissingSubscriptions(contractIds: List<UUID>): Int {
        var placed = 0
        contractIds.forEach { id ->
            contributions.byContract(id).filter { it.subscriptionOrderId == null }.forEach { c ->
                if (subscribe(c)) placed++
            }
        }
        return placed
    }

    private suspend fun credit(
        contract: ContractFundingView,
        paymentId: String,
        source: ContributionSource,
        channel: ContributionChannel,
        payment: IncomingPayment,
        employer: UUID?,
    ): ReceiptOutcome {
        val (stored, created) = contributions.insertIfAbsent(
            Contribution(
                id = Ids.newId(),
                contractId = contract.contractId,
                paymentId = paymentId,
                source = source,
                channel = channel,
                amount = payment.amount,
                currency = payment.currency,
                valueDate = payment.valueDate,
                employerPartyId = employer,
                receivedAt = now(),
            ),
        )
        if (!created) return ReceiptOutcome.Duplicate(stored)
        // Activation is the onboarding workflow's: it is TOLD a contribution arrived and activates only
        // once its own gates pass (signature, cooling-off). The contract is never moved from here.
        if (contract.status == PENDING) activation.firstContributionReceived(contract.contractId)
        subscribe(stored)
        return ReceiptOutcome.Credited(stored)
    }

    private suspend fun subscribe(c: Contribution): Boolean = runCatching {
        val order = fund.subscribe(c.contractId, c.amount, c.currency, "contribution:${c.paymentId}")
        contributions.setSubscriptionOrder(c.id, order)
    }.onFailure { log.warnf(it, "subscription for contribution %s not placed; the sweep will retry", c.id) }.isSuccess

    private suspend fun park(payment: IncomingPayment, reason: UnmatchedReason): ReceiptOutcome.Unmatched =
        ReceiptOutcome.Unmatched(
            unmatched.insertIfAbsent(
                UnmatchedPayment(Ids.newId(), payment, reason, UnmatchedStatus.OPEN, now()),
            ),
        )

    /**
     * A PENDING_ACTIVATION contract takes money only while a SIGNED onboarding awaits its first
     * contribution. Otherwise (never signed, a transfer-in, withdrawn) the money is parked: a payment
     * must not stand in for the onboarding gates.
     */
    private suspend fun awaitingOnboarding(contract: ContractFundingView): UnmatchedReason? =
        if (contract.status == PENDING && !activation.awaitsFirstContribution(contract.contractId)) {
            UnmatchedReason.CONTRACT_NOT_ACCEPTING
        } else {
            null
        }

    private fun rejectionFor(contract: ContractFundingView, currency: String): UnmatchedReason? = when {
        contract.status !in INTAKE -> UnmatchedReason.CONTRACT_NOT_ACCEPTING
        contract.currency != currency -> UnmatchedReason.CURRENCY_MISMATCH
        else -> null
    }

    private suspend fun requireContract(id: UUID): ContractFundingView =
        directory.find(id) ?: throw ContractNotFoundException(id)

    private fun now(): Instant = clock.instant()

    companion object {
        /** SUSPENDED pauses the schedule, not the account: money that still arrives is credited. */
        val ACCEPTING = setOf("ACTIVE", "SUSPENDED")
        const val PENDING = "PENDING_ACTIVATION"

        /** Money may also arrive for a signed contract awaiting activation: the first payment activates it. */
        val INTAKE = ACCEPTING + PENDING
        private val IBAN = Regex("^[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}$")
    }
}
