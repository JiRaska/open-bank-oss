// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.campaign.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.campaign.application.port.out.NotificationSendRequest
import com.openbank.campaign.domain.model.Channel
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.reactive.messaging.Emitter
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CompletableFuture

class KafkaNotificationSendAdapterTest {
    private val emitter = mockk<Emitter<String>>()
    private val mapper = ObjectMapper()

    @Test
    fun `push interaction reference is transport metadata not a template variable`(): Unit = runBlocking {
        val payload = slot<String>()
        every { emitter.send(capture(payload)) } returns CompletableFuture.completedFuture(null)
        val interactionRef = UUID.randomUUID()

        KafkaNotificationSendAdapter(emitter, mapper).requestSend(
            NotificationSendRequest(
                partyId = UUID.randomUUID(),
                channel = Channel.PUSH,
                template = "MARKETING_PRODUCT_OFFER_PUSH",
                recipient = "party-id",
                variables = mapOf("offerTitle" to "Savings"),
                correlationId = UUID.randomUUID(),
                deepLink = "openbank://savings",
                interactionRef = interactionRef,
            ),
        )

        val node = mapper.readTree(payload.captured)
        assertThat(node.path("interactionRef").asText()).isEqualTo(interactionRef.toString())
        assertThat(node.path("variables").has("interactionRef")).isFalse()
    }

    @Test
    fun `email handoff does not serialize an interaction reference`(): Unit = runBlocking {
        val payload = slot<String>()
        every { emitter.send(capture(payload)) } returns CompletableFuture.completedFuture(null)

        KafkaNotificationSendAdapter(emitter, mapper).requestSend(
            NotificationSendRequest(
                partyId = UUID.randomUUID(),
                channel = Channel.EMAIL,
                template = "MARKETING_PRODUCT_OFFER",
                recipient = "party-id",
                variables = mapOf("offerTitle" to "Savings"),
                correlationId = UUID.randomUUID(),
            ),
        )

        assertThat(mapper.readTree(payload.captured).has("interactionRef")).isFalse()
    }
}
