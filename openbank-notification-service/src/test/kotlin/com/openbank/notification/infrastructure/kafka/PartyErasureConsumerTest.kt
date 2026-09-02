// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.messaging.EventRetry
import com.openbank.notification.infrastructure.persistence.repository.DeviceTokenRepository
import com.openbank.notification.infrastructure.persistence.repository.NotificationRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class PartyErasureConsumerTest {

    private val deviceTokenRepository = mockk<DeviceTokenRepository>()
    private val notificationRepository = mockk<NotificationRepository>()
    private lateinit var consumer: PartyErasureConsumer

    @BeforeEach
    fun setUp() {
        consumer = PartyErasureConsumer().also {
            it.deviceTokenRepository = deviceTokenRepository
            it.notificationRepository = notificationRepository
            it.objectMapper = ObjectMapper()
        }
    }

    @Test
    fun `PARTY_ERASED deletes device tokens and notifications`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        coEvery { deviceTokenRepository.deleteByPartyId(partyId) } returns 2L
        coEvery { notificationRepository.deleteByPartyId(partyId) } returns 5L

        consumer.consume("""{"eventType":"PARTY_ERASED","partyId":"$partyId"}""")

        coVerify(exactly = 1) { deviceTokenRepository.deleteByPartyId(partyId) }
        coVerify(exactly = 1) { notificationRepository.deleteByPartyId(partyId) }
    }

    @Test
    fun `non-PARTY_ERASED events are ignored`(): Unit = runBlocking {
        consumer.consume("""{"eventType":"PARTY_CREATED","partyId":"${UUID.randomUUID()}"}""")

        coVerify(exactly = 0) { deviceTokenRepository.deleteByPartyId(any()) }
        coVerify(exactly = 0) { notificationRepository.deleteByPartyId(any()) }
    }

    @Test
    fun `malformed payload is acked without throwing`(): Unit = runBlocking {
        consumer.consume("not json")
        consumer.consume("""{"eventType":"PARTY_ERASED"}""") // no partyId

        coVerify(exactly = 0) { deviceTokenRepository.deleteByPartyId(any()) }
    }

    @Test
    fun `erasure failure is retried and rethrown so the connector dead-letters`(): Unit = runBlocking {
        // This test previously asserted the OPPOSITE — "erasure failure is swallowed to protect the
        // consumer group" — and it PASSED, which is exactly the defect (#5698). Returning normally
        // ACKS the message, so a failed GDPR Art. 17 erasure left device tokens and notification
        // history in place while the log recorded the erasure as done: an acked message and a
        // successful one are indistinguishable from outside. A test that reads as error handling
        // was the thing certifying the breach.
        val partyId = UUID.randomUUID()
        coEvery { deviceTokenRepository.deleteByPartyId(partyId) } throws RuntimeException("db down")

        assertThatThrownBy {
            runBlocking { consumer.consume("""{"eventType":"PARTY_ERASED","partyId":"$partyId"}""") }
        }.isInstanceOf(RuntimeException::class.java)

        // Bounded, not unbounded: a permanently unreachable DB must not pin the partition forever.
        coVerify(exactly = EventRetry.DEFAULT_MAX_ATTEMPTS) { deviceTokenRepository.deleteByPartyId(partyId) }
    }
}
