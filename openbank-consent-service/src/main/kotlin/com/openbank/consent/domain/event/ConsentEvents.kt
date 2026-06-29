// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.consent.domain.event

import com.openbank.consent.domain.model.ConsentScope
import com.openbank.consent.domain.model.GranteeType
import com.openbank.libs.domain.event.DomainEvent
import java.time.OffsetDateTime
import java.util.UUID

data class ConsentGranted(
    override val aggregateId: UUID,
    val partyId: UUID,
    val granteeId: String,
    val granteeType: GranteeType,
    val scopes: Set<ConsentScope>,
    val validTo: OffsetDateTime,
) : DomainEvent() {
    override val aggregateType = "Consent"
    override val eventType = "ConsentGranted"
    override val version = 1L
}

data class ConsentRevoked(
    override val aggregateId: UUID,
    val partyId: UUID,
    val granteeId: String,
    val reason: String,
) : DomainEvent() {
    override val aggregateType = "Consent"
    override val eventType = "ConsentRevoked"
    override val version = 1L
}

data class ConsentExpired(override val aggregateId: UUID, val partyId: UUID, val granteeId: String) : DomainEvent() {
    override val aggregateType = "Consent"
    override val eventType = "ConsentExpired"
    override val version = 1L
}

data class ConsentRejected(
    override val aggregateId: UUID,
    val partyId: UUID,
    val granteeId: String,
    val reason: String,
) : DomainEvent() {
    override val aggregateType = "Consent"
    override val eventType = "ConsentRejected"
    override val version = 1L
}
