// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.interest.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
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
 * WHY THIS TEST EXISTS AND WHY IT ASSERTS THE PARSED WIRE VALUE. audit-service resolves attribution
 * strongest-claim-first: a `sourceService` on the event body is recorded as
 * `AttributionSource.EVENT`, and without it the row falls back to the topic ladder and is recorded
 * as `AttributionSource.TOPIC` -- a value DERIVED from the topic name rather than STATED by this
 * service. `audit_entries` is append-only AT THE DATABASE and `source_service` is chain-hashed into
 * `record_hash`, so a row written without the field can never be corrected: the fix is forward-only
 * and every emitted event is one more permanently-inferred row. That is what makes this worth a
 * test rather than a convention.
 *
 * THE PROBE TRAP THIS TEST IS WRITTEN AROUND. A payload built by serialising a Kotlin data class
 * carries no literal `"sourceService"` anywhere in the source -- the wire key exists only at
 * runtime as a property name -- so `grep '"sourceService"'` over a module can return ZERO while the
 * field is demonstrably emitted (card-issuance-service and fx-service are exactly that shape). A
 * test that asserted on the source, or on a substring of the payload string, would inherit the same
 * blindness. So this parses the captured message and reads the resolved value.
 */
class KafkaInterestOutboxEventPublisherTest {

    @Test
    fun `publish stamps sourceService on the emitted payload`() {
        val emitter = mockk<MutinyEmitter<String>>()
        val captured = slot<Message<String>>()
        every { emitter.sendMessage(capture(captured)) } returns Uni.createFrom().voidItem()
        val mapper = ObjectMapper()

        val payload = """{"accrualId":"acc-1","accountId":"a-1","amount":1.23}"""

        // The producer's own payload does not carry the field -- this is the state the stamp fixes.
        assertThat(mapper.readTree(payload).has("sourceService")).isFalse()

        val entry = OutboxEntry(
            eventId = UUID.randomUUID(),
            aggregateId = UUID.randomUUID(),
            eventType = "interest.accrual.posted",
            payload = payload,
            status = OutboxStatus.PENDING,
            attemptCount = 0,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
            sentAt = null,
            lastError = null,
        )

        runBlocking { KafkaInterestOutboxEventPublisher(emitter, mapper).publish(entry) }

        assertThat(mapper.readTree(captured.captured.payload).get("sourceService").asText())
            .isEqualTo("interest-service")
    }

    @Test
    fun `the stamped value is this module's directory name minus the openbank prefix`() {
        // Pins the VALUE, not just its presence. The fleet's audit convention is the module
        // directory name without `openbank-`; a producer that drifts to a second spelling splits
        // its own history into two apparent producers at a merge date, and those rows cannot be
        // converged afterwards (append-only + chain-hashed).
        assertThat(KafkaInterestOutboxEventPublisher.SOURCE_SERVICE).isEqualTo("interest-service")
    }

    @Test
    fun `a payload that is not JSON is emitted unchanged rather than dropped`() {
        val emitter = mockk<MutinyEmitter<String>>()
        val captured = slot<Message<String>>()
        every { emitter.sendMessage(capture(captured)) } returns Uni.createFrom().voidItem()

        val entry = OutboxEntry(
            eventId = UUID.randomUUID(),
            aggregateId = UUID.randomUUID(),
            eventType = "interest.accrual.posted",
            payload = "not json at all",
            status = OutboxStatus.PENDING,
            attemptCount = 0,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
            sentAt = null,
            lastError = null,
        )

        runBlocking { KafkaInterestOutboxEventPublisher(emitter, ObjectMapper()).publish(entry) }

        // This is the money path: an unattributed row is a strictly better outcome than a
        // publish that throws and wedges the outbox dispatcher.
        assertThat(captured.captured.payload).isEqualTo("not json at all")
    }
}
