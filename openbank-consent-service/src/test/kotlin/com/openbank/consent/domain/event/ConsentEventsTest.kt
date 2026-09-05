// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.consent.domain.event

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.consent.domain.model.Consent
import com.openbank.consent.domain.model.ConsentScope
import com.openbank.consent.domain.model.GranteeType
import com.openbank.consent.domain.model.SuppressionReason
import com.openbank.consent.domain.model.SuppressionScope
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Serialization round-trip for consent-service's Kafka events (issue #3994/#5256, fleet
 * follow-up to #5255's domestic-payment fix and the other nine slices: #5267, #5329, #5336,
 * #5337, #5343, #5344, #5349, #5351, plus #5369's audit-service subscription wiring).
 *
 * `sourceService` is the strongest (EVENT-sourced) attribution `AuditConsumer` reads
 * (`node.textOrNull("sourceService")`) — before this field none of these six event types carried
 * such a key and every audit row for consent-service fell back to
 * `EventAttribution.TopicAttribution`'s `openbank.consent.events` -> `consent-service` entry,
 * correct but only TOPIC-sourced, not the producer's own claim. Audit-service subscribes to that
 * topic today (`openbank-audit-service/src/main/resources/application.yaml`'s consumed-topics
 * list), so this is a live attribution upgrade, not a forward-looking one.
 *
 * `eventType` (`ConsentGranted` / `ConsentRevoked` / `ConsentExpired` / `ConsentRejected` /
 * `SuppressionCreated` / `SuppressionRevoked`, all via `DomainEvent`) is NOT touched — nothing
 * outside consent-service reads any of these strings by name (verified fleet-wide), so there is
 * no load-bearing-rename risk here, unlike account-service's #5267 fix.
 *
 * These are serialised data classes, not hand-built maps — `ConsentRepositoryImpl.outboxMessage`
 * calls `objectMapper.writeValueAsString(event)` directly, so the wire key exists only as the
 * Kotlin property name `sourceService`.
 */
class ConsentEventsTest {

