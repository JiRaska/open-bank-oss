// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.consent.domain.event

import com.openbank.consent.domain.model.ConsentScope
import com.openbank.consent.domain.model.GranteeType
import com.openbank.consent.domain.model.SuppressionReason
import com.openbank.consent.domain.model.SuppressionScope
import com.openbank.libs.domain.event.DomainEvent
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

data class ConsentGranted(
    override val aggregateId: UUID,
    val partyId: UUID,
    val granteeId: String,
    val granteeType: GranteeType,
    val scopes: Set<ConsentScope>,
    val validTo: OffsetDateTime,
    override val occurredAt: Instant,
) : DomainEvent(occurredAt) {
    override val aggregateType = "Consent"
    override val eventType = "ConsentGranted"
    override val version = 1L
}

/**
 * `scopes` mirrors [ConsentGranted]: a consumer deciding whether a revocation concerns it needs the
 * same granularity it had when the consent was granted. campaign-service's ConsentEventConsumer
 * filters on `scopes` for a `MARKETING_COMMS*` prefix (ADR-0200 D2) — without the field its filter
 * matched nothing, so a revocation never terminated a running journey. Additive and therefore
 * backward compatible: existing consumers ignore the new field.
 */
data class ConsentRevoked(
    override val aggregateId: UUID,
    val partyId: UUID,
    val granteeId: String,
    val scopes: Set<ConsentScope>,
    val reason: String,
    override val occurredAt: Instant,
) : DomainEvent(occurredAt) {
    override val aggregateType = "Consent"
    override val eventType = "ConsentRevoked"
    override val version = 1L
}

data class ConsentExpired(
    override val aggregateId: UUID,
    val partyId: UUID,
    val granteeId: String,
    override val occurredAt: Instant,
) : DomainEvent(occurredAt) {
    override val aggregateType = "Consent"
    override val eventType = "ConsentExpired"
    override val version = 1L
}

data class ConsentRejected(
    override val aggregateId: UUID,
    val partyId: UUID,
    val granteeId: String,
    val reason: String,
    override val occurredAt: Instant,
) : DomainEvent(occurredAt) {
    override val aggregateType = "Consent"
    override val eventType = "ConsentRejected"
    override val version = 1L
}

/**
 * ADR-0219 D3: a suppression entry was created — the contact-policy gate's near-real-time
 * invalidation signal, so a "do not contact" takes effect at event speed, not at the next
 * materialisation interval (the same rule ADR-0219 sets for consent revocation).
 */
data class SuppressionCreated(
    override val aggregateId: UUID,
    val partyId: UUID,
    val scope: SuppressionScope,
    val value: String?,
    val reason: SuppressionReason,
    val source: String,
    override val occurredAt: Instant,
) : DomainEvent(occurredAt) {
    override val aggregateType = "Suppression"
    override val eventType = "SuppressionCreated"
    override val version = 1L
}

/** ADR-0219 D3: a suppression entry was revoked — contact resumes under the remaining rules. */
data class SuppressionRevoked(
    override val aggregateId: UUID,
    val partyId: UUID,
    val scope: SuppressionScope,
    val value: String?,
    override val occurredAt: Instant,
) : DomainEvent(occurredAt) {
    override val aggregateType = "Suppression"
    override val eventType = "SuppressionRevoked"
    override val version = 1L
}
