// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.port.out

import com.openbank.delegation.domain.model.ApprovalRequest
import com.openbank.delegation.domain.model.ApprovalStatus
import com.openbank.delegation.domain.model.RepresentationMandate
import com.openbank.delegation.domain.model.SignerGroup
import com.openbank.delegation.domain.model.SigningAmount
import com.openbank.delegation.domain.model.SigningPolicy
import com.openbank.delegation.domain.model.TrustedPayee
import com.openbank.libs.domain.event.DomainEvent
import java.time.Instant
import java.util.UUID

/** An entity a human may currently act for, as party-service reports it LIVE. */
data class ActingForEntity(val entityPartyId: UUID, val name: String?)

/**
 * party-service's register, read live (ADR-0312). Every method either answers from a successful
 * read or throws [MandateDirectoryUnavailableException] — an unreadable register never reads as
 * "no mandates", which would silently shrink the eligible set.
 */
interface MandateDirectory {
    suspend fun activeMandates(entityPartyId: UUID): List<RepresentationMandate>

    suspend fun actingFor(humanPartyId: UUID): List<ActingForEntity>

    /**
     * Display names for notification copy only. Best effort by contract: null when the name
     * cannot be read — a missing name never blocks or changes a signing decision.
     */
    suspend fun entityName(entityPartyId: UUID): String?

    suspend fun personName(partyId: UUID): String?
}

class MandateDirectoryUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** What a co-signer's SCA challenge must be dynamically linked to. */
data class ApprovalLink(
    val approvalRequestId: UUID,
    val payloadSha256: String,
    val amount: SigningAmount?,
    val creditorIban: String?,
)

enum class ScaVerdict { VERIFIED, REFUSED, UNAVAILABLE }

interface ApprovalScaVerifier {
    /**
     * The initiator's payment SCA was consumed by customer-edge with dynamic linking to amount,
     * currency and creditor before the request is created. Verified by READ: the challenge must be
     * the initiator's, a payment initiation, completed AND consumed.
     */
    suspend fun verifyConsumedInitiatorChallenge(challengeId: UUID, partyId: UUID): ScaVerdict

    /**
     * A co-signer's challenge is consumed HERE, stating the linking data, so sca-service refuses a
     * challenge that was raised for anything else (ADR-0312).
     */
    suspend fun consumeApprovalChallenge(challengeId: UUID, partyId: UUID, link: ApprovalLink): ScaVerdict
}

/** State read inside the locking transaction, for guards that must see the committed truth. */
data class TransitionContext(val currentPolicyVersion: Int)

/** A change that takes effect atomically with an administrative approval. */
sealed interface AppliedChange {
    data class ReplacePolicy(val policy: SigningPolicy) : AppliedChange

    data class UpsertGroup(val group: SignerGroup, val approvalId: UUID, val at: Instant) : AppliedChange

    data class AddPayee(val payee: TrustedPayee) : AppliedChange

    data class RemovePayee(val payeeId: UUID, val approvalId: UUID, val at: Instant) : AppliedChange
}

data class Transition(val next: ApprovalRequest, val events: List<DomainEvent>, val change: AppliedChange? = null)

private val SUBMISSION_DIGEST = Regex("[0-9a-f]{64}")

data class DomesticSubmissionKey(val digest: String, val debitAccountId: UUID) {
    init {
        require(SUBMISSION_DIGEST.matches(digest)) { "submissionKeyDigest must be a lowercase SHA-256 digest" }
    }
}

data class ApprovalSubmissionScope(
    val entityPartyId: UUID,
    val initiatorPartyId: UUID,
    val correlation: String,
    val key: DomesticSubmissionKey? = null,
) {
    init {
        require(SUBMISSION_DIGEST.matches(correlation)) { "submissionCorrelation must be a lowercase SHA-256 digest" }
    }
}

enum class DomesticSubmissionMode { DIRECT, HELD }

data class DomesticSubmissionBinding(
    val scope: ApprovalSubmissionScope,
    val payloadSha256: String,
    val mode: DomesticSubmissionMode,
)

data class ApprovalSubmissionState(val approvalId: UUID, val status: ApprovalStatus, val releaseRef: String?)

class ApprovalSignatureChallengeConflict : RuntimeException("The SCA challenge already signed an approval")

@Suppress("TooManyFunctions")
interface BusinessSigningRepository {
    suspend fun findPolicy(entityPartyId: UUID): SigningPolicy?

    suspend fun listGroups(entityPartyId: UUID): List<SignerGroup>

    suspend fun listActivePayees(entityPartyId: UUID): List<TrustedPayee>

    suspend fun findPayee(entityPartyId: UUID, payeeId: UUID): TrustedPayee?

    /** Inserts the request, its signatures and events in one transaction. */
    suspend fun create(request: ApprovalRequest, events: List<DomainEvent>)

    /** Commits the receipt with the approval, its signature and its events. False when another receipt won. */
    suspend fun createWithSubmission(
        request: ApprovalRequest,
        events: List<DomainEvent>,
        scope: ApprovalSubmissionScope,
    ): Boolean

    /** Exact scoped lookup of the retained receipt and current lifecycle fields. */
    suspend fun findSubmission(scope: ApprovalSubmissionScope): ApprovalSubmissionState?

    suspend fun findSubmissionBinding(key: DomesticSubmissionKey): DomesticSubmissionBinding?

    /** Atomically inserts a binding or returns the committed binding that won the same key. */
    suspend fun bindSubmission(candidate: DomesticSubmissionBinding): DomesticSubmissionBinding

    suspend fun find(entityPartyId: UUID, id: UUID): ApprovalRequest?

    /** The request a (single-use) SCA challenge already signed, if any. */
    suspend fun findBySignatureChallenge(scaChallengeId: UUID): ApprovalRequest?

    suspend fun list(entityPartyId: UUID, status: ApprovalStatus?, limit: Int): List<ApprovalRequest>

    suspend fun listPending(entityPartyIds: Set<UUID>, limit: Int): List<ApprovalRequest>

    /**
     * Locks the request row, applies [decide] to the committed state and persists the result, its
     * new signatures, its events and any [AppliedChange] in ONE transaction.
     */
    suspend fun transition(
        entityPartyId: UUID,
        id: UUID,
        decide: (ApprovalRequest, TransitionContext) -> Transition,
    ): ApprovalRequest

    /**
     * The single-use release: `UPDATE … SET status = RELEASED WHERE status = APPROVED AND not
     * expired`. True for exactly one caller, however many race.
     */
    suspend fun claimRelease(entityPartyId: UUID, id: UUID, claimToken: UUID, now: Instant): Boolean

    suspend fun findDomesticReleaseKeyDigest(entityPartyId: UUID, approvalId: UUID): String?

    suspend fun findExpirable(now: Instant, limit: Int): List<ApprovalRequest>
}