    private val objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())

    private val now = Instant.parse("2026-08-16T10:00:00Z")
    private val validTo = OffsetDateTime.parse("2027-08-16T10:00:00Z")

    @Test
    fun `ConsentGranted carries eventType and sourceService for AuditConsumer attribution`() {
        val event = ConsentGranted(
            aggregateId = UUID.randomUUID(),
            partyId = UUID.randomUUID(),
            granteeId = "tpp-1",
            granteeType = GranteeType.TPP,
            scopes = setOf(ConsentScope.ACCOUNTS_READ),
            validTo = validTo,
            occurredAt = now,
        )

        assertThat(event.eventType).isEqualTo("ConsentGranted")
        assertThat(event.sourceService).isEqualTo("consent-service")

        val node = objectMapper.readTree(objectMapper.writeValueAsString(event))
        assertThat(node.get("eventType").asText()).isEqualTo("ConsentGranted")
        assertThat(node.get("sourceService").asText()).isEqualTo("consent-service")
    }

    @Test
    fun `ConsentRevoked carries eventType and sourceService for AuditConsumer attribution`() {
        val event = ConsentRevoked(
            aggregateId = UUID.randomUUID(),
            partyId = UUID.randomUUID(),
            granteeId = "tpp-1",
            scopes = setOf(ConsentScope.ACCOUNTS_READ),
            reason = "customer requested",
            occurredAt = now,
        )

        val node = objectMapper.readTree(objectMapper.writeValueAsString(event))
        assertThat(node.get("eventType").asText()).isEqualTo("ConsentRevoked")
        assertThat(node.get("sourceService").asText()).isEqualTo("consent-service")
    }

    @Test
    fun `ConsentExpired carries eventType and sourceService for AuditConsumer attribution`() {
        val event = ConsentExpired(
            aggregateId = UUID.randomUUID(),
            partyId = UUID.randomUUID(),
            granteeId = "tpp-1",
            occurredAt = now,
        )

        val node = objectMapper.readTree(objectMapper.writeValueAsString(event))
        assertThat(node.get("eventType").asText()).isEqualTo("ConsentExpired")
        assertThat(node.get("sourceService").asText()).isEqualTo("consent-service")
    }

    @Test
    fun `ConsentRejected carries eventType and sourceService for AuditConsumer attribution`() {
        val event = ConsentRejected(
            aggregateId = UUID.randomUUID(),
            partyId = UUID.randomUUID(),
            granteeId = "tpp-1",
            reason = "SCA declined",
            occurredAt = now,
        )

        val node = objectMapper.readTree(objectMapper.writeValueAsString(event))
        assertThat(node.get("eventType").asText()).isEqualTo("ConsentRejected")
        assertThat(node.get("sourceService").asText()).isEqualTo("consent-service")
    }

    @Test
    fun `SuppressionCreated carries eventType and sourceService for AuditConsumer attribution`() {
        val event = SuppressionCreated(
            aggregateId = UUID.randomUUID(),
            partyId = UUID.randomUUID(),
            scope = SuppressionScope.ALL,
            value = null,
            reason = SuppressionReason.CUSTOMER_OPTOUT,
            source = "customer-portal",
            occurredAt = now,
        )

        val node = objectMapper.readTree(objectMapper.writeValueAsString(event))
        assertThat(node.get("eventType").asText()).isEqualTo("SuppressionCreated")
        assertThat(node.get("sourceService").asText()).isEqualTo("consent-service")
    }

    @Test
    fun `SuppressionRevoked carries eventType and sourceService for AuditConsumer attribution`() {
        val event = SuppressionRevoked(
            aggregateId = UUID.randomUUID(),
            partyId = UUID.randomUUID(),
            scope = SuppressionScope.ALL,
            value = null,
            occurredAt = now,
        )

        val node = objectMapper.readTree(objectMapper.writeValueAsString(event))
        assertThat(node.get("eventType").asText()).isEqualTo("SuppressionRevoked")
        assertThat(node.get("sourceService").asText()).isEqualTo("consent-service")
    }

    // ─── accountAccess (#8432) ──────────────────────────────────────────────────

    private fun granted(scopes: Set<ConsentScope>) = ConsentGranted(
        aggregateId = UUID.randomUUID(),
        partyId = UUID.randomUUID(),
        granteeId = "tpp-1",
        granteeType = GranteeType.TPP,
        scopes = scopes,
        validTo = OffsetDateTime.parse("2026-11-16T10:00:00Z"),
        occurredAt = now,
    )

    private fun revoked(scopes: Set<ConsentScope>) = ConsentRevoked(
        aggregateId = UUID.randomUUID(),
        partyId = UUID.randomUUID(),
        granteeId = "tpp-1",
        scopes = scopes,
        reason = "customer request",
        occurredAt = now,
    )

    /**
     * The flag is a computed getter, so it can only reach the wire if Jackson serialises it. If it
     * ever stops appearing, notification-service fails closed and silently stops telling customers
     * a third party gained access to their accounts — a defect no consent-service test would
     * otherwise see.
     */
    @Test
    fun `accountAccess rides the wire on both events`() {
        listOf(
            objectMapper.readTree(objectMapper.writeValueAsString(granted(setOf(ConsentScope.ACCOUNTS_READ)))),
            objectMapper.readTree(objectMapper.writeValueAsString(revoked(setOf(ConsentScope.ACCOUNTS_READ)))),
        ).forEach { node ->
            assertThat(node.has("accountAccess")).isTrue()
            assertThat(node.get("accountAccess").isBoolean).isTrue()
            assertThat(node.get("accountAccess").booleanValue()).isTrue()
        }
    }

    /**
     * Derived from `Consent.GDPR_ONLY_SCOPES` itself rather than a list repeated here: the point of
     * computing the flag in this service is that adding a preference scope there cannot turn into a
     * security push downstream, and a hand-copied list in the test would not prove that.
     */
    @Test
    fun `every GDPR-only scope is a preference, every other scope is account access`() {
        Consent.GDPR_ONLY_SCOPES.forEach { scope ->
            assertThat(granted(setOf(scope)).accountAccess)
                .describedAs("%s is a data-processing preference and must not notify", scope)
                .isFalse()
            assertThat(revoked(setOf(scope)).accountAccess).isFalse()
        }
        (ConsentScope.entries.toSet() - Consent.GDPR_ONLY_SCOPES).forEach { scope ->
            assertThat(granted(setOf(scope)).accountAccess)
                .describedAs("%s grants access to banking data or money and must notify", scope)
                .isTrue()
        }
    }

    /** A mixed set is rejected at creation (ADR-0205 D1), but if one ever formed it is access. */
    @Test
    fun `any non-preference scope makes the whole consent account access`() {
        val mixed = setOf(ConsentScope.MARKETING_COMMS_EMAIL, ConsentScope.ACCOUNTS_READ)
        assertThat(granted(mixed).accountAccess).isTrue()
    }
}
