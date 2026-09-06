// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.domain.event

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * ADR-0249 D4 (issue #5728) — what these events actually put ON THE WIRE.
 *
 * Asserted off serialised JSON, not off the Kotlin properties. These are serialised data classes,
 * so `sourceService` exists on the wire only as a property name: a grep for a quoted
 * `"sourceService"` in this service returns nothing, and a test reading `event.sourceService`
 * would pass even if Jackson never emitted the field (the card-issuance twin,
 * `CardEventsTest`, makes the same point for the same reason).
 *
 * `sourceService` is what `AuditConsumer.resolveSourceService` reads as the strongest
 * (EVENT-sourced) attribution. Omitting it is silent: the consumer falls back to `?: "unknown"`
 * with no error, which is how the fleet reached 76% unattributed rows (#3994/#5256).
 */
class SpendReservationEventsTest {

    // WRITE_DATES_AS_TIMESTAMPS disabled to match the CDI ObjectMapper the outbox actually
    // serialises with (Quarkus disables it by default). A bare ObjectMapper would render
    // `occurredAt` as an epoch decimal here and as ISO-8601 in production — the test would then
    // be describing a wire format nothing emits.
    private val mapper = ObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    private val grant: UUID = UUID.randomUUID()
    private val reservation: UUID = UUID.randomUUID()
    private val grantor: UUID = UUID.randomUUID()
    private val grantee: UUID = UUID.randomUUID()
    private val at: Instant = Instant.parse("2026-08-20T09:15:00Z")
    private val settled: OffsetDateTime = OffsetDateTime.ofInstant(at, ZoneOffset.UTC)
    private val money = EventMoney(BigDecimal("1250.00"), "CZK")

    @Test
    fun `SpendReserved carries sourceService, the grant as aggregate and the reservation as a field`() {
        val node = mapper.readTree(
            mapper.writeValueAsString(
                SpendReserved(grant, reservation, grantor, grantee, money, "payment-7", at),
            ),
        )

        assertThat(node.get("sourceService").asText()).isEqualTo("delegation-service")
        assertThat(node.get("eventType").asText()).isEqualTo("SpendReserved")
        assertThat(node.get("aggregateType").asText()).isEqualTo("DelegationGrant")
        assertThat(node.get("aggregateId").asText()).isEqualTo(grant.toString())
        assertThat(node.get("reservationId").asText()).isEqualTo(reservation.toString())
        assertThat(node.get("grantorPartyId").asText()).isEqualTo(grantor.toString())
        assertThat(node.get("granteePartyId").asText()).isEqualTo(grantee.toString())
        assertThat(node.get("idempotencyKey").asText()).isEqualTo("payment-7")
        // Flat `currency: String`, not the domain CurrencyCode — Jackson renders that as
        // {"code":"CZK"} while every consumer of this topic reads the field as text.
        assertThat(node.get("amount").get("currency").asText()).isEqualTo("CZK")
        assertThat(node.get("amount").get("amount").decimalValue()).isEqualByComparingTo(BigDecimal("1250.00"))
    }

    @Test
    fun `SpendConfirmed carries sourceService on the wire`() {
        val node = mapper.readTree(
            mapper.writeValueAsString(
                SpendConfirmed(grant, reservation, grantor, grantee, money, settled, at),
            ),
        )

        assertThat(node.get("sourceService").asText()).isEqualTo("delegation-service")
        assertThat(node.get("eventType").asText()).isEqualTo("SpendConfirmed")
        assertThat(node.get("reservationId").asText()).isEqualTo(reservation.toString())
        assertThat(node.hasNonNull("settledAt")).isTrue()
    }

    @Test
    fun `SpendReleased carries sourceService on the wire`() {
        val node = mapper.readTree(
            mapper.writeValueAsString(
                SpendReleased(grant, reservation, grantor, grantee, money, settled, at),
            ),
        )

        assertThat(node.get("sourceService").asText()).isEqualTo("delegation-service")
        assertThat(node.get("eventType").asText()).isEqualTo("SpendReleased")
        assertThat(node.get("reservationId").asText()).isEqualTo(reservation.toString())
    }

    @Test
    fun `occurredAt has no default — a caller must pass a clock reading`() {
        // The Instant.EPOCH shape (#3874/#3883): a defaulted business time is a value every
        // isNotNull() assertion agrees with and no reader can distinguish from a real one. There
        // is no no-arg construction to test here BECAUSE the parameter is required, which is the
        // property under test; this asserts the value that is passed survives serialisation.
        val node = mapper.readTree(
            mapper.writeValueAsString(
                SpendReserved(grant, reservation, grantor, grantee, money, "k", at),
            ),
        )

        assertThat(Instant.parse(node.get("occurredAt").asText())).isEqualTo(at)
        assertThat(Instant.parse(node.get("occurredAt").asText())).isAfter(Instant.EPOCH)
    }
}
