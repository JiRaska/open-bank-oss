// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.domain.event

import com.openbank.delegation.domain.model.DelegationRecertificationAudience
import com.openbank.libs.domain.event.DomainEvent
import java.time.Instant
import java.util.UUID

/** A review is due; delivery is a notification concern, never a lifecycle transition. */
data class DelegationRecertificationDue(
    override val aggregateId: UUID,
    val recertificationId: UUID,
    val grantorPartyId: UUID,
    val expectedLifecycleRevision: Long,
    val audience: DelegationRecertificationAudience,
    override val occurredAt: Instant,
) : DomainEvent(occurredAt) {
    override val aggregateType = "DelegationRecertificationCycle"
    override val eventType = "DelegationRecertificationDue"
    override val version = 1L
}

/** The grantor reviewed the current authority; it does not alter the grant or its revision. */
data class DelegationRecertificationConfirmed(
    override val aggregateId: UUID,
    val recertificationId: UUID,
    val grantorPartyId: UUID,
    val expectedLifecycleRevision: Long,
    override val occurredAt: Instant,
) : DomainEvent(occurredAt) {
    override val aggregateType = "DelegationRecertificationCycle"
    override val eventType = "DelegationRecertificationConfirmed"
    override val version = 1L
}
