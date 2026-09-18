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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class DelegationNotificationConsumerTest {

    private val notificationConsumer = mockk<NotificationConsumer>()
    private val objectMapper: ObjectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())
    private lateinit var consumer: DelegationNotificationConsumer

    private val grantId = UUID.randomUUID()
    private val grantorPartyId = UUID.randomUUID()
    private val granteePartyId = UUID.randomUUID()
    private val recertificationId = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        consumer = DelegationNotificationConsumer(notificationConsumer, objectMapper)
        every { notificationConsumer.consume(any()) } returns Uni.createFrom().voidItem()
        every { notificationConsumer.recordJointCancellation(any(), any(), any(), any(), any()) } returns
            Uni.createFrom().voidItem()
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

    private fun statutoryOpened(
        kind: String = "ISSUE",
        recipients: List<String> = listOf(grantorPartyId.toString(), granteePartyId.toString()),
        expiresAt: String = "2026-09-19T12:00:00Z",
        sourceService: String = "delegation-service",
        version: Int = 1,
    ): String = objectMapper.writeValueAsString(
        mapOf(
            "eventType" to "StatutoryDelegationProposalOpened",
            "aggregateType" to "StatutoryDelegationOperation",
            "version" to version,
            "sourceService" to sourceService,
            "aggregateId" to grantId.toString(),
            "principalPartyId" to UUID.randomUUID().toString(),
            "actorId" to grantorPartyId.toString(),
            "operationKind" to kind,
            "representativePartyIds" to recipients,
            "expiresAt" to expiresAt,
        ),
    )

    private fun statutoryCancelled(sourceService: String = "delegation-service"): String =
        objectMapper.writeValueAsString(
            mapOf(
                "eventType" to "StatutoryDelegationProposalCancelled",
                "aggregateType" to "StatutoryDelegationOperation",
                "version" to 1,
                "sourceService" to sourceService,
                "aggregateId" to grantId.toString(),
                "principalPartyId" to UUID.randomUUID().toString(),
                "actorId" to grantorPartyId.toString(),
                "operationKind" to "ISSUE",
                "requestHash" to "a".repeat(64),
                "ruleHash" to "b".repeat(64),
                "occurredAt" to "2026-09-18T12:00:00Z",
            ),
        )

    @Test
    fun `joint cancellation records a tombstone and sends no new prompt`() {
        consumer.consume(statutoryCancelled()).subscribe().with({}, {})

        verify(exactly = 1) {
            notificationConsumer.recordJointCancellation(grantId, any(), grantorPartyId, "ISSUE", any())
        }
        verify(exactly = 0) { notificationConsumer.consume(any()) }
    }

    @Test
    fun `joint cancellation with forged source is ignored`() {
        consumer.consume(statutoryCancelled(sourceService = "other-service")).subscribe().with({}, {})

        verify(exactly = 0) { notificationConsumer.recordJointCancellation(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `joint proposal notifies each frozen human with a distinct replay safe key`() {
        consumer = DelegationNotificationConsumer(
            notificationConsumer,
            objectMapper,
            Clock.fixed(Instant.parse("2026-09-18T12:00:00Z"), ZoneOffset.UTC),
        )
        consumer.consume(statutoryOpened()).subscribe().with({}, {})

        val requests = capturedRequests()
        assertThat(requests).hasSize(2)
        assertThat(requests.map { it.partyId }).containsExactly(grantorPartyId, granteePartyId)
        assertThat(requests).allSatisfy {
            assertThat(it.template).isEqualTo(NotificationTemplate.JOINT_ISSUANCE_SIGNATURE_REQUESTED)
            assertThat(it.channel).isEqualTo(NotificationChannel.PUSH)
            assertThat(it.variables).isEmpty()
            assertThat(it.correlationId).isEqualTo(grantId)
            assertThat(it.deepLink).isEqualTo("openbank://delegations/joint-issuance")
            assertThat(it.deduplicationKey).isNotNull()
            assertThat(it.deliveryNotAfter).isEqualTo(Instant.parse("2026-09-19T12:00:00Z"))
        }
        assertThat(requests.map { it.deduplicationKey }.distinct()).hasSize(2)
    }

    @Test
    fun `joint acceptance uses its own screen and template`() {
        consumer = DelegationNotificationConsumer(
            notificationConsumer,
            objectMapper,
            Clock.fixed(Instant.parse("2026-09-18T12:00:00Z"), ZoneOffset.UTC),
        )
        consumer.consume(statutoryOpened(kind = "ACCEPT")).subscribe().with({}, {})

        assertThat(capturedRequests()).allSatisfy {
            assertThat(it.template).isEqualTo(NotificationTemplate.JOINT_ACCEPTANCE_SIGNATURE_REQUESTED)
            assertThat(it.deepLink).isEqualTo("openbank://delegations/joint-acceptance")
        }
    }

    @Test
    fun `joint proposal replay keeps one stable dedup key per representative`() {
        consumer = DelegationNotificationConsumer(
            notificationConsumer,
            objectMapper,
            Clock.fixed(Instant.parse("2026-09-18T12:00:00Z"), ZoneOffset.UTC),
        )
        val payload = statutoryOpened()
        consumer.consume(payload).subscribe().with({}, {})
        consumer.consume(payload).subscribe().with({}, {})

        val requests = capturedRequests()
        assertThat(requests).hasSize(4)
        assertThat(requests[0].deduplicationKey).isEqualTo(requests[2].deduplicationKey)
        assertThat(requests[1].deduplicationKey).isEqualTo(requests[3].deduplicationKey)
        assertThat(requests[0].deduplicationKey).isNotEqualTo(requests[1].deduplicationKey)
    }

    @Test
    fun `stale or malformed statutory proposals do not notify`() {
        consumer = DelegationNotificationConsumer(
            notificationConsumer,
            objectMapper,
            Clock.fixed(Instant.parse("2026-09-18T12:00:00Z"), ZoneOffset.UTC),
        )
        listOf(
            statutoryOpened(expiresAt = "2026-09-18T12:00:00Z"),
            statutoryOpened(sourceService = "other-service"),
            statutoryOpened(version = 2),
            statutoryOpened(recipients = listOf(grantorPartyId.toString(), grantorPartyId.toString())),
            statutoryOpened(recipients = listOf("bad-id", granteePartyId.toString())),
            statutoryOpened(kind = "OTHER"),
        ).forEach { consumer.consume(it).subscribe().with({}, {}) }

        verify(exactly = 0) { notificationConsumer.consume(any()) }
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
    fun `first confirmed delegated spend notifies only the grantor with a stable deduplication key`() {
        consumer.consume(
            eventPayload("SpendConfirmed").dropLast(1) +
                ",\"sourceService\":\"delegation-service\",\"reservationId\":\"${UUID.randomUUID()}\"}",
        ).subscribe().with({}, {})

        val req = capturedRequests().single()
        assertThat(req.partyId).isEqualTo(grantorPartyId)
        assertThat(req.template).isEqualTo(NotificationTemplate.DELEGATION_FIRST_USE)
        assertThat(req.variables).isEmpty()
        assertThat(req.correlationId).isEqualTo(grantId)
        assertThat(req.deduplicationKey).isEqualTo(grantId)
    }

    @Test
    fun `due recertification notifies only the grantor and never requires a grantee id`() {
        consumer.consume(
            eventPayload("DelegationRecertificationDue", grantee = null).dropLast(1) +
                ",\"recertificationId\":\"$recertificationId\",\"audience\":\"CORPORATE\"}",
        ).subscribe().with({}, {})

        val request = capturedRequests().single()
        assertThat(request.partyId).isEqualTo(grantorPartyId)
        assertThat(request.template).isEqualTo(NotificationTemplate.DELEGATION_RECERTIFICATION_DUE)
        assertThat(request.variables).isEqualTo(mapOf("audience" to "CORPORATE"))
        assertThat(request.deepLink).isEqualTo("openbank://delegations/$grantId")
        assertThat(request.deduplicationKey).isEqualTo(recertificationId)
    }

    @Test
    fun `due recertification with an unknown audience is dropped fail closed`() {
        consumer.consume(
            eventPayload("DelegationRecertificationDue", grantee = null).dropLast(1) +
                ",\"recertificationId\":\"$recertificationId\",\"audience\":\"UNKNOWN\"}",
        ).subscribe().with({}, {})

        verify(exactly = 0) { notificationConsumer.consume(any()) }
    }

    @Test
    fun `due recertification without a stable cycle id is dropped fail closed`() {
        consumer.consume(
            eventPayload("DelegationRecertificationDue", grantee = null).dropLast(1) +
                ",\"audience\":\"FOP\"}",
        ).subscribe().with({}, {})

        verify(exactly = 0) { notificationConsumer.consume(any()) }
    }

    @Test
    fun `first confirmed spend from another source is rejected`() {
        consumer.consume(
            eventPayload("SpendConfirmed").dropLast(1) + ",\"sourceService\":\"other-service\"}",
        ).subscribe().with({}, {})

        verify(exactly = 0) { notificationConsumer.consume(any()) }
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
        consumer.consume(
            """{"eventType":"StatutoryDelegationProposalCancelled","aggregateId":"${UUID.randomUUID()}","actorId":"${UUID.randomUUID()}"}""",
        ).subscribe().with({}, {})

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
