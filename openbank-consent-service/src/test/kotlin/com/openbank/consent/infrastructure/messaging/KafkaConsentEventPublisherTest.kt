// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.consent.infrastructure.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.consent.domain.event.ConsentExpired
import com.openbank.consent.domain.event.ConsentGranted
import com.openbank.consent.domain.event.ConsentRejected
import com.openbank.consent.domain.event.ConsentRevoked
import com.openbank.consent.domain.model.ConsentScope
import com.openbank.consent.domain.model.GranteeType
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.smallrye.mutiny.Uni
import io.smallrye.reactive.messaging.MutinyEmitter
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

class KafkaConsentEventPublisherTest {

    private val emitter = mockk<MutinyEmitter<String>>()
    private val objectMapper = ObjectMapper().findAndRegisterModules()
    private val publisher = KafkaConsentEventPublisher(emitter, objectMapper)

    private val aggregateId = UUID.randomUUID()
    private val partyId = UUID.randomUUID()

    @Test
    fun `publish ConsentGranted serializes the event to JSON and sends it`(): Unit = runBlocking {
        val sent = captureSend()
        val event = ConsentGranted(
            aggregateId = aggregateId,
            partyId = partyId,
            granteeId = "tpp-123",
            granteeType = GranteeType.TPP,
            scopes = setOf(ConsentScope.ACCOUNTS_READ),
            validTo = OffsetDateTime.now().plusDays(1),
        )

        publisher.publish(event)

        verify(exactly = 1) { emitter.send(any<String>()) }
        assertThat(sent.captured).contains("\"eventType\":\"ConsentGranted\"")
        assertThat(sent.captured).contains(aggregateId.toString())
    }

    @Test
    fun `publish ConsentRevoked sends the serialized event`(): Unit = runBlocking {
        val sent = captureSend()

        publisher.publish(ConsentRevoked(aggregateId, partyId, "tpp-123", "customer request"))

        assertThat(sent.captured).contains("\"eventType\":\"ConsentRevoked\"")
        assertThat(sent.captured).contains("customer request")
    }

    @Test
    fun `publish ConsentExpired sends the serialized event`(): Unit = runBlocking {
        val sent = captureSend()

        publisher.publish(ConsentExpired(aggregateId, partyId, "tpp-123"))

        assertThat(sent.captured).contains("\"eventType\":\"ConsentExpired\"")
    }

    @Test
    fun `publish ConsentRejected sends the serialized event`(): Unit = runBlocking {
        val sent = captureSend()

        publisher.publish(ConsentRejected(aggregateId, partyId, "tpp-123", "sca failed"))

        assertThat(sent.captured).contains("\"eventType\":\"ConsentRejected\"")
        assertThat(sent.captured).contains("sca failed")
    }

    private fun captureSend(): CapturingSlot<String> {
        val sent = slot<String>()
        every { emitter.send(capture(sent)) } returns Uni.createFrom().voidItem()
        return sent
    }
}
