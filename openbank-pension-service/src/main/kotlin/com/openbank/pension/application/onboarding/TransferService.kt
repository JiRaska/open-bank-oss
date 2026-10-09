// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.application.onboarding

import com.openbank.pension.application.port.out.FundAdministrationPort
import com.openbank.pension.application.port.out.ParticipantNotifier
import com.openbank.pension.application.port.out.PensionContractRepository
import com.openbank.pension.application.port.out.Redemption
import com.openbank.pension.application.port.out.TransferInBookingPort
import com.openbank.pension.application.usecase.ParticipantNotices
import com.openbank.pension.domain.model.ContractStatus
import com.openbank.pension.domain.onboarding.OnboardingStatus
import com.openbank.pension.domain.pack.JurisdictionPackRegistry
import com.openbank.pension.domain.transfer.Compensation
import com.openbank.pension.domain.transfer.Counterparty
import com.openbank.pension.domain.transfer.FundsArrival
import com.openbank.pension.domain.transfer.TransferDirection
import com.openbank.pension.domain.transfer.TransferOrigin
import com.openbank.pension.domain.transfer.TransferRequest
import com.openbank.pension.domain.transfer.TransferStatus
import com.openbank.pension.domain.transfer.TransferTerms
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

data class TransferOutCommand(
    val contractId: UUID,
    /** The participant (customer-initiated) or null when the receiving provider asks on their behalf. */
    val partyId: UUID?,
    val receiving: Counterparty,
    val challengeId: String?,
)

/**
 * Transfer-in and transfer-out between providers (ADR-0334 §4 step 3) — the client entry points and
 * the idempotent callbacks the transfer workflows run as activities.
 */
