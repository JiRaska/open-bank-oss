// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.audit

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.smallrye.reactive.messaging.kafka.Record
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.reactive.messaging.Emitter
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture

/**
 * [EdgeAuditPublisher] is the only place that records the real customer identity behind a
 * money-moving or identity-changing action (GDPR Art. 30 / DORA Art. 17) — upstream services
 * see only the M2M token, so a gap here is unreconstructable after the fact. It is also
 * "best-effort BY DESIGN": a broker failure must degrade to a log line, never propagate.
 */
class EdgeAuditPublisherTest {

    private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-07-07T10:00:00Z"), ZoneOffset.UTC)
    private val objectMapper = ObjectMapper()

    @Suppress("UNCHECKED_CAST")
    private fun mockEmitter(): Emitter<Record<String, String>> = mockk<Emitter<Record<String, String>>> {
        every { send(any()) } returns CompletableFuture.completedFuture(null)
    }

    @Test
    fun `emits a well-formed audit record with the customer as actor and aggregate`() {
        val emitter = mockEmitter()
        val recordSlot = slot<Record<String, String>>()
        every { emitter.send(capture(recordSlot)) } returns CompletableFuture.completedFuture(null)
        val publisher = EdgeAuditPublisher(objectMapper, emitter, fixedClock)

        publisher.emit(
            eventType = "customer.payment.initiated",
            partyId = "party-123",
            operation = "payment.sepa.created",
            result = "SUCCESS",
            resourceId = "payment-456",
            details = mapOf("amount" to "100.00", "currency" to "EUR"),
        )

        assertThat(recordSlot.captured.key()).isEqualTo("party-123")
        val payload = objectMapper.readTree(recordSlot.captured.value())
        assertThat(payload.get("eventType").asText()).isEqualTo("customer.payment.initiated")
        assertThat(payload.get("aggregateType").asText()).isEqualTo("CUSTOMER_ACTION")
        assertThat(payload.get("partyId").asText()).isEqualTo("party-123")
        assertThat(payload.get("actorId").asText()).isEqualTo("party-123")
        assertThat(payload.get("actorType").asText()).isEqualTo("CUSTOMER")
        assertThat(payload.get("operation").asText()).isEqualTo("payment.sepa.created")
        assertThat(payload.get("result").asText()).isEqualTo("SUCCESS")
        assertThat(payload.get("resourceId").asText()).isEqualTo("payment-456")
        assertThat(payload.get("sourceService").asText()).isEqualTo("customer-edge")
        assertThat(payload.get("occurredAt").asText()).isEqualTo("2026-07-07T10:00:00Z")
        assertThat(payload.get("amount").asText()).isEqualTo("100.00")
        assertThat(payload.get("currency").asText()).isEqualTo("EUR")
    }

    @Test
    fun `omits resourceId from the payload when absent instead of writing a null`() {
        val emitter = mockEmitter()
        val recordSlot = slot<Record<String, String>>()
        every { emitter.send(capture(recordSlot)) } returns CompletableFuture.completedFuture(null)
        val publisher = EdgeAuditPublisher(objectMapper, emitter, fixedClock)

        publisher.emit(eventType = "customer.login", partyId = "party-1", operation = "auth.login", result = "SUCCESS")

        val payload = objectMapper.readTree(recordSlot.captured.value())
        assertThat(payload.has("resourceId")).isFalse()
    }

    @Test
    fun `null detail values are dropped rather than serialised as null`() {
        val emitter = mockEmitter()
        val recordSlot = slot<Record<String, String>>()
        every { emitter.send(capture(recordSlot)) } returns CompletableFuture.completedFuture(null)
        val publisher = EdgeAuditPublisher(objectMapper, emitter, fixedClock)

        publisher.emit(
            eventType = "customer.profile.updated",
            partyId = "party-1",
            operation = "party.updated",
            result = "SUCCESS",
            details = mapOf("email" to "new@example.com", "phone" to null),
        )

        val payload = objectMapper.readTree(recordSlot.captured.value())
        assertThat(payload.get("email").asText()).isEqualTo("new@example.com")
        assertThat(payload.has("phone")).isFalse()
    }

    @Test
    fun `never throws even when the emitter itself throws synchronously`() {
        val emitter = mockk<Emitter<Record<String, String>>> {
            every { send(any()) } throws IllegalStateException("channel closed")
        }
        val publisher = EdgeAuditPublisher(objectMapper, emitter, fixedClock)

        publisher.emit(
            eventType = "customer.payment.initiated",
            partyId = "party-1",
            operation = "x",
            result = "FAILURE",
        )

        verify { emitter.send(any()) }
    }

    @Test
    fun `does not throw when the emitter's returned future completes exceptionally`() {
        val emitter = mockk<Emitter<Record<String, String>>> {
            every { send(any()) } returns CompletableFuture<Void>().apply {
                completeExceptionally(RuntimeException("broker unavailable"))
            }
        }
        val publisher = EdgeAuditPublisher(objectMapper, emitter, fixedClock)

        publisher.emit(
            eventType = "customer.payment.initiated",
            partyId = "party-1",
            operation = "x",
            result = "FAILURE",
        )
    }
}
