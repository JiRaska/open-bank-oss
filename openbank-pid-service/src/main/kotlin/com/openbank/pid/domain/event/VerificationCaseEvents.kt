// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.domain.event

import com.openbank.libs.domain.event.DomainEvent
import com.openbank.pid.domain.model.CaseVerdict
import com.openbank.pid.domain.model.VerificationTrigger
import java.time.Instant
import java.util.UUID

/**
 * A four-eyes identity-verification case was opened (ADR-0072 §1). Emitted when pid /resolve
 * could not auto-decide and escalated to manual verification. Downstream (onboarding cockpit)
 * may use this to surface the case to operators. Carries no plaintext RČ.
 */
data class VerificationCaseOpenedEvent(
    override val aggregateId: UUID,
    val trigger: VerificationTrigger,
    val candidatePartyIds: List<UUID>,
    override val occurredAt: Instant,
) : DomainEvent(occurredAt) {
    override val aggregateType = "IdentityVerificationCase"
    override val eventType = "IdentityVerificationCaseOpened"
    override val version = 1L
}

/**
 * A four-eyes case was decided by two distinct approvers (ADR-0030). The [verdict] steers
 * subsequent /resolve calls for the same applicant; downstream consumers (e.g. customer-edge)
 * may use it to resume a pending onboarding.
 */
data class VerificationCaseDecidedEvent(
    override val aggregateId: UUID,
    val verdict: CaseVerdict,
    val linkPartyId: UUID?,
    val firstApprover: String,
    val secondApprover: String,
    override val occurredAt: Instant,
) : DomainEvent(occurredAt) {
    override val aggregateType = "IdentityVerificationCase"
    override val eventType = "IdentityVerificationCaseDecided"
    override val version = 1L
}
