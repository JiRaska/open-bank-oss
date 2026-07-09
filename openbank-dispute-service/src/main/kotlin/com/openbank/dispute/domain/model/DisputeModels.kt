// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.dispute.domain.model

import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

enum class DisputeType { UNAUTHORIZED, DUPLICATE, GOODS_NOT_RECEIVED, NOT_AS_DESCRIBED, CREDIT_NOT_PROCESSED, TECHNICAL_ERROR, OTHER }
enum class DisputeStatus { OPEN, UNDER_REVIEW, PENDING_CUSTOMER, PENDING_MERCHANT, RESOLVED_CUSTOMER, RESOLVED_MERCHANT, WITHDRAWN, ESCALATED }
enum class DisputeResolution { CHARGEBACK, REPRESENTMENT, ARBITRATION, WITHDRAWN, PENDING }

/**
 * Remediation outcome of a dispute investigation (ADR-0117 hardening, first increment).
 * Recorded when a case moves out of evidence-gathering into a final decision — distinct from
 * [DisputeResolution], which describes the *scheme mechanism* (chargeback/representment/etc.),
 * while this describes the *investigation verdict* the gathered evidence supports.
 *
 * - [UPHELD] — the customer's dispute is valid; a compensating action (e.g. a refund) is
 *   warranted. Emits `dispute.remediation_requested` (see [DisputeService]) so a downstream
 *   consumer can act — this service does not itself move money.
 * - [REJECTED] — the evidence does not support the customer's claim; no compensation.
 * - [PARTIAL] — a partial remediation is warranted; `remediationAmount` on [Dispute] carries the
 *   compensated amount (must be less than [Dispute.amount]).
 */
enum class RemediationOutcome { UPHELD, REJECTED, PARTIAL }

data class Dispute(
    val id: UUID = UUID.randomUUID(),
    val reference: String,
    val transactionId: UUID,
    val accountId: UUID,
    val partyId: UUID,
    val disputeType: DisputeType,
    val status: DisputeStatus = DisputeStatus.OPEN,
    val resolution: DisputeResolution = DisputeResolution.PENDING,
    val amount: BigDecimal,
    val currency: String = "EUR",
    val description: String? = null,
    val merchantName: String? = null,
    val merchantId: String? = null,
    val transactionDate: LocalDate,
    val filingDate: LocalDate,
    val resolutionDeadline: LocalDate? = null,
    val resolvedAt: OffsetDateTime? = null,
    val resolvedBy: String? = null,
    val chargebackAmount: BigDecimal? = null,
    /** Investigation verdict (ADR-0117 hardening) — set together with [resolvedAt]/[resolvedBy]. */
    val remediationOutcome: RemediationOutcome? = null,
    /** Compensated amount when [remediationOutcome] is [RemediationOutcome.PARTIAL] (or the full
     * [amount] convenience-copied when [RemediationOutcome.UPHELD]); null otherwise. */
    val remediationAmount: BigDecimal? = null,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)

/**
 * An append-only evidence item attached to a dispute (ADR-0117 §4). Each item is tamper-evident:
 * [sequence] is monotonic per [disputeId] and [recordHash] commits to `(prevHash ‖ this item's
 * content)`, mirroring `openbank-audit-service`'s hash-chain pattern (ADR-0133) — see
 * `EvidenceChain` for the hashing function. No shared `openbank-libs` primitive existed to reuse
 * (the audit-service chain is bespoke to that service), so this is a small, self-contained
 * first-increment implementation local to `openbank-dispute-service`.
 */
data class DisputeEvidence(
    val id: UUID = UUID.randomUUID(),
    val disputeId: UUID,
    val submittedBy: String,
    val evidenceType: String,
    val description: String? = null,
    val fileReference: String? = null,
    /** Stamped by the application layer (DisputeService) using the injected Clock. */
    val submittedAt: OffsetDateTime? = null,
    /** 0-based position in this dispute's evidence chain. Stamped by the application layer. */
    val sequence: Long = 0,
    /** The [recordHash] of the previous evidence item for this dispute, or the chain genesis
     * constant for the first item. Stamped by the application layer. */
    val prevHash: String? = null,
    /** SHA-256 over `(prevHash, disputeId, sequence, submittedBy, evidenceType, description,
     * fileReference, submittedAt)` — see `EvidenceChain.recordHash`. Stamped by the application
     * layer; never recomputed on read except by the verify endpoint. */
    val recordHash: String? = null,
)

data class DisputeTimelineEvent(
    val id: UUID = UUID.randomUUID(),
    val disputeId: UUID,
    val eventType: String,
    val description: String,
    val actor: String? = null,
    val createdAt: OffsetDateTime,
)

data class OpenDisputeRequest(
    val transactionId: UUID,
    val accountId: UUID,
    val partyId: UUID,
    val disputeType: DisputeType,
    val amount: BigDecimal,
    val currency: String = "EUR",
    val description: String? = null,
    val merchantName: String? = null,
    val merchantId: String? = null,
    val transactionDate: LocalDate,
)

data class UpdateDisputeRequest(
    val status: DisputeStatus? = null,
    val resolution: DisputeResolution? = null,
    val chargebackAmount: BigDecimal? = null,
    val resolvedBy: String? = null,
)

/**
 * Request to record a dispute's remediation outcome (ADR-0117 hardening §3). Distinct from the
 * generic [UpdateDisputeRequest] because a resolution is a one-way transition with its own
 * validation (a [RemediationOutcome.PARTIAL] outcome requires [remediationAmount] to be present
 * and strictly less than the dispute's claimed amount).
 */
data class ResolveDisputeRequest(
    val outcome: RemediationOutcome,
    val remediationAmount: BigDecimal? = null,
    val resolvedBy: String,
    val notes: String? = null,
)

/** Result of walking a dispute's evidence chain (see `EvidenceChain.verify`). */
data class EvidenceChainVerification(
    val disputeId: UUID,
    val intact: Boolean,
    val itemsChecked: Int,
    val firstBrokenEvidenceId: UUID? = null,
)
