// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.notification.application.NotificationConsumer
import com.openbank.notification.domain.model.NotificationChannel
import com.openbank.notification.domain.model.NotificationRequest
import com.openbank.notification.domain.model.NotificationTemplate
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.smallrye.mutiny.Uni
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class KycNotificationConsumerTest {

    private val notificationConsumer = mockk<NotificationConsumer>()
    private val objectMapper: ObjectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())
    private lateinit var consumer: KycNotificationConsumer

    private val caseId = UUID.randomUUID()
    private val partyId = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        consumer = KycNotificationConsumer(notificationConsumer, objectMapper)
        every { notificationConsumer.consume(any()) } returns Uni.createFrom().voidItem()
    }

    /** Captures the [NotificationRequest] JSON(s) handed to [NotificationConsumer.consume]. */
    private fun capturedRequests(): List<NotificationRequest> {
        val slot = mutableListOf<String>()
        verify { notificationConsumer.consume(capture(slot)) }
        return slot.map { objectMapper.readValue(it, NotificationRequest::class.java) }
    }

    /** The exact flat envelope `KycEvents` puts on `openbank.kyc.events` (pinned by the pacts). */
    private fun eventPayload(
        eventType: String,
        kycCaseId: String? = caseId.toString(),
        party: String? = partyId.toString(),
    ): String {
        val fields = mutableListOf(
            "\"eventType\":\"$eventType\"",
            "\"status\":\"VERIFIED\"",
            "\"riskLevel\":\"LOW\"",
            "\"occurredAt\":\"${Instant.parse("2026-09-03T10:00:00Z")}\"",
        )
        kycCaseId?.let { fields += "\"kycCaseId\":\"$it\"" }
        party?.let { fields += "\"partyId\":\"$it\"" }
        return "{${fields.joinToString(",")}}"
    }

    @Test
    fun `case approved notifies the data subject with KYC_APPROVED`() {
        consumer.consume(eventPayload("KYC_CASE_APPROVED")).subscribe().with({}, {})

        val req = capturedRequests().single()
        assertThat(req.partyId).isEqualTo(partyId)
        assertThat(req.recipient).isEqualTo(partyId.toString())
        assertThat(req.template).isEqualTo(NotificationTemplate.KYC_APPROVED)
        assertThat(req.channel).isEqualTo(NotificationChannel.PUSH)
        assertThat(req.variables).isEmpty()
        assertThat(req.correlationId).isEqualTo(caseId)
        assertThat(req.deepLink).isNull()
    }

    @Test
    fun `case rejected notifies with a fixed customer-safe reason, never internal notes`() {
        consumer.consume(eventPayload("KYC_CASE_REJECTED")).subscribe().with({}, {})

        val req = capturedRequests().single()
        assertThat(req.template).isEqualTo(NotificationTemplate.KYC_REJECTED)
        assertThat(req.variables).containsKey("reason")
        // Deliberately fixed text: the reviewer's internal reason (KycCase.notes) is not on the
        // wire and must not be — see the consumer's KDoc.
        assertThat(req.variables["reason"]).isEqualTo("the submitted information could not be verified")
        assertThat(req.correlationId).isEqualTo(caseId)
    }

    @Test
    fun `opened and status-changed events are not notified`() {
        consumer.consume(eventPayload("KYC_CASE_OPENED")).subscribe().with({}, {})
        consumer.consume(eventPayload("KYC_CASE_STATUS_CHANGED")).subscribe().with({}, {})

        verify(exactly = 0) { notificationConsumer.consume(any()) }
    }

    @Test
    fun `malformed JSON is a poison pill, swallowed without throwing`() {
        consumer.consume("not json").subscribe().with({}, {})

        verify(exactly = 0) { notificationConsumer.consume(any()) }
    }

    @Test
    fun `missing or unparseable identifiers are dropped rather than notified with a bad target`() {
        consumer.consume(eventPayload("KYC_CASE_APPROVED", party = null)).subscribe().with({}, {})
        consumer.consume(eventPayload("KYC_CASE_REJECTED", kycCaseId = null)).subscribe().with({}, {})
        consumer.consume(eventPayload("KYC_CASE_APPROVED", kycCaseId = "not-a-uuid")).subscribe().with({}, {})

        verify(exactly = 0) { notificationConsumer.consume(any()) }
    }
}
