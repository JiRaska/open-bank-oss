// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.domain.event

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.delegation.domain.model.DelegationCapability
import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.DelegationResourceType
import com.openbank.delegation.domain.model.SpendReservation
import com.openbank.libs.domain.money.Money
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * The wire shape of [DelegationFirstUsed], asserted off REAL serialised JSON.
 *
 * The payload is a serialised data class, so every key exists only as a Kotlin property name at
 * runtime and a grep for a quoted `"grantorPartyId"` finds nothing in this module. Asserting off
 * the property instead of the JSON would test the compiler, not the contract that
 * `DelegationNotificationConsumer` reads with `node.path("grantorPartyId")`.
 */
class DelegationFirstUsedTest {

    private val mapper: ObjectMapper = jacksonObjectMapper().findAndRegisterModules()
    private val grantor = UUID.randomUUID()
    private val grantee = UUID.randomUUID()
    private val resourceId = UUID.randomUUID()

    private fun grant(id: UUID = UUID.randomUUID()): DelegationGrant {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        return DelegationGrant(
            id = id,
            grantorPartyId = grantor,
            granteePartyId = grantee,
            resourceType = DelegationResourceType.ACCOUNT,
            resourceId = resourceId,
            capabilities = setOf(DelegationCapability.ACCOUNT_INITIATE_PAYMENT),
            dailyLimit = Money.of("5000.00", "CZK"),
            validFrom = now.minusDays(1),
            validTo = now.plusDays(30),
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun reservation(grantId: UUID, createdAt: OffsetDateTime) = SpendReservation(
        grantId = grantId,
        amount = Money.of("125.50", "CZK"),
        idempotencyKey = "k-1",
        createdAt = createdAt,
    )

    @Test
    fun `carries the grant as aggregate and both parties on the wire`() {
        val g = grant()
        val r = reservation(g.id, OffsetDateTime.now(ZoneOffset.UTC))

        val json = mapper.readTree(mapper.writeValueAsString(DelegationFirstUsed.from(r, g)))

        assertThat(json.get("eventType").asText()).isEqualTo("DelegationFirstUsed")
        assertThat(json.get("aggregateType").asText()).isEqualTo("DelegationGrant")
        assertThat(json.get("aggregateId").asText()).isEqualTo(g.id.toString())
        assertThat(json.get("grantorPartyId").asText()).isEqualTo(grantor.toString())
        assertThat(json.get("granteePartyId").asText()).isEqualTo(grantee.toString())
        assertThat(json.get("resourceType").asText()).isEqualTo("ACCOUNT")
        assertThat(json.get("resourceId").asText()).isEqualTo(resourceId.toString())
        assertThat(json.get("reservationId").asText()).isEqualTo(r.id.toString())
        assertThat(json.get("amount").get("currency").asText()).isEqualTo("CZK")
        // A flat currency string, not the domain CurrencyCode's {"code":"CZK"} — the shape every
        // consumer of this topic already reads (EventMoney's own KDoc).
        assertThat(json.get("amount").get("currency").isTextual).isTrue()
        assertThat(json.path("lifecycleRevision").isMissingNode).isTrue()
        assertThat(json.path("sourceService").isMissingNode).isTrue()
    }

    /**
     * Recency, not non-nullity. `Instant.EPOCH` is non-null and would satisfy `isNotNull()`, and an
     * audit row carrying 1970 cannot be repaired later — `audit_entries` is append-only at the
     * database and `source_service` is chain-hashed into `record_hash`.
     */
    @Test
    fun `occurredAt is the reservation's own creation instant, never a sentinel`() {
        val before = Instant.now()
        val g = grant()
        val createdAt = OffsetDateTime.now(ZoneOffset.UTC)

        val event = DelegationFirstUsed.from(reservation(g.id, createdAt), g)

        assertThat(event.occurredAt).isEqualTo(createdAt.toInstant())
        assertThat(event.occurredAt).isBetween(before.minusSeconds(SKEW), Instant.now().plusSeconds(SKEW))
        assertThat(event.occurredAt).isNotEqualTo(Instant.EPOCH)
    }

    @Test
    fun `refuses a reservation that belongs to a different grant`() {
        val g = grant()
        val foreign = reservation(UUID.randomUUID(), OffsetDateTime.now(ZoneOffset.UTC))

        assertThatThrownBy { DelegationFirstUsed.from(foreign, g) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private companion object {
        const val SKEW = 120L
    }
}
