// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.openbank.notification.infrastructure.client.PartyMergeResolver
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * `consume`'s three ingress rejections — the branches that must ACK-and-drop *before* the request
 * reaches [NotificationConsumer.dispatch].
 *
 * The proof that each really short-circuits is [PartyMergeResolver]: it is the first collaborator
 * `dispatch` touches and it is a strict mock here, so any request that is NOT rejected calls an
 * unstubbed method and the test fails. That is what makes these assertions non-vacuous — a
 * rejection that silently fell through to dispatch would be caught, not reported as a pass.
 */
class NotificationConsumerRejectionTest {

    private val mergeResolver = mockk<PartyMergeResolver>()

    private val consumer = NotificationConsumer(mailerMocked = false, pushFallbackEnabled = false).also {
        it.objectMapper = ObjectMapper().registerKotlinModule()
        it.partyMergeResolver = mergeResolver
    }

    private val partyId: UUID = UUID.randomUUID()

    private fun json(template: String, variables: String, extra: String = "") =
        """{"partyId":"$partyId","channel":"PUSH","template":"$template",""" +
            """"recipient":"$partyId","variables":$variables$extra}"""

    @Test
    fun `a payload that is not JSON at all is dropped, not rethrown onto the partition`() {
        consumer.consume("this is not json").await().indefinitely()

        verify(exactly = 0) { mergeResolver.resolve(any()) }
    }

    @Test
    fun `a payload naming a template that does not exist is dropped`() {
        consumer.consume(json("NO_SUCH_TEMPLATE", """{"name":"Ada"}""")).await().indefinitely()

        verify(exactly = 0) { mergeResolver.resolve(any()) }
    }

    @Test
    fun `an undeclared variable is rejected before anything is rendered or persisted`() {
        // The #1325 shape: a secret-shaped key riding an ordinary template into storage.
        val payload = json("WELCOME", """{"name":"Ada","otp":"314159"}""")

        consumer.consume(payload).await().indefinitely()

        verify(exactly = 0) { mergeResolver.resolve(any()) }
    }

    @Test
    fun `a non-bank deep link is rejected`() {
        val payload = json("WELCOME", """{"name":"Ada"}""", ""","deepLink":"https://evil.example/steal"""")

        consumer.consume(payload).await().indefinitely()

        verify(exactly = 0) { mergeResolver.resolve(any()) }
    }

    @Test
    fun `a deep link on an EMAIL request is rejected even when the route itself is allow-listed`() {
        // The allow-list is only consulted for PUSH: navigation metadata has no meaning on EMAIL,
        // so an allowed route on the wrong channel is still a malformed request.
        val payload = """{"partyId":"$partyId","channel":"EMAIL","template":"WELCOME",""" +
            """"recipient":"a@b.example","variables":{"name":"Ada"},"deepLink":"openbank://home"}"""

        consumer.consume(payload).await().indefinitely()

        verify(exactly = 0) { mergeResolver.resolve(any()) }
    }

    @Test
    fun `every rejection completes the Uni so the record is acked rather than wedging the partition`() {
        val result = consumer.consume("{ broken").await().indefinitely()

        assertThat(result).isNull()
    }
}
