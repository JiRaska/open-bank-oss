// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.domain.model

import com.openbank.libs.domain.error.ResourceConflictException
import com.openbank.libs.governance.MakerCheckerViolation
import com.openbank.libs.governance.Proposal
import com.openbank.libs.governance.ProposalState
import com.openbank.libs.governance.StaffIdentity
import java.time.Duration
import java.time.Instant
import java.util.UUID

enum class AmlCaseStatus {
    OPEN,
    UNDER_REVIEW,
    CLEARED,
    BLOCKED,
    ESCALATED,
}

enum class ScreeningType {
    CUSTOMER_ONBOARDING,
    TRANSACTION_MONITORING,
    PERIODIC_REVIEW,
    MANUAL_INVESTIGATION,
}

/**
 * aml-service's name for the shared AML case severity (ADR-0353 D3/D4): one enum, declared once in
 * `openbank-libs-fincrime`, so the rails that open cases cannot drift from it.
 */
typealias AmlRiskLevel = com.openbank.libs.fincrime.AmlCaseSeverity

/**
 * Which entry point opened a case. Set by the code of that entry point and by nothing a caller
 * sends: the create endpoint always records [REST], whatever the request says.
 *
 * It exists because a key proves nothing about who wrote the row under it. A held payment's case
 * key is what a rail accepts a release decision from, so "the case under that key" has to mean
 * "the case the hold intake opened", and only this field can say so.
 */
enum class CaseOrigin {
    /** `POST /api/v1/aml/cases`: a member of staff or a named machine caller. */
    REST,

    /** The `PAYMENT_HELD` consumer: the one case of a held payment. */
    PAYMENT_HOLD_INTAKE,

    /** The post-booking pack evaluation (ADR-0371). */
    PACK_EVALUATION,

    /** Onboarding screening of a new party, from its event or from the reconciler. */
    ONBOARDING_SCREENING,
}

/**
 * The two outcomes of a held payment's case (ADR-0374 point 3): the rail releases the payment on
 * [CLEAR] and rejects it on [BLOCK]. Deliberately not the monitoring disposition's CLEAR/REPORT.
 */
enum class HoldDisposition(val status: AmlCaseStatus) {
    CLEAR(AmlCaseStatus.CLEARED),
    BLOCK(AmlCaseStatus.BLOCKED),
}

/**
 * One analyst's proposed decision on a held payment's case, waiting for a second PERSON. Bound to
 * the proposer's person id ([proposerPersonId]) and open until [expiresAt]; [withdrawnAt] is set when
 * the proposer withdrew it. A proposal without a person id (none is ever written now) cannot be
 * confirmed: the libs four-eyes rule refuses it.
 */
data class HoldDecisionProposal(
    val outcome: HoldDisposition,
    val reason: String,
    val proposedBy: String,
    val proposedAt: Instant,
    val proposerPersonId: String? = null,
    val expiresAt: Instant? = null,
    val withdrawnBy: String? = null,
    val withdrawnAt: Instant? = null,
) {
    /**
     * Whether this is a four-eyes proposal under the person rule at all: it names the proposer's
     * person AND has an expiry. A row without either (the V17 shape, written before persons were
     * compared) is inert: it is never open, so a fresh proposal may replace it, and it can never be
     * confirmed ([Proposal.approveBy] refuses a proposal with no person). Nothing is backfilled.
     */
    val isPersonBound: Boolean
        get() = proposerPersonId != null && expiresAt != null

    /** Pending, person-bound and unexpired. The ONLY test of "a proposal blocks a new one". */
    fun isOpenAt(caseId: UUID, now: Instant): Boolean = isPersonBound && asProposal(caseId).isOpen(now)

    /** The fleet's four-eyes primitive over this proposal; every rule is enforced there. */
    fun asProposal(caseId: UUID): Proposal<HoldDisposition> = Proposal(
        id = caseId.toString(),
        action = outcome,
        proposedBy = proposedBy,
        proposedAt = proposedAt,
        state = if (withdrawnAt != null) ProposalState.WITHDRAWN else ProposalState.PROPOSED,
        proposerPersonId = proposerPersonId,
        expiresAt = expiresAt,
    )
}

