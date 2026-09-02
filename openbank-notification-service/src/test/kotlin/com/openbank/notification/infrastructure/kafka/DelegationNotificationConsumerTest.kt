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

class DelegationNotificationConsumerTest {

    private val notificationConsumer = mockk<NotificationConsumer>()
    private val objectMapper: ObjectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())
    private lateinit var consumer: DelegationNotificationConsumer

    private val grantId = UUID.randomUUID()
    private val grantorPartyId = UUID.randomUUID()
    private val granteePartyId = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        consumer = DelegationNotificationConsumer(notificationConsumer, objectMapper)
        every { notificationConsumer.consume(any()) } returns Uni.createFrom().voidItem()
    }

    /** Captures the [NotificationRequest] JSON(s) handed to [NotificationConsumer.consume]. */
    private fun capturedRequests(): List<NotificationRequest> {
        val slot = mutableListOf<String>()
        verify { notificationConsumer.consume(capture(slot)) }
        return slot.map { objectMapper.readValue(it, NotificationRequest::class.java) }
    }

    private fun eventPayload(
        eventType: String,
        resourceType: String = "ACCOUNT",
        aggregateId: UUID = grantId,
        grantor: UUID? = grantorPartyId,
        grantee: UUID? = granteePartyId,
    ): String {
        val fields = mutableListOf(
            "\"eventType\":\"$eventType\"",
            "\"aggregateId\":\"$aggregateId\"",
            "\"resourceType\":\"$resourceType\"",
            "\"occurredAt\":\"${Instant.parse("2026-08-19T10:00:00Z")}\"",
        )
        grantor?.let { fields += "\"grantorPartyId\":\"$it\"" }
        grantee?.let { fields += "\"granteePartyId\":\"$it\"" }
        return "{${fields.joinToString(",")}}"
    }

    @Test
    fun `DelegationOffered notifies the grantee to accept or decline`() {
        consumer.consume(eventPayload("DelegationOffered", resourceType = "CARD"))
            .subscribe().with({}, {})

        val requests = capturedRequests()
        assertThat(requests).hasSize(1)
        val req = requests.single()
        assertThat(req.partyId).isEqualTo(granteePartyId)
        assertThat(req.template).isEqualTo(NotificationTemplate.DELEGATION_OFFERED)
        assertThat(req.channel).isEqualTo(NotificationChannel.PUSH)
        assertThat(req.variables).isEqualTo(mapOf("resourceType" to "CARD"))
        assertThat(req.correlationId).isEqualTo(grantId)
        assertThat(req.deepLink).isEqualTo("openbank://delegations/$grantId")
    }

    @Test
    fun `DelegationActivated notifies the grantor that their offer was accepted`() {
        consumer.consume(eventPayload("DelegationActivated")).subscribe().with({}, {})

        val req = capturedRequests().single()
        assertThat(req.partyId).isEqualTo(grantorPartyId)
        assertThat(req.template).isEqualTo(NotificationTemplate.DELEGATION_ACCEPTED)
    }

    @Test
    fun `DelegationDeclined notifies the grantor`() {
        consumer.consume(eventPayload("DelegationDeclined")).subscribe().with({}, {})

        val req = capturedRequests().single()
        assertThat(req.partyId).isEqualTo(grantorPartyId)
        assertThat(req.template).isEqualTo(NotificationTemplate.DELEGATION_DECLINED)
    }

    @Test
    fun `DelegationRevoked notifies the grantee that access just ended`() {
        consumer.consume(eventPayload("DelegationRevoked")).subscribe().with({}, {})

        val req = capturedRequests().single()
        assertThat(req.partyId).isEqualTo(granteePartyId)
        assertThat(req.template).isEqualTo(NotificationTemplate.DELEGATION_REVOKED)
    }

    @Test
    fun `DelegationExpired notifies BOTH grantor and grantee`() {
        consumer.consume(eventPayload("DelegationExpired")).subscribe().with({}, {})

        val requests = capturedRequests()
        assertThat(requests).hasSize(2)
        assertThat(requests.map { it.partyId }).containsExactlyInAnyOrder(grantorPartyId, granteePartyId)
        assertThat(requests).allSatisfy { assertThat(it.template).isEqualTo(NotificationTemplate.DELEGATION_EXPIRED) }
    }

    @Test
    fun `bank suspension and reinstatement notify both parties`() {
        for (type in listOf("DelegationSuspended", "DelegationReinstated")) {
            consumer.consume(eventPayload(type)).subscribe().with({}, {})
        }

        val requests = capturedRequests()
        assertThat(requests).hasSize(4)
        assertThat(requests.groupingBy { it.template }.eachCount()).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                NotificationTemplate.DELEGATION_SUSPENDED to 2,
                NotificationTemplate.DELEGATION_REINSTATED to 2,
            ),
        )
        assertThat(requests).allSatisfy {
            assertThat(it.partyId).isIn(grantorPartyId, granteePartyId)
            assertThat(it.deepLink).isEqualTo("openbank://delegations/$grantId")
        }
    }

    @Test
    fun `renunciation notifies the grantor`() {
        consumer.consume(eventPayload("DelegationRenounced")).subscribe().with({}, {})

        val request = capturedRequests().single()
        assertThat(request.partyId).isEqualTo(grantorPartyId)
        assertThat(request.template).isEqualTo(NotificationTemplate.DELEGATION_RENOUNCED)
    }

    @Test
    fun `unknown lifecycle types are not notified`() {
        consumer.consume(eventPayload("SomethingElse")).subscribe().with({}, {})

        verify(exactly = 0) { notificationConsumer.consume(any()) }
    }

    @Test
    fun `malformed JSON is a poison pill, swallowed without throwing`() {
        consumer.consume("not json").subscribe().with({}, {})

        verify(exactly = 0) { notificationConsumer.consume(any()) }
    }

    @Test
    fun `missing party identifiers are dropped rather than notified with a bad target`() {
        consumer.consume(eventPayload("DelegationOffered", grantee = null)).subscribe().with({}, {})
        consumer.consume(eventPayload("DelegationActivated", grantor = null)).subscribe().with({}, {})
        // aggregateId present but not a parseable UUID.
        consumer.consume(
            """{"eventType":"DelegationRevoked","aggregateId":"not-a-uuid",""" +
                """"grantorPartyId":"$grantorPartyId","granteePartyId":"$granteePartyId","resourceType":"ACCOUNT"}""",
        ).subscribe().with({}, {})

        verify(exactly = 0) { notificationConsumer.consume(any()) }
    }
}
