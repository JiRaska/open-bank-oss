// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.domain.event

import com.openbank.aml.domain.model.AmlCase
import com.openbank.aml.domain.model.AmlCaseStatus
import com.openbank.aml.domain.model.AmlRiskLevel
import com.openbank.aml.domain.model.ScreeningType
import java.time.Instant
import java.util.UUID

data class AmlCaseCreatedEvent(
    val caseId: UUID,
    val idempotencyKey: String,
    val partyId: UUID,
    val accountId: UUID?,
    val transactionId: UUID?,
    val customerReference: String,
    val screeningType: ScreeningType,
    val riskLevel: AmlRiskLevel,
    val status: AmlCaseStatus,
    val alertCode: String,
    val matchedEntity: String?,
    val occurredAt: Instant,
)

data class AmlCaseStatusChangedEvent(
    val caseId: UUID,
    val partyId: UUID,
    val previousStatus: AmlCaseStatus,
    val newStatus: AmlCaseStatus,
    val decisionReason: String?,
    val assignedAnalyst: String?,
    val decidedBy: String?,
    val occurredAt: Instant,
)

fun AmlCase.toCreatedEvent(now: Instant) = AmlCaseCreatedEvent(
    caseId = id,
    idempotencyKey = idempotencyKey,
    partyId = partyId,
    accountId = accountId,
    transactionId = transactionId,
    customerReference = customerReference,
    screeningType = screeningType,
    riskLevel = riskLevel,
    status = status,
    alertCode = alertCode,
    matchedEntity = matchedEntity,
    occurredAt = now,
)
