// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.application.onboarding

import com.openbank.pension.domain.model.ProductLine
import com.openbank.pension.domain.onboarding.ActivationTrigger
import com.openbank.pension.domain.onboarding.KeyInformationDocumentType
import com.openbank.pension.domain.onboarding.OnboardingApplication
import com.openbank.pension.domain.onboarding.OnboardingRules
import com.openbank.pension.domain.onboarding.OnboardingStatus
import com.openbank.pension.domain.onboarding.SuitabilityAssessment
import com.openbank.pension.domain.transfer.FundsArrival
import com.openbank.pension.domain.transfer.IncentiveHistoryEntry
import com.openbank.pension.domain.transfer.TransferRequest
import com.openbank.pension.domain.transfer.TransferStatus
import java.time.LocalDate
import java.util.UUID

class OnboardingNotFoundException(what: String, id: UUID) : RuntimeException("$what $id not found")

/** A collaborator the step depends on could not answer; the participant may retry later (503). */
class IntegrationUnavailableException(message: String) : RuntimeException(message)

/** Two writers raced on one aggregate; the loser re-reads and retries (409). */
class ConcurrentModificationException(message: String) : IllegalStateException(message)

// --- persistence -----------------------------------------------------------------------------

interface OnboardingApplicationRepository {
    /** Optimistic: refuses with [ConcurrentModificationException] when the stored version moved. */
    suspend fun save(application: OnboardingApplication): OnboardingApplication
    suspend fun findById(id: UUID): OnboardingApplication?
    suspend fun findByStatus(status: OnboardingStatus?, limit: Int): List<OnboardingApplication>
    suspend fun findByTransferRequest(transferId: UUID): OnboardingApplication?
    suspend fun findByContract(contractId: UUID): OnboardingApplication?
}

interface SuitabilityAssessmentRepository {
    suspend fun save(assessment: SuitabilityAssessment): SuitabilityAssessment
    suspend fun findById(id: UUID): SuitabilityAssessment?
}

interface TransferRequestRepository {
    suspend fun save(request: TransferRequest): TransferRequest
    suspend fun findById(id: UUID): TransferRequest?
    suspend fun findByContract(contractId: UUID): List<TransferRequest>
    suspend fun findByStatus(status: TransferStatus?, limit: Int): List<TransferRequest>
}

/** The onboarding extension of each loaded jurisdiction pack, by the core pack's pinned key. */
interface OnboardingRulesRegistry {
    fun rules(jurisdiction: String, productLine: ProductLine, packVersion: Int): OnboardingRules
}

// --- other services (ports; adapters in infrastructure) ---------------------------------------

enum class KycStatus { VERIFIED, PENDING, NOT_STARTED, REJECTED }

/**
 * What KYC already established about a party (ADR-0334 §4 "KYC reuse"). Verified attributes win
 * over declared ones; a null attribute means KYC holds none and the declaration stands.
 */
data class KycProfile(
    val status: KycStatus,
    val fullLegalCapacity: Boolean,
    val verifiedBirthDate: LocalDate?,
    val verifiedResidencyCountry: String?,
)

/**
 * party-service: whether [guardianPartyId] is the VERIFIED legal guardian of [wardPartyId]. The
 * only way a party other than the applicant may act on an application.
 */
interface PartyRelationPort {
    suspend fun isLegalGuardian(guardianPartyId: UUID, wardPartyId: UUID): Boolean
}

/** party-service / kyc-service. Returns null when the party is unknown. */
interface PartyKycPort {
    suspend fun profile(partyId: UUID): KycProfile?
}

data class KidRequest(
    val applicationId: UUID,
    val partyId: UUID,
    val type: KeyInformationDocumentType,
    val templateCode: String,
    val productLine: ProductLine,
    val strategyCode: String,
    val language: String?,
)

data class GeneratedDocument(val documentId: String, val sha256: String)

/** document-service: renders the key-information document for one strategy choice. */
interface KeyInformationDocumentPort {
    suspend fun generate(request: KidRequest): GeneratedDocument
}

/** Outcome of spending an SCA challenge. Distinct values — never a boolean shared with "skipped". */
enum class SignatureOutcome { VERIFIED, REJECTED }

/**
 * sca-service: spends a challenge on exactly the operation it authorised (dynamic linking) — the
 * [operationRef] names the application or transfer and its counterparty, [documentSha256] the
 * document signed. Server-side and SINGLE-USE: a challenge spent once answers REJECTED for any
 * second use. The adapter raises [IntegrationUnavailableException] when it cannot ask; it never
 * answers VERIFIED for a check it did not perform.
 */
interface SignatureVerificationPort {
    suspend fun verify(
        partyId: UUID,
        challengeId: String,
        documentSha256: String?,
        operationRef: String,
    ): SignatureOutcome
}

enum class CounterpartyDispatch { DISPATCHED, REFUSED }

/** What dispatching a transfer-in to the ceding provider led to. */
enum class DispatchResult { SENT, ENDED }

data class CounterpartyReceipt(val outcome: CounterpartyDispatch, val reference: String?, val reason: String? = null)

/** The other pension provider of a transfer. */
interface TransferCounterpartyPort {
    /** Transfer-in: ask the ceding provider to move the contract here. */
    suspend fun requestTransferIn(request: TransferRequest): CounterpartyReceipt

    /** Transfer-in: withdraw an outstanding request (compensation). */
    suspend fun cancelTransferIn(request: TransferRequest)

    /** Transfer-out: pay the net amount and the incentive history to the receiving provider. */
    suspend fun payTransferOut(request: TransferRequest, incentiveHistory: List<IncentiveHistoryEntry>): String
}

// --- orchestration ------------------------------------------------------------------------------

/** The timers one onboarding workflow runs on, resolved from the pinned pack at signature. */
data class OnboardingTimers(
    val coolingOffDays: Int,
    val activationTrigger: ActivationTrigger,
    val activationDeadlineDays: Int,
)

data class TransferInTimers(val responseDeadlineDays: Int, val fundsGraceDays: Int, val coolingOffDays: Int)

/**
 * Starts and signals the long-running workflows. Suspending, because the adapter's calls are
 * blocking gRPC and must leave the event loop. Starting is idempotent per aggregate id, so a
 * retried signature after a lost response cannot start a second workflow.
 */
interface PensionOrchestrator {
    suspend fun startOnboarding(applicationId: UUID, timers: OnboardingTimers)
    suspend fun signalContributionReceived(applicationId: UUID)
    suspend fun signalWithdrawal(applicationId: UUID)
    suspend fun startTransferIn(transferId: UUID, applicationId: UUID, timers: TransferInTimers)
    suspend fun signalCounterpartyAccepted(transferId: UUID)
    suspend fun signalCounterpartyRejected(transferId: UUID, reason: String)
    suspend fun signalFundsReceived(transferId: UUID, arrival: FundsArrival)
    suspend fun signalTransferWithdrawal(transferId: UUID)
    suspend fun startTransferOut(transferId: UUID)
}
