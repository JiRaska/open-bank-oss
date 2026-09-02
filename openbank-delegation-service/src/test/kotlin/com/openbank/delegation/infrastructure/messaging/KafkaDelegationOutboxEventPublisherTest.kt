// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.delegation.infrastructure.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.openbank.delegation.domain.event.DelegationRevoked
import com.openbank.delegation.domain.event.DelegationSpendReservationStateChanged
import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.smallrye.mutiny.Uni
import io.smallrye.reactive.messaging.MutinyEmitter
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.reactive.messaging.Message
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * `sourceService` must be ON THE WIRE, not merely mentioned in this module.
 *
 * WHY THIS TEST EXISTS. audit-service resolves attribution strongest-claim-first: a `sourceService`
 * on the event body is recorded as `AttributionSource.EVENT`, and without it the row falls back to
 * the topic ladder (`TopicAttribution`) and is recorded as `AttributionSource.TOPIC` -- a value
 * DERIVED from the topic name rather than STATED by this service. Both spell "delegation-service"
 * today, so nothing was ever WRONG; what was missing is the producer's own claim.
 *
 * That is worth a test because it cannot be repaired later. `audit_entries` is append-only AT THE
 * DATABASE (the V2 rules are `DO INSTEAD NOTHING`, so a normalising UPDATE touches zero rows and
 * REPORTS SUCCESS) and `source_service` is chain-hashed into `record_hash`. Every event emitted
 * without the field is one more permanently-inferred row; the fix is forward-only by construction.
 *
 * THE PROBE TRAP THIS TEST IS WRITTEN AROUND -- AND WHY THIS MODULE IS THE WORKED EXAMPLE.
 * `DelegationRepositoryImpl` serialises a Kotlin data class (`objectMapper.writeValueAsString(event)`),
 * so the wire keys exist ONLY at runtime as property names. There is no literal `"sourceService"`
 * for a grep to find, and there is no literal `"grantorPartyId"` either -- a quoted-string probe
 * over this module returns zero for every field it emits. A test that asserted on source text, or
 * on a substring of the payload, would inherit exactly that blindness. So this test serialises a
 * REAL event through the module's own idiom and asserts the PARSED wire value.
 */
class KafkaDelegationOutboxEventPublisherTest {

    private val mapper = ObjectMapper().registerModule(JavaTimeModule())

    private fun realEventPayload(): String = mapper.writeValueAsString(
        DelegationRevoked(
            aggregateId = UUID.randomUUID(),
            lifecycleRevision = 2,
            grantorPartyId = UUID.randomUUID(),
            granteePartyId = UUID.randomUUID(),
            resourceType = com.openbank.delegation.domain.model.DelegationResourceType.ACCOUNT,
            resourceId = UUID.randomUUID(),
            capabilities = emptySet(),
            reason = "revoked by grantor",
            occurredAt = Instant.now(),
        ),
    )

    private fun entry(
        payload: String,
        eventType: String = "DelegationRevoked",
        aggregateId: UUID = UUID.randomUUID(),
    ) = OutboxEntry(
        eventId = UUID.randomUUID(),
        aggregateId = aggregateId,
        eventType = eventType,
        payload = payload,
        status = OutboxStatus.PENDING,
        attemptCount = 0,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
        sentAt = null,
        lastError = null,
    )

    private fun statePayload(reservationId: UUID, revision: Long): String =
        """{"reservationId":"$reservationId","reservationVersion":$revision}"""

    @Test
    fun `a real serialised domain event carries no sourceService of its own`() {
        // The state the stamp exists to fix, asserted against the module's actual event type
        // rather than a hand-written fixture -- so adding the field to the data class later
        // makes this line fail loudly instead of leaving two competing sources of the value.
        assertThat(mapper.readTree(realEventPayload()).has("sourceService")).isFalse()
    }

    @Test
    fun `publish stamps sourceService on the emitted payload`() {
        val emitter = mockk<MutinyEmitter<String>>()
        val captured = slot<Message<String>>()
        every { emitter.sendMessage(capture(captured)) } returns Uni.createFrom().voidItem()

        runBlocking {
            KafkaDelegationOutboxEventPublisher(emitter, emitter, mapper).publish(entry(realEventPayload()))
        }

        assertThat(mapper.readTree(captured.captured.payload).get("sourceService").asText())
            .isEqualTo("delegation-service")
    }

    @Test
    fun `stamping preserves every field the producer already emitted`() {
        val emitter = mockk<MutinyEmitter<String>>()
        val captured = slot<Message<String>>()
        every { emitter.sendMessage(capture(captured)) } returns Uni.createFrom().voidItem()
        val payload = realEventPayload()

        runBlocking { KafkaDelegationOutboxEventPublisher(emitter, emitter, mapper).publish(entry(payload)) }

        val before = mapper.readTree(payload)
        val after = mapper.readTree(captured.captured.payload)
        before.fieldNames().forEach { field ->
            assertThat(after.get(field)).describedAs("field %s survived stamping", field).isEqualTo(before.get(field))
        }
    }

    @Test
    fun `the stamped value is this module's directory name minus the openbank prefix`() {
        assertThat(KafkaDelegationOutboxEventPublisher.SOURCE_SERVICE).isEqualTo("delegation-service")
    }

    @Test
    fun `a payload that is not JSON is emitted unchanged rather than dropped`() {
        val emitter = mockk<MutinyEmitter<String>>()
        val captured = slot<Message<String>>()
        every { emitter.sendMessage(capture(captured)) } returns Uni.createFrom().voidItem()

        runBlocking { KafkaDelegationOutboxEventPublisher(emitter, emitter, mapper).publish(entry("not json at all")) }

        // This is the money path: an unattributed row is a strictly better outcome than a publish
        // that throws and wedges the outbox dispatcher.
        assertThat(captured.captured.payload).isEqualTo("not json at all")
    }

    @Test
    fun `reservation snapshots route only to the compacted state channel`() {
        val lifecycleEmitter = mockk<MutinyEmitter<String>>()
        val stateEmitter = mockk<MutinyEmitter<String>>()
        every { stateEmitter.sendMessage(any()) } returns Uni.createFrom().voidItem()
        val reservationId = UUID.randomUUID()

        runBlocking {
            KafkaDelegationOutboxEventPublisher(lifecycleEmitter, stateEmitter, mapper).publish(
                entry(
                    statePayload(reservationId, 1L),
                    DelegationSpendReservationStateChanged.EVENT_TYPE,
                    reservationId,
                ),
            )
        }

        verify(exactly = 1) { stateEmitter.sendMessage(any()) }
        verify(exactly = 0) { lifecycleEmitter.sendMessage(any()) }
    }

    @Test
    fun `invalid reservation revision fails closed before either channel`() {
        val lifecycleEmitter = mockk<MutinyEmitter<String>>()
        val stateEmitter = mockk<MutinyEmitter<String>>()
        val reservationId = UUID.randomUUID()
        val publisher = KafkaDelegationOutboxEventPublisher(lifecycleEmitter, stateEmitter, mapper)

        org.assertj.core.api.Assertions.assertThatThrownBy {
            runBlocking {
                publisher.publish(
                    entry(
                        statePayload(reservationId, 3L),
                        DelegationSpendReservationStateChanged.EVENT_TYPE,
                        reservationId,
                    ),
                )
            }
        }.isInstanceOf(IllegalArgumentException::class.java)
        verify(exactly = 0) { stateEmitter.sendMessage(any()) }
        verify(exactly = 0) { lifecycleEmitter.sendMessage(any()) }
    }
}
