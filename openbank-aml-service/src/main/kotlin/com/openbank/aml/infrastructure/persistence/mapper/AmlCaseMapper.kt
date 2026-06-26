// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.aml.infrastructure.persistence.mapper

import com.openbank.aml.domain.model.AmlCase
import com.openbank.aml.domain.model.AmlCaseStatus
import com.openbank.aml.domain.model.AmlRiskLevel
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
    updatedAt = updatedAt
)
