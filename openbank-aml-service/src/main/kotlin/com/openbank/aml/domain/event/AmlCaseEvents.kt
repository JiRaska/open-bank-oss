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
    // Additive: false while the owning party is not established. `partyId` is then the nil UUID
    // (AmlCase.UNRESOLVED_PARTY_WIRE_ID) or, on rows from before #3413, the account id; neither is a party.
    val partyResolved: Boolean = true,
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
    // Additive (#4403): what a decision applies to. A consumer that acts on one kind of case, such
    // as a payment rail releasing a fraud hold, cannot otherwise tell which case or payment it is.
    val transactionId: UUID? = null,
    val alertCode: String? = null,
    // Additive (#4403): binds a decision to the case a rail opened, since alertCode and
    // transactionId are free text any case creator may set.
    val idempotencyKey: String? = null,
    // Additive: see AmlCaseCreatedEvent.partyResolved.
    val partyResolved: Boolean = true,
)

fun AmlCase.toCreatedEvent(now: Instant) = AmlCaseCreatedEvent(
    caseId = id,
    idempotencyKey = idempotencyKey,
    partyId = partyId ?: AmlCase.UNRESOLVED_PARTY_WIRE_ID,
    accountId = accountId,
    transactionId = transactionId,
    customerReference = customerReference,
    screeningType = screeningType,
    riskLevel = riskLevel,
    status = status,
    alertCode = alertCode,
    matchedEntity = matchedEntity,
    occurredAt = now,
    partyResolved = partyResolved,
)