@Suppress("TooManyFunctions", "LongParameterList")
class TransferService(
    private val transfers: TransferRequestRepository,
    private val applications: OnboardingApplicationRepository,
    private val contracts: PensionContractRepository,
    private val packs: JurisdictionPackRegistry,
    private val counterparties: TransferCounterpartyPort,
    private val funds: FundAdministrationPort,
    private val signatures: SignatureVerificationPort,
    private val orchestrator: PensionOrchestrator,
    private val onboarding: OnboardingService,
    private val transferInBooking: TransferInBookingPort,
    private val tx: TransactionRunner,
    private val clock: Clock,
    private val notifier: ParticipantNotifier,
) {

    /**
     * Every transfer write goes through here. When a write MOVES the transfer into a status the
     * participant hears about (#12379), the notice follows the stored write — informational, its
     * outcome never undoes the step. A rewrite in the same status (a retried activity) is silent.
     */
    private suspend fun persist(transfer: TransferRequest): TransferRequest {
        val before = transfers.findById(transfer.id)?.status
        val saved = transfers.save(transfer)
        if (saved.status != before && saved.status in ParticipantNotices.NOTIFIED_TRANSFER_STATUSES) {
            ParticipantNotices.send(notifier, ParticipantNotices.transferStatus(saved))
        }
        return saved
    }

    private fun today(): LocalDate = LocalDate.now(clock)
    private fun now() = clock.instant()

    suspend fun get(id: UUID, partyId: UUID?): TransferRequest {
        val transfer = transfers.findById(id) ?: throw OnboardingNotFoundException("transfer request", id)
        if (partyId != null && transfer.partyId != partyId) throw OnboardingNotFoundException("transfer request", id)
        return transfer
    }

    suspend fun list(status: TransferStatus?, limit: Int): List<TransferRequest> = transfers.findByStatus(status, limit)

    // --- transfer-out: entry ------------------------------------------------------------------

    /**
     * Validates and records a transfer-out, then starts its workflow. A participant's request must
     * be SCA-signed and may name only their own contract; a receiving provider's request is relayed
     * by an operator and carries no participant signature (the receiving provider holds it).
     */
    suspend fun requestTransferOut(command: TransferOutCommand): TransferRequest {
        val contract = contracts.findById(command.contractId)
            ?: throw OnboardingNotFoundException("transfer contract", command.contractId)
        if (command.partyId != null && contract.participantPartyId != command.partyId) {
            throw OnboardingNotFoundException("transfer contract", command.contractId)
        }
        check(contract.status == ContractStatus.ACTIVE || contract.status == ContractStatus.SUSPENDED) {
            "a ${contract.status} contract cannot be transferred out"
        }
        val pack = packs.pinned(contract.jurisdiction, contract.productLine, contract.packVersion)
        check(pack.transfer.allowed) { "this pack does not allow a transfer-out" }
        check(
            transfers.findByContract(contract.id).none {
                it.direction == TransferDirection.OUT && !it.status.terminal
            },
        ) {
            "a transfer-out of this contract is already in progress"
        }
        val origin = if (command.partyId != null) TransferOrigin.PARTICIPANT else TransferOrigin.RECEIVING_PROVIDER
        if (origin == TransferOrigin.PARTICIPANT) {
            val challenge =
                requireNotNull(command.challengeId?.takeIf { it.isNotBlank() }) { "scaChallengeId is required" }
            verifyConsent(contract.participantPartyId, challenge, outRef(contract.id, command.receiving))
        }
        val request = TransferRequest.request(
            direction = TransferDirection.OUT,
            origin = origin,
            contractId = contract.id,
            partyId = contract.participantPartyId,
            counterparty = command.receiving,
            currency = pack.currency,
            signatureRef = command.challengeId,
            deadline = TransferTerms.deadline(pack.transfer, today()),
            now = now(),
        )
        val saved = persist(request)
        if (saved.status == TransferStatus.REQUESTED) orchestrator.startTransferOut(saved.id)
        return saved
    }

    /**
     * The participant's SCA consent to a transfer-out a receiving provider requested. Without it
     * the request never leaves AWAITING_CONSENT and nothing is valued, sold or paid.
     */
    suspend fun consentTransferOut(id: UUID, partyId: UUID, challengeId: String): TransferRequest {
        val transfer = get(id, partyId)
        check(transfer.status == TransferStatus.AWAITING_CONSENT) {
            "transfer $id is ${transfer.status}, not awaiting consent"
        }
        verifyConsent(partyId, challengeId, outRef(transfer.contractId, transfer.counterparty) + ":$id")
        val saved = persist(transfer.consented(challengeId, now()))
        orchestrator.startTransferOut(saved.id)
        return saved
    }

    /** Binds the challenge to this contract and THIS receiving contract — never reusable elsewhere. */
    private fun outRef(contractId: UUID, receiving: Counterparty) =
        "pension-transfer-out:$contractId:${receiving.providerId}:${receiving.contractNumber}"

    private suspend fun verifyConsent(partyId: UUID, challengeId: String, operationRef: String) {
        if (signatures.verify(partyId, challengeId, null, operationRef) != SignatureOutcome.VERIFIED) {
            throw SignatureRejectedException("the SCA challenge was not verified")
        }
    }

    // --- transfer-in: counterparty callbacks (relayed by an operator / integration) -----------

    suspend fun counterpartyAccepted(id: UUID): TransferRequest = inbound(id).also {
        orchestrator.signalCounterpartyAccepted(id)
    }

    suspend fun counterpartyRejected(id: UUID, reason: String): TransferRequest = inbound(id).also {
        orchestrator.signalCounterpartyRejected(id, reason)
    }

    suspend fun fundsReceived(id: UUID, arrival: FundsArrival): TransferRequest {
        val transfer = inbound(id)
        require(arrival.currency == transfer.currency) { "transferred currency must be ${transfer.currency}" }
        orchestrator.signalFundsReceived(id, arrival)
        return transfer
    }

    private suspend fun inbound(id: UUID): TransferRequest {
        val transfer = get(id, null)
        check(transfer.direction == TransferDirection.IN) { "transfer $id is not a transfer-in" }
        check(!transfer.status.terminal) { "transfer $id is already ${transfer.status}" }
        return transfer
    }

    // --- transfer-in: workflow callbacks ----------------------------------------------------------

    /** Sends the request to the ceding provider. A refusal ends the transfer with compensation. */
    suspend fun dispatchIn(id: UUID): DispatchResult {
        val transfer = get(id, null)
        if (transfer.status != TransferStatus.REQUESTED) {
            return if (transfer.status.terminal) DispatchResult.ENDED else DispatchResult.SENT
        }
        val receipt = counterparties.requestTransferIn(transfer)
        if (receipt.outcome == CounterpartyDispatch.REFUSED) {
            failIn(id, TransferStatus.FAILED, receipt.reason ?: "the ceding provider refused the request")
            return DispatchResult.ENDED
        }
        persist(
            transfer.sent(
                checkNotNull(receipt.reference) {
                    "a dispatched request carries a reference"
                },
                now(),
            ),
        )
        return DispatchResult.SENT
    }

    suspend fun recordAccepted(id: UUID) {
        val transfer = get(id, null)
        if (transfer.status == TransferStatus.SENT) persist(transfer.accepted(now()))
    }

    /**
     * Funds arrived: they buy units, the contract activates with the ORIGINAL start date carried
     * over (ADR-0334 §4 step 3), and the onboarding application completes.
     */
    suspend fun completeIn(id: UUID, arrival: FundsArrival) {
        val transfer = get(id, null)
        if (transfer.status == TransferStatus.COMPLETED) return
        check(!transfer.status.terminal) { "transfer $id already ended ${transfer.status}" }
        val contract = contracts.findById(transfer.contractId)
            ?: throw OnboardingNotFoundException("contract", transfer.contractId)
        val pack = packs.pinned(contract.jurisdiction, contract.productLine, contract.packVersion)
        val received = if (transfer.status == TransferStatus.FUNDS_RECEIVED) {
            transfer
        } else {
            persist(transfer.fundsReceived(arrival, pack.transfer.carriesIncentiveHistory, now()))
        }
        funds.subscribe(contract.id, arrival.amount, arrival.currency, "transfer-in:$id")
        // Booked in S3's contribution ledger as TRANSFER_IN (tax year reports it apart; no second order).
        transferInBooking.book(contract.id, id, arrival.amount, arrival.currency, today())
        tx.inTransaction {
            val current = contracts.findById(contract.id) ?: throw OnboardingNotFoundException("contract", contract.id)
            if (current.status == ContractStatus.PENDING_ACTIVATION) {
                contracts.save(current.activate(arrival.originalStartDate, now()))
            }
            val application = applications.findByTransferRequest(id)
            if (application != null && application.status == OnboardingStatus.SIGNED) {
                applications.save(application.activate(today(), now()))
            }
            persist(received.completed(emptyList(), now()))
        }
    }

    /**
     * Ends a transfer-in that will not complete, and compensates: an outstanding request is
     * withdrawn at the ceding provider, and the never-funded contract is closed.
     */
    suspend fun failIn(id: UUID, outcome: TransferStatus, reason: String) {
        val transfer = get(id, null)
        if (transfer.status.terminal) return
        val outstanding = transfer.status == TransferStatus.SENT || transfer.status == TransferStatus.ACCEPTED
        if (outstanding && outcome != TransferStatus.REJECTED) counterparties.cancelTransferIn(transfer)
        val compensation = if (outstanding && outcome != TransferStatus.REJECTED) {
            Compensation.CEDING_CANCELLED_AND_CONTRACT_CLOSED
        } else {
            Compensation.CONTRACT_CLOSED
        }
        tx.inTransaction {
            onboarding.closeContract(transfer.contractId)
            applications.findByTransferRequest(transfer.id)?.let { application ->
                if (application.status ==
                    OnboardingStatus.SIGNED
                ) {
                    applications.save(application.transferFailed(reason, now()))
                }
            }
            persist(transfer.failed(outcome, reason, compensation, now()))
        }
    }

    // --- transfer-out: workflow callbacks ----------------------------------------------------------

    /** Re-validates and values the contract; answers false when the request was rejected. */
    suspend fun valuateOut(id: UUID): Boolean {
        val transfer = get(id, null)
        if (transfer.status != TransferStatus.REQUESTED) return !transfer.status.terminal
        val contract = contracts.findById(transfer.contractId)
            ?: throw OnboardingNotFoundException("contract", transfer.contractId)
        if (contract.status != ContractStatus.ACTIVE && contract.status != ContractStatus.SUSPENDED) {
            persist(
                transfer.failed(TransferStatus.REJECTED, "contract is ${contract.status}", Compensation.NONE, now()),
            )
            return false
        }
        val pack = packs.pinned(contract.jurisdiction, contract.productLine, contract.packVersion)
        val valuation = funds.valuation(contract.id, transfer.currency)
        check(valuation.currency == transfer.currency) {
            "valuation currency ${valuation.currency} != ${transfer.currency}"
        }
        val fee = TransferTerms.fee(pack.transfer, contract.startDate, valuation.amount, today())
        persist(transfer.valuated(valuation.amount, fee, now()))
        return true
    }

    /** Sells the units; the reference is kept so compensation can reverse exactly this redemption. */
    suspend fun redeemOut(id: UUID): String {
        val transfer = get(id, null)
        transfer.redemptionRef?.let { return it }
        check(transfer.status == TransferStatus.VALUATED) { "transfer $id is ${transfer.status}, not VALUATED" }
        val ref = funds.redeem(
            transfer.contractId,
            checkNotNull(transfer.grossAmount),
            transfer.currency,
            "transfer-out:$id",
        ).reference
        persist(transfer.copy(redemptionRef = ref, updatedAt = now()))
        return ref
    }

    /** Pays the receiving provider, then closes the contract as TRANSFERRED_OUT. */
    suspend fun settleOut(id: UUID) {
        val transfer = get(id, null)
        if (transfer.status == TransferStatus.COMPLETED) return
        val history = transfers.findByContract(transfer.contractId)
            .filter { it.direction == TransferDirection.IN && it.status == TransferStatus.COMPLETED }
            .flatMap { it.incentiveHistory }
        val settled = if (transfer.status == TransferStatus.SETTLED) {
            transfer
        } else {
            val payment = counterparties.payTransferOut(transfer, history)
            persist(transfer.settled(checkNotNull(transfer.redemptionRef) { "redeem before settling" }, now()))
                .also { check(payment.isNotBlank()) { "the counterparty payment carries a reference" } }
        }
        tx.inTransaction {
            val contract = contracts.findById(settled.contractId)
                ?: throw OnboardingNotFoundException("contract", settled.contractId)
            if (contract.status == ContractStatus.ACTIVE || contract.status == ContractStatus.SUSPENDED) {
                contracts.save(contract.requestTermination(now()).markTransferredOut(now()))
            } else if (contract.status == ContractStatus.TERMINATING) {
                contracts.save(contract.markTransferredOut(now()))
            }
            persist(settled.completed(history, now()))
        }
    }

    /** Compensation for a transfer-out that failed before paying out: the redemption is reversed. */
    suspend fun compensateOut(id: UUID, reason: String) {
        val transfer = get(id, null)
        // SETTLED means the money left: nothing to reverse, only the local completion is pending.
        if (transfer.status.terminal || transfer.status == TransferStatus.SETTLED) return
        val ref = transfer.redemptionRef
        if (ref != null) {
            funds.reverseRedemption(
                transfer.contractId,
                Redemption(ref, checkNotNull(transfer.grossAmount)),
                transfer.currency,
            )
        }
        val compensation = if (ref != null) Compensation.REDEMPTION_REVERSED else Compensation.NONE
        val failed = if (transfer.status == TransferStatus.REQUESTED) TransferStatus.REJECTED else TransferStatus.FAILED
        persist(transfer.failed(failed, reason, compensation, now()))
    }
}
