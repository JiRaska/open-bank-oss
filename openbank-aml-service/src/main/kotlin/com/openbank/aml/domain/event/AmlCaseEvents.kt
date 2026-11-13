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
    /**
     * `null` while the case has no established party ([AmlCase.establishedPartyId]). An event never
     * carries a stand-in: what is not a party is not published as one, so no consumer can act on it.
     */
    val partyId: UUID?,
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
    /** As on [AmlCaseCreatedEvent]: `null` while the case has no established party. */
    val partyId: UUID?,
    val previousStatus: AmlCaseStatus,
    val newStatus: AmlCaseStatus,
    val decisionReason: String?,
    val assignedAnalyst: String?,
    val decidedBy: String?,
    val occurredAt: Instant,
    // Additive (#4403): what a decision applies to. A consumer that acts on one kind of case, such
    // as a payment rail releasing a fraud hold, cannot otherwise tell which case or payment it is.
    val transactionId: UUID? = null,
    val alertCode: String? = null,
    // Additive (#4403): binds a decision to the case a rail opened, since alertCode and
    // transactionId are free text any case creator may set.
    val idempotencyKey: String? = null,
)

fun AmlCase.toCreatedEvent(now: Instant) = AmlCaseCreatedEvent(
    caseId = id,
    idempotencyKey = idempotencyKey,
    partyId = establishedPartyId,
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
