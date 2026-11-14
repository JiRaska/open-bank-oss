// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.infrastructure.persistence.mapper

import com.openbank.aml.domain.model.AmlCase
import com.openbank.aml.domain.model.AmlCaseStatus
import com.openbank.aml.domain.model.AmlRiskLevel
import com.openbank.aml.domain.model.CaseOrigin
import com.openbank.aml.domain.model.HoldDecisionProposal
import com.openbank.aml.domain.model.HoldDisposition
import com.openbank.aml.domain.model.ScreeningType
import com.openbank.aml.infrastructure.persistence.entity.AmlCaseEntity

fun AmlCase.toEntity() = AmlCaseEntity().also {
    it.caseId = id
    it.idempotencyKey = idempotencyKey
    it.partyId = partyId
    it.accountId = accountId
    it.transactionId = transactionId
    it.customerReference = customerReference
    it.screeningType = screeningType.name
    it.riskLevel = riskLevel.name
    it.status = status.name
    it.alertCode = alertCode
    it.alertDetail = alertDetail
    it.matchedEntity = matchedEntity
    it.decisionReason = decisionReason
    it.assignedAnalyst = assignedAnalyst
    it.decidedBy = decidedBy
    it.screenedAt = screenedAt
    it.decidedAt = decidedAt
    it.createdAt = createdAt
    it.updatedAt = updatedAt
    it.requestHash = requestHash
    it.origin = origin?.name
    it.writeHoldProposal(holdProposal)
}

fun AmlCaseEntity.writeHoldProposal(proposal: HoldDecisionProposal?) {
    holdProposalOutcome = proposal?.outcome?.name
    holdProposalReason = proposal?.reason
    holdProposedBy = proposal?.proposedBy
    holdProposedAt = proposal?.proposedAt
}

/** The stored proposal; `null` unless all four columns are set and the outcome is one this build knows. */
fun AmlCaseEntity.readHoldProposal(): HoldDecisionProposal? {
    val outcome = holdProposalOutcome?.let { name -> HoldDisposition.entries.firstOrNull { it.name == name } }
    val reason = holdProposalReason
    val by = holdProposedBy
    val at = holdProposedAt
    return if (outcome == null ||
        reason == null ||
        by == null ||
        at == null
    ) {
        null
    } else {
        HoldDecisionProposal(outcome, reason, by, at)
    }
}

fun AmlCaseEntity.toDomain() = AmlCase(
    id = caseId,
    idempotencyKey = idempotencyKey,
    partyId = partyId,
    accountId = accountId,
    transactionId = transactionId,
    customerReference = customerReference,
    screeningType = ScreeningType.valueOf(screeningType),
    riskLevel = AmlRiskLevel.valueOf(riskLevel),
    status = AmlCaseStatus.valueOf(status),
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
    requestHash = requestHash,
    // A name this build does not know (written by a newer pod) reads as unknown, never as a system origin.
    origin = origin?.let { name -> CaseOrigin.entries.firstOrNull { it.name == name } },
    holdProposal = readHoldProposal(),
)
