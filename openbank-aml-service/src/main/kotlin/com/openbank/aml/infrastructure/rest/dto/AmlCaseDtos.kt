// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.infrastructure.rest.dto

import com.openbank.aml.application.port.`in`.CreateAmlCaseCommand
import com.openbank.aml.application.port.`in`.HoldDecisionCommand
import com.openbank.aml.application.port.`in`.UpdateAmlDecisionCommand
import com.openbank.aml.domain.model.AmlCase
import com.openbank.aml.domain.model.AmlCaseStatus
import com.openbank.aml.domain.model.AmlRiskLevel
import com.openbank.aml.domain.model.CaseOrigin
import com.openbank.aml.domain.model.HoldDisposition
import com.openbank.aml.domain.model.ScreeningType
import java.time.Instant
import java.util.UUID

data class CreateAmlCaseRequest(
    val partyId: UUID,
    val accountId: UUID?,
    val transactionId: UUID?,
    val customerReference: String,
    val screeningType: String,
    val riskLevel: String,
    val alertCode: String,
    val alertDetail: String?,
    val matchedEntity: String?,
) {
    fun toCommand(idempotencyKey: String, requestHash: String? = null) = CreateAmlCaseCommand(
        idempotencyKey = idempotencyKey,
        // Fixed here, not read from the request: a caller cannot claim another entry point's provenance.
        origin = CaseOrigin.REST,
        requestHash = requestHash,
        partyId = partyId,
        accountId = accountId,
        transactionId = transactionId,
        customerReference = customerReference,
        screeningType = ScreeningType.valueOf(screeningType),
        riskLevel = AmlRiskLevel.valueOf(riskLevel),
        alertCode = alertCode,
        alertDetail = alertDetail,
        matchedEntity = matchedEntity,
    )
}

data class UpdateAmlDecisionRequest(
    val targetStatus: String,
    val decisionReason: String?,
    val assignedAnalyst: String?,
    /**
     * Ignored (ADR-0371 §5). Who decided is the authenticated caller; a name in the body was a field
     * anyone could fill with someone else's. Still accepted so existing clients keep working.
     */
    val decidedBy: String? = null,
) {
    fun toCommand(caseId: UUID, authenticatedCaller: String) = UpdateAmlDecisionCommand(
        caseId = caseId,
        targetStatus = AmlCaseStatus.valueOf(targetStatus),
        decisionReason = decisionReason,
        assignedAnalyst = assignedAnalyst,
        decidedBy = authenticatedCaller,
    )
}

/** Body of a hold-decision proposal and of its confirmation, which restates the proposal (ADR-0374 point 3). */
data class HoldDecisionRequest(val outcome: String?, val reason: String?) {
    fun toCommand(caseId: UUID, authenticatedCaller: String): HoldDecisionCommand {
        val outcomeName = requireNotNull(outcome) { "outcome is required" }
        val parsed = HoldDisposition.entries.firstOrNull { it.name == outcomeName }
        require(parsed != null) { "outcome must be one of ${HoldDisposition.entries.joinToString()}" }
        val text = requireNotNull(reason) { "reason is required" }
        require(text.isNotBlank()) { "reason is required" }
        return HoldDecisionCommand(caseId, parsed, text, authenticatedCaller)
    }
}

/** The pending (or confirmed) proposal of a held payment's case, and the case status beside it. */
data class HoldDecisionProposalResponse(
    val caseId: UUID,
    val status: String,
    val outcome: String,
    val reason: String,
    val proposedBy: String,
    val proposedAt: Instant,
)

fun AmlCase.toHoldProposalResponse(): HoldDecisionProposalResponse? = holdProposal?.let {
    HoldDecisionProposalResponse(id, status.name, it.outcome.name, it.reason, it.proposedBy, it.proposedAt)
}

data class AmlCaseResponse(
    val id: UUID,
    val idempotencyKey: String,
    val partyId: UUID,
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
    /**
     * True when this case can be closed or escalated only through an approved four-eyes disposition
     * proposal (ADR-0371 §5). A direct `PUT /decision` to anything but UNDER_REVIEW is refused for
     * it, so a client needs to know which flow to offer before the officer has written a rationale.
     */
    val requiresFourEyesDisposition: Boolean = false,
    /**
     * False while the owning party is not established. [partyId] is then not a party: it is the nil
     * UUID, or on a case from before a missing party could be stored the account id. A resolved case
     * answers `true` and its real party.
     */
    val partyResolved: Boolean = true,
)

fun AmlCase.toResponse() = AmlCaseResponse(
    id = id,
    idempotencyKey = idempotencyKey,
    partyId = partyId ?: AmlCase.UNRESOLVED_PARTY_WIRE_ID,
    accountId = accountId,
    transactionId = transactionId,
    customerReference = customerReference,
    screeningType = screeningType,
    riskLevel = riskLevel,
    status = status,
    alertCode = alertCode,
    alertDetail = alertDetail,
    matchedEntity = matchedEntity,
    decisionReason = decisionReason,
    assignedAnalyst = assignedAnalyst,
    decidedBy = decidedBy,
    screenedAt = screenedAt,
    decidedAt = decidedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
    requiresFourEyesDisposition = isPackMonitoringCase,
    partyResolved = partyEstablished,
)
