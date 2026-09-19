// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.notification.application.NotificationConsumer
import com.openbank.notification.domain.model.MobileDeepLink
import com.openbank.notification.domain.model.NotificationChannel
import com.openbank.notification.domain.model.NotificationLanguage
import com.openbank.notification.domain.model.NotificationRequest
import com.openbank.notification.domain.model.NotificationTemplate
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.smallrye.mutiny.Uni
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class ApprovalNotificationConsumerTest {

    private val notificationConsumer = mockk<NotificationConsumer>()
    private val objectMapper: ObjectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())
    private val meters = SimpleMeterRegistry()
    private lateinit var consumer: ApprovalNotificationConsumer

    private val fixture: String = requireNotNull(
        javaClass.getResource("/approval-events/approval-requested.v1.json"),
    ).readText()
    private val eventId = UUID.fromString("0199a1b2-0000-7000-8000-00000000e001")
    private val approvalId = UUID.fromString("0199a1b2-0000-7000-8000-0000000a0001")
    private val initiator = UUID.fromString("0199a1b2-0000-7000-8000-0000000b0001")
    private val signerA = UUID.fromString("0199a1b2-0000-7000-8000-0000000b0002")
    private val signerB = UUID.fromString("0199a1b2-0000-7000-8000-0000000b0003")

    @BeforeEach
    fun setUp() {
        consumer = ApprovalNotificationConsumer(notificationConsumer, objectMapper, meters)
        every { notificationConsumer.consume(any()) } returns Uni.createFrom().voidItem()
    }

    private fun captured(): List<NotificationRequest> {
        val slot = mutableListOf<String>()
        verify(atLeast = 0) { notificationConsumer.consume(capture(slot)) }
        return slot.map { objectMapper.readValue(it, NotificationRequest::class.java) }
    }

    private fun event(edit: ObjectNode.() -> Unit = {}): String =
        (objectMapper.readTree(fixture) as ObjectNode).apply(edit).toString()

    private fun run(payload: String) {
        consumer.consume(payload).await().indefinitely()
    }

    private fun counted(type: String, disposition: String): Double =
        meters.find(ApprovalNotificationConsumer.METRIC_EVENTS)
            .tags("type", type, "disposition", disposition).counter()?.count() ?: 0.0

    @Test
    fun `the producer's v1 message shape fans APPROVAL_REQUIRED out to every co-signer but never the initiator`() {
        run(fixture)

        val requests = captured()
        assertThat(requests.map { it.partyId }).containsExactlyInAnyOrder(signerA, signerB)
        assertThat(requests).allSatisfy { req ->
            assertThat(req.template).isEqualTo(NotificationTemplate.APPROVAL_REQUIRED)
            assertThat(req.channel).isEqualTo(NotificationChannel.PUSH)
            assertThat(req.deepLink)
                .isEqualTo("openbank://business/approvals/$approvalId?entity=0199a1b2-0000-7000-8000-0000000e0001")
            assertThat(MobileDeepLink.isAllowed(req.deepLink)).isTrue()
            assertThat(req.correlationId).isEqualTo(approvalId)
            assertThat(req.language).isEqualTo(NotificationLanguage.CS)
            // Every key the request carries is one the template declares — the closed schema that
            // NotificationConsumer rejects on, so a mismatch here would drop the notification.
            assertThat(req.template.unknownVariables(req.variables)).isEmpty()
            assertThat(req.variables).containsEntry("entityName", "Example Bakery s.r.o.")
                .containsEntry("kind", "PAYMENT")
                .containsEntry("amountFormatted", "125 000,50 CZK")
                .containsEntry("payeeName", "Example Mill a.s.")
                .containsEntry("initiatorName", "Initiator Example")
                .containsEntry("expiresAt", "21. 9. 2026 10:15")
        }
        assertThat(counted("APPROVAL_REQUESTED", "handed_off")).isEqualTo(2.0)
    }

    @Test
    fun `each recipient gets a distinct, deterministic deduplication key derived from eventId`() {
        run(fixture)
        run(fixture)

        val keys = captured().map { it.partyId to it.deduplicationKey }
        // Both deliveries produce the SAME keys (so the unique index turns the replay into a no-op)...
        assertThat(keys.take(2)).containsExactlyInAnyOrderElementsOf(keys.drop(2))
        // ...and within one event the keys differ per recipient (the index is global).
        assertThat(keys.take(2).map { it.second }.toSet()).hasSize(2)
        assertThat(keys.take(2)).contains(signerA to ApprovalNotificationConsumer.deduplicationKey(eventId, signerA))
    }

    @Test
    fun `a different eventId for the same approval is a different fact and notifies again`() {
        run(fixture)
        run(event { put("eventId", UUID.randomUUID().toString()) })

        assertThat(captured().map { it.deduplicationKey }.toSet()).hasSize(4)
    }

    @Test
    fun `outcome events reach the initiator as well as the recipients, without duplicates`() {
        run(
            event {
                put("type", "APPROVAL_REJECTED")
                put("reason", "Wrong account")
                put("locale", "en")
            },
        )

        val requests = captured()
        assertThat(requests.map { it.partyId }).containsExactlyInAnyOrder(initiator, signerA, signerB)
        assertThat(requests).allSatisfy { req ->
            assertThat(req.template).isEqualTo(NotificationTemplate.APPROVAL_REJECTED)
            assertThat(req.language).isEqualTo(NotificationLanguage.EN)
            assertThat(req.variables).containsEntry("reason", "Wrong account")
                .containsEntry("amountFormatted", "125,000.50 CZK")
                .doesNotContainKey("initiatorName")
            assertThat(req.template.unknownVariables(req.variables)).isEmpty()
        }
    }

    @Test
    fun `every notified type maps to its template with a variable set the template accepts`() {
        mapOf(
            "APPROVAL_COMPLETED" to NotificationTemplate.APPROVAL_COMPLETED,
            "APPROVAL_EXPIRED" to NotificationTemplate.APPROVAL_EXPIRED,
            "PAYMENT_RELEASE_FAILED" to NotificationTemplate.PAYMENT_RELEASE_FAILED,
        ).forEach { (type, template) ->
            val seen = AtomicInteger()
            every { notificationConsumer.consume(any()) } answers {
                val req = objectMapper.readValue(firstArg<String>(), NotificationRequest::class.java)
                assertThat(req.template).isEqualTo(template)
                assertThat(req.template.unknownVariables(req.variables)).isEmpty()
                seen.incrementAndGet()
                Uni.createFrom().voidItem()
            }
            run(event { put("type", type) })
            assertThat(seen.get()).describedAs(type).isEqualTo(3)
        }
    }

    @Test
    fun `APPROVAL_SIGNED and PAYMENT_RELEASED are read and deliberately not notified`() {
        run(event { put("type", "APPROVAL_SIGNED") })
        run(event { put("type", "PAYMENT_RELEASED") })
        run(event { put("type", "SOMETHING_NEW") })

        verify(exactly = 0) { notificationConsumer.consume(any()) }
        assertThat(counted("APPROVAL_SIGNED", "ignored")).isEqualTo(1.0)
        assertThat(counted("PAYMENT_RELEASED", "ignored")).isEqualTo(1.0)
        assertThat(counted("other", "ignored")).isEqualTo(1.0)
    }

    @Test
    fun `a record missing a required identifier or not JSON at all is acked as a poison pill`() {
        run(event { remove("eventId") })
        run(event { put("recipientPartyIds", objectMapper.createArrayNode().add("not-a-uuid")) })
        run("{not json")
        run("[]")

        verify(exactly = 0) { notificationConsumer.consume(any()) }
        assertThat(counted("APPROVAL_REQUESTED", "malformed")).isEqualTo(2.0)
        assertThat(counted("?", "malformed")).isEqualTo(2.0)
    }

    @Test
    fun `an absent or malformed entityPartyId falls back to the bare link, which the allow-list accepts`() {
        run(event { remove("entityPartyId") })
        run(event { put("entityPartyId", "x&next=https://evil.invalid") })

        assertThat(captured()).hasSize(4).allSatisfy { req ->
            assertThat(req.deepLink).isEqualTo("openbank://business/approvals/$approvalId")
            assertThat(MobileDeepLink.isAllowed(req.deepLink)).isTrue()
        }
    }

    @Test
    fun `optional fields may be absent - no amount renders no amount, not a zero`() {
        run(
            event {
                put("kind", "POLICY_CHANGE")
                remove("amount")
                remove("currency")
                remove("payeeName")
                remove("schemaVersion")
                remove("locale")
            },
        )

        val requests = captured()
        assertThat(requests).hasSize(2).allSatisfy { req ->
            assertThat(req.variables).doesNotContainKeys("amountFormatted", "payeeName")
            assertThat(req.language).isEqualTo(NotificationLanguage.CS)
        }
    }

    @Test
    fun `a transient pipeline failure is retried, and a persistent one fails the Uni so the record is nacked`() {
        val calls = AtomicInteger()
        every { notificationConsumer.consume(any()) } answers {
            if (calls.incrementAndGet() == 1) {
                Uni.createFrom().failure(IllegalStateException("transient"))
            } else {
                Uni.createFrom().voidItem()
            }
        }
        run(fixture)
        // 1 failure + 1 retry for the first recipient, 1 call for the second.
        assertThat(calls.get()).isEqualTo(3)

        every { notificationConsumer.consume(any()) } returns Uni.createFrom().failure(IllegalStateException("down"))
        assertThatThrownBy { run(fixture) }.hasMessageContaining("down")
    }
}
