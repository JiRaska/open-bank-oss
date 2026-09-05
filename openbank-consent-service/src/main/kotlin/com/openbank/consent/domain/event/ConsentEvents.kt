// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.consent.domain.event

import com.openbank.consent.domain.model.Consent
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
    /**
     * Producing service, read by `AuditConsumer.resolveSourceService` as the strongest
     * (EVENT-sourced) attribution (#3994/#5256). `eventType` ("ConsentGranted" etc., via
     * [DomainEvent]) is not touched — nothing outside consent-service reads any of this module's
     * event-type strings by name (verified fleet-wide), so there is no load-bearing-rename risk;
     * `sourceService` has no such consumer, so it is safe to add net-new. Value matches the
     * fleet's audit convention: the module directory without the `openbank-` prefix, the same
     * spelling `TopicAttribution` already maps `openbank.consent.events` to. This is a serialised
     * data class, not a hand-built map — `ConsentRepositoryImpl.outboxMessage` calls
     * `objectMapper.writeValueAsString(event)` directly, so the wire key exists only as this
     * Kotlin property name.
     */
    val sourceService: String = "consent-service",
) : DomainEvent(occurredAt) {
    override val aggregateType = "Consent"
    override val eventType = "ConsentGranted"
    override val version = 1L

    /**
     * Whether this consent grants access to the customer's banking data or money, as opposed to
     * being a pure data-processing preference (#8432).
     *
     * **Computed here, on the wire, because only this service can compute it correctly.**
     * [Consent.GDPR_ONLY_SCOPES] is the canonical set of preference scopes — marketing channels,
     * RUM telemetry, credit-offer processing — and it is already the set that decides SCA
     * exemption, guarded disjoint from [Consent.AISP_SCOPES] at class-load. A consumer that
     * re-derived this from `scopes` would hold a second copy of that list, and the next preference
     * scope added here would silently start reading as account access there. A Jackson-serialised
     * getter keeps the definition in one place and puts the answer on the wire; there is no
     * constructor parameter to set wrong at a call site, and nothing deserialises these events back
     * into the class (consumers read them as `JsonNode`).
     *
     * notification-service's `ConsentNotificationConsumer` reads this to decide whether the
     * customer is told: a third party gaining or losing access to their accounts is a security
     * event they must hear about, while their own marketing toggle is not.
     */
    val accountAccess: Boolean get() = scopes.any { it !in Consent.GDPR_ONLY_SCOPES }
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
    /** See [ConsentGranted.sourceService] (#3994/#5256). */
    val sourceService: String = "consent-service",
) : DomainEvent(occurredAt) {
    override val aggregateType = "Consent"
    override val eventType = "ConsentRevoked"
    override val version = 1L

    /** See [ConsentGranted.accountAccess] (#8432). */
    val accountAccess: Boolean get() = scopes.any { it !in Consent.GDPR_ONLY_SCOPES }
}

/**
 * A consent was replaced by a newer one for the same grantee and the same scopes (#6487).
 *
 * Carries `supersededBy` so a consumer can tell this apart from a withdrawal: access continues
 * under the named consent, and a journey keyed to the old id should follow it rather than stop.
 * That is the opposite of [ConsentRevoked], which means access ended.
 */
data class ConsentSuperseded(
    override val aggregateId: UUID,
    val partyId: UUID,
    val granteeId: String,
    val scopes: Set<ConsentScope>,
    val supersededBy: UUID,
    override val occurredAt: Instant,
    /** See [ConsentGranted.sourceService] (#3994/#5256). */
    val sourceService: String = "consent-service",
) : DomainEvent(occurredAt) {
    override val aggregateType = "Consent"
    override val eventType = "ConsentSuperseded"
    override val version = 1L
}

data class ConsentExpired(
    override val aggregateId: UUID,
    val partyId: UUID,
    val granteeId: String,
    override val occurredAt: Instant,
    /** See [ConsentGranted.sourceService] (#3994/#5256). */
    val sourceService: String = "consent-service",
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
    /** See [ConsentGranted.sourceService] (#3994/#5256). */
    val sourceService: String = "consent-service",
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
    /** See [ConsentGranted.sourceService] (#3994/#5256). */
    val sourceService: String = "consent-service",
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
    /** See [ConsentGranted.sourceService] (#3994/#5256). */
    val sourceService: String = "consent-service",
) : DomainEvent(occurredAt) {
    override val aggregateType = "Suppression"
    override val eventType = "SuppressionRevoked"
    override val version = 1L
}
