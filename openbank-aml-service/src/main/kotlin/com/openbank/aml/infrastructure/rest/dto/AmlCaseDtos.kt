// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.infrastructure.rest.dto

import com.openbank.aml.application.port.`in`.CreateAmlCaseCommand
import com.openbank.aml.application.port.`in`.UpdateAmlDecisionCommand
import com.openbank.aml.domain.model.AmlCase
import com.openbank.aml.domain.model.AmlCaseStatus
import com.openbank.aml.domain.model.AmlRiskLevel
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
    fun toCommand(idempotencyKey: String) = CreateAmlCaseCommand(
        idempotencyKey = idempotencyKey,
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
    val decidedBy: String,
) {
    fun toCommand(caseId: UUID) = UpdateAmlDecisionCommand(
        caseId = caseId,
        targetStatus = AmlCaseStatus.valueOf(targetStatus),
        decisionReason = decisionReason,
        assignedAnalyst = assignedAnalyst,
        decidedBy = decidedBy,
    )
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
)

fun AmlCase.toResponse() = AmlCaseResponse(
    id = id,
    idempotencyKey = idempotencyKey,
    partyId = partyId,
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
)