/**
 * A decision on a held payment's case was refused by the four-eyes rule: it was made by one analyst
 * alone, by the proposer confirming their own proposal, or against a proposal that is not the one
 * pending. A conflict (409), never a validation error.
 */
class HoldDecisionFourEyesException(message: String, cause: Throwable? = null) :
    ResourceConflictException(message, cause = cause)

private fun refuseHold(refused: Boolean, message: () -> String) {
    if (refused) throw HoldDecisionFourEyesException(message())
}

/** The libs four-eyes refusal as this aggregate's conflict (409), keeping its cause. */
private inline fun <R> fourEyes(block: () -> R): R = try {
    block()
} catch (e: MakerCheckerViolation) {
    throw HoldDecisionFourEyesException(e.message ?: "four-eyes refused", e)
}

data class AmlCase(
    val id: UUID,
    val idempotencyKey: String,
    /**
     * The party the case is about; `null` while it is not known. Only the held-payment intake stores
     * `null`: a hold whose rail could not name the debtor's party is recorded that way and resolved
     * from [accountId] afterwards. Events publish this value exactly as stored, `null` included.
     */
    val partyId: UUID?,
    val accountId: UUID?,
    val transactionId: UUID?,
    val customerReference: String,
    val screeningType: ScreeningType,
    val riskLevel: AmlRiskLevel,
    val status: AmlCaseStatus,
    val alertCode: String,
    val alertDetail: String?,
    val matchedEntity: String?,
    val decisionReason: String?,
    val assignedAnalyst: String?,
    val decidedBy: String?,
    val screenedAt: Instant,
    val decidedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** Request fingerprint the case was created under (#10916); `null` for legacy rows and non-HTTP creates. */
    val requestHash: String? = null,
    /** `null` for rows written before provenance was recorded: unknown, so never treated as a system origin. */
    val origin: CaseOrigin? = null,
    /** The pending (or, once decided, the confirmed) four-eyes proposal of a held payment's case. */
    val holdProposal: HoldDecisionProposal? = null,
) {
    /**
     * ADR-0374 point 3: a case the hold intake opened releases or rejects a held payment when it is
     * decided, so its terminal decision takes two analysts. Keyed on [origin], the one field only the
     * intake writes; a case under a hold key with any other origin cannot be decided at all
     * ([isForeignClaimOnHoldKey]).
     */
    val requiresFourEyesHoldDecision: Boolean
        get() = origin == CaseOrigin.PAYMENT_HOLD_INTAKE

    /**
     * False while the owning party is not established: no party at all, or (rows from before a
     * missing party could be stored) the account id standing in for it (#3413).
     */
    val partyEstablished: Boolean
        get() = partyId != null && partyId != accountId

    /**
     * Whether this case may be attached to [candidate]: only while no party is established, and
     * never to its own account id, which would leave it exactly as unestablished as before.
     */
    fun resolvableTo(candidate: UUID): Boolean = !partyEstablished && candidate != accountId

    /**
     * A case that sits under a held-payment key without having been opened by the hold intake.
     *
     * A rail releases a held payment on a status-changed event that carries the hold key, and the
     * key alone says nothing about who wrote the row: the create endpoint refuses the namespace
     * now, but a pod from before that refusal accepted it, and such a row can outlive the pod. This
     * is the one guard that closes that path: [AmlCaseService.updateDecision] refuses to decide such
     * a case, so no event can leave under a hold key from a case the intake did not open.
     */
    val isForeignClaimOnHoldKey: Boolean
        get() = idempotencyKey.startsWith(PAYMENT_HOLD_KEY_PREFIX) && origin != CaseOrigin.PAYMENT_HOLD_INTAKE

    /**
     * A case opened by the post-booking pack evaluation (ADR-0371), as opposed to a
     * `TRANSACTION_MONITORING` case a payment rail opened when a screening gate referred a transfer.
     * Both share the screening type, and they are not the same thing: a rail's case is worked by
     * operators and releases a payment hold; a pack-monitoring case is confidential and four-eyes.
     * The evaluation job is the only writer of keys with [PACK_MONITORING_KEY_PREFIX]; the REST
     * create endpoint refuses them.
     */
    val isPackMonitoringCase: Boolean
        get() = screeningType == ScreeningType.TRANSACTION_MONITORING &&
            idempotencyKey.startsWith(PACK_MONITORING_KEY_PREFIX)

    fun transitionTo(
        targetStatus: AmlCaseStatus,
        decisionReason: String?,
        assignedAnalyst: String?,
        decidedBy: String,
        // No default. It used to be `Instant.EPOCH`, and the one caller that took the default was
        // production (`AmlCaseService.updateDecision`), so every terminal AML decision — an
        // analyst's and the sandbox auto-clear alike — recorded `decidedAt`/`updatedAt` as
        // 1970-01-01, straight into the DB columns and onto `aml.case.status_changed.v1`. Both
        // unit-test callers already passed a real value, so nothing observed it (#5837).
        now: Instant,
    ): AmlCase {
        // The invariant lives here, not in a route: every single-step decision goes through this
        // method, so none can decide a held payment's case alone.
        if (requiresFourEyesHoldDecision && targetStatus in TERMINAL_STATUSES) {
            throw HoldDecisionFourEyesException(
                "A held payment's case is decided by two analysts: propose the decision at " +
                    "POST /api/v1/aml/cases/$id/hold-decision/proposal and have a different analyst confirm it at " +
                    "POST /api/v1/aml/cases/$id/hold-decision/confirmation",
            )
        }
        return decide(targetStatus, decisionReason, assignedAnalyst, decidedBy, now)
    }

    /**
     * First half of a held payment's decision: [proposer] proposes [outcome] for [reason]. Changes no
     * status and emits nothing a rail acts on. A new proposal replaces an earlier one only once that
     * one is no longer open — withdrawn by its proposer or expired ([HOLD_PROPOSAL_TTL]); never while
     * it is pending, so nobody can silently swap the decision another analyst is about to confirm.
     */
    fun proposeHoldDecision(outcome: HoldDisposition, reason: String, proposer: StaffIdentity, now: Instant): AmlCase {
        refuseHold(!requiresFourEyesHoldDecision) {
            "Only a held payment's case is decided through a hold-decision proposal"
        }
        refuseHold(status in TERMINAL_STATUSES) { "The case is already decided: $status" }
        require(reason.isNotBlank()) { "reason is required" }
        refuseHold(holdProposal?.isOpenAt(id, now) == true) {
            "A decision is already proposed for this case; its proposer withdraws it, or it expires at " +
                "${holdProposal?.expiresAt}, before another can be made"
        }
        val proposal = fourEyes { Proposal.propose(id.toString(), outcome, proposer, now, HOLD_PROPOSAL_TTL) }
        return copy(
            holdProposal = HoldDecisionProposal(
                outcome = outcome,
                reason = reason.trim(),
                proposedBy = proposal.proposedBy,
                proposedAt = now,
                proposerPersonId = proposal.proposerPersonId,
                expiresAt = proposal.expiresAt,
            ),
            updatedAt = now,
        )
    }

    /**
     * The proposer — the same PERSON, from any of their accounts — withdraws the pending proposal.
     * The withdrawal stays on the case (who, when) until a new proposal is made.
     */
    fun withdrawHoldDecision(by: StaffIdentity, now: Instant): AmlCase {
        refuseHold(!requiresFourEyesHoldDecision) {
            "Only a held payment's case is decided through a hold-decision proposal"
        }
        refuseHold(status in TERMINAL_STATUSES) { "The case is already decided: $status" }
        val proposal = holdProposal
        refuseHold(proposal == null) { "No decision has been proposed for this case" }
        val withdrawn = fourEyes { proposal!!.asProposal(id).withdrawBy(by, now) }
        return copy(
            holdProposal = proposal!!.copy(withdrawnBy = withdrawn.decidedBy, withdrawnAt = now),
            updatedAt = now,
        )
    }

    /**
     * Second half: a DIFFERENT PERSON, [confirmer], authenticated with a second factor, confirms the
     * pending, unexpired proposal, restating its [outcome] and [reason]. Only here does the case reach
     * CLEARED or BLOCKED. The four-eyes rule is the fleet's [Proposal.approveBy].
     */
    fun confirmHoldDecision(outcome: HoldDisposition, reason: String, confirmer: StaffIdentity, now: Instant): AmlCase {
        refuseHold(!requiresFourEyesHoldDecision) {
            "Only a held payment's case is decided through a hold-decision proposal"
        }
        val proposal = holdProposal
        refuseHold(proposal == null) { "No decision has been proposed for this case" }
        proposal!!
        refuseHold(status in TERMINAL_STATUSES) { "The case is already decided: $status" }
        refuseHold(outcome != proposal.outcome || reason.trim() != proposal.reason) {
            "The pending proposal is not the one confirmed; read it again before confirming"
        }
        refuseHold(!proposal.isPersonBound) {
            "The pending proposal predates the person rule and cannot be confirmed; propose the decision again"
        }
        val approved = fourEyes { proposal.asProposal(id).approveBy(confirmer, now) }
        return decide(proposal.outcome.status, proposal.reason, assignedAnalyst, approved.decidedBy!!, now)
    }

    private fun decide(
        targetStatus: AmlCaseStatus,
        decisionReason: String?,
        assignedAnalyst: String?,
        decidedBy: String,
        now: Instant,
    ): AmlCase {
        require(canTransitionTo(targetStatus)) { "Invalid AML case status transition: $status -> $targetStatus" }
        require(decidedBy.isNotBlank()) { "decidedBy is required" }
        require(targetStatus != AmlCaseStatus.BLOCKED || !decisionReason.isNullOrBlank()) {
            "decisionReason is required when blocking an AML case"
        }

        return copy(
            status = targetStatus,
            decisionReason = decisionReason?.trim()?.ifBlank { null },
            assignedAnalyst = assignedAnalyst?.trim()?.ifBlank { null },
            decidedBy = decidedBy.trim(),
            decidedAt = now,
            updatedAt = now,
        )
    }

    fun canTransitionTo(targetStatus: AmlCaseStatus): Boolean = when (status) {
        AmlCaseStatus.OPEN -> targetStatus in setOf(
            AmlCaseStatus.UNDER_REVIEW,
            AmlCaseStatus.CLEARED,
            AmlCaseStatus.BLOCKED,
            AmlCaseStatus.ESCALATED,
        )

        AmlCaseStatus.UNDER_REVIEW -> targetStatus in setOf(
            AmlCaseStatus.CLEARED,
            AmlCaseStatus.BLOCKED,
            AmlCaseStatus.ESCALATED,
        )

        AmlCaseStatus.ESCALATED -> targetStatus in setOf(
            AmlCaseStatus.UNDER_REVIEW,
            AmlCaseStatus.CLEARED,
            AmlCaseStatus.BLOCKED,
        )

        AmlCaseStatus.CLEARED,
        AmlCaseStatus.BLOCKED,
        -> false
    }

    companion object {
        /** Reserved idempotency-key prefix of cases the pack evaluation opens. */
        const val PACK_MONITORING_KEY_PREFIX = "tm:"

        /** Reserved idempotency-key prefix of the one case a held payment opens. */
        const val PAYMENT_HOLD_KEY_PREFIX = "payment-hold:"

        /** Key namespaces only this service's own jobs and consumers write; the create endpoint refuses them. */
        /** How long a hold-decision proposal stays open for a second analyst. */
        val HOLD_PROPOSAL_TTL: Duration = Duration.ofHours(24)

        /** The statuses a rail acts on; on a held payment's case only a confirmation reaches them. */
        val TERMINAL_STATUSES = setOf(AmlCaseStatus.CLEARED, AmlCaseStatus.BLOCKED)

        val RESERVED_KEY_PREFIXES = listOf(PACK_MONITORING_KEY_PREFIX, PAYMENT_HOLD_KEY_PREFIX)

        /**
         * What the REST response carries in its REQUIRED `partyId` when NO party is stored: the nil UUID.
         * That contract cannot express a missing party without a breaking change, so the absence is
         * stated by `partyResolved = false` next to it and this value only fills the slot. A stored
         * party is always answered as stored. Events are not bound by the REST contract and publish
         * `null`. The nil UUID is never stored and never accepted as a party.
         */
        val UNRESOLVED_PARTY_WIRE_ID: UUID = UUID(0L, 0L)
    }
}
