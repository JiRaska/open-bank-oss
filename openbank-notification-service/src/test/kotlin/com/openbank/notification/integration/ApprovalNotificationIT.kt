// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.notification.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.openbank.notification.infrastructure.persistence.entity.DeviceTokenEntity
import com.openbank.notification.infrastructure.persistence.entity.NotificationEntity
import com.openbank.notification.infrastructure.persistence.entity.NotificationPreferenceEntity
import com.openbank.notification.infrastructure.persistence.repository.DeviceTokenRepository
import com.openbank.notification.infrastructure.persistence.repository.NotificationPreferenceRepository
import com.openbank.notification.infrastructure.persistence.repository.NotificationRepository
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import io.smallrye.reactive.messaging.memory.InMemorySource
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.eclipse.microprofile.reactive.messaging.Message
import org.eclipse.microprofile.reactive.messaging.Metadata
import org.eclipse.microprofile.reactive.messaging.spi.Connector
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import java.util.function.Function
import java.util.function.Supplier

/**
 * #10281 end to end through the REAL `approval-events-in` channel (in-memory connector), the real
 * `NotificationConsumer` pipeline and a real Postgres carrying the dedup unique index — the two
 * properties a mocked pipeline cannot establish:
 *  - the same `eventId` delivered twice yields ONE inbox row per recipient (the index, not offset
 *    timing, is the authority), and
 *  - `APPROVAL_REQUIRED` reaches the device even when every mutable push category is switched off,
 *    while a PAYMENTS-category outcome to the same person is suppressed by that preference.
 */
@QuarkusTest
@QuarkusTestResource(ApprovalNotificationIT.InMemoryKafkaResource::class)
@QuarkusTestResource(com.openbank.notification.it.PostgresTestResource::class)
class ApprovalNotificationIT {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchIncomingChannelsToInMemory("approval-events-in", "notification-events-in") +
                InMemoryConnector.switchOutgoingChannelsToInMemory("notification-events-out")

        override fun stop() = InMemoryConnector.clear()
    }

    @Inject lateinit var repository: NotificationRepository

    @Inject lateinit var deviceTokenRepo: DeviceTokenRepository

    @Inject lateinit var preferenceRepo: NotificationPreferenceRepository

    @Inject lateinit var objectMapper: ObjectMapper

    @Inject
    @Connector("smallrye-in-memory")
    lateinit var connector: InMemoryConnector

    private val fixture: String = requireNotNull(
        javaClass.getResource("/approval-events/approval-requested.v1.json"),
    ).readText()

    private fun event(
        eventId: UUID,
        initiator: UUID,
        recipients: List<UUID>,
        type: String = "APPROVAL_REQUESTED",
    ): String = (objectMapper.readTree(fixture) as ObjectNode).apply {
        put("eventId", eventId.toString())
        put("type", type)
        put("approvalId", UUID.randomUUID().toString())
        put("initiatorPartyId", initiator.toString())
        putArray("recipientPartyIds").apply { recipients.forEach { add(it.toString()) } }
    }.toString()

    /** Sends one record and reports which of ack/nack the connector invoked. */
    private fun send(payload: String): String {
        val source: InMemorySource<Message<String>> = connector.source("approval-events-in")
        source.runOnVertxContext(true)
        val outcome = CompletableFuture<String>()
        source.send(
            Message.of(
                payload,
                Metadata.empty(),
                Supplier<CompletionStage<Void>> {
                    outcome.complete("acked")
                    CompletableFuture.completedFuture<Void>(null)
                },
                Function<Throwable, CompletionStage<Void>> { _ ->
                    outcome.complete("nacked")
                    CompletableFuture.completedFuture<Void>(null)
                },
            ),
        )
        return outcome.get(30, TimeUnit.SECONDS)
    }

    private fun rowsFor(partyId: UUID): List<NotificationEntity> = VertxContextSupport.subscribeAndAwait {
        Panache.withSession { repository.find("partyId", partyId).list() }
    }

    private fun seedDevice(partyId: UUID) {
        VertxContextSupport.subscribeAndAwait {
            Panache.withTransaction {
                val now = Instant.now()
                deviceTokenRepo.persist(
                    DeviceTokenEntity().also {
                        it.deviceId = UUID.randomUUID()
                        it.partyId = partyId
                        it.appInstance = "approval-it-$partyId"
                        it.platform = "APNS"
                        it.token = OffContextPushSender.GOOD_TOKEN
                        it.status = "ACTIVE"
                        it.registeredAt = now
                        it.createdAt = now
                        it.updatedAt = now
                    },
                )
            }
        }
    }

    /**
     * The shared test push adapter accepts only its one GOOD_TOKEN, and `(platform, token)` is
     * unique, while every @QuarkusTest in this JVM shares one Postgres — so the seeded device must
     * not outlive the test, or the next class seeding the same token fails on the index.
     */
    private fun removeDevice(partyId: UUID) {
        VertxContextSupport.subscribeAndAwait {
            Panache.withTransaction { deviceTokenRepo.delete("partyId", partyId) }
        }
    }

    private fun muteEveryMutableCategory(partyId: UUID) {
        VertxContextSupport.subscribeAndAwait {
            Panache.withTransaction {
                preferenceRepo.persist(
                    NotificationPreferenceEntity().also {
                        it.partyId = partyId
                        it.paymentsPush = false
                        it.productPush = false
                        it.marketingPush = false
                        it.updatedAt = Instant.now()
                    },
                )
            }
        }
    }

    /**
     * The key the Kafka connector actually reads must resolve from the image alone: a missing or
     * YAML-quoted `group.id` falls back to the application name, which the KafkaUser ACL does not
     * grant, and the channel then consumes nothing while reporting healthy (#686).
     */
    @Test
    fun `the channel consumer group and offset reset resolve from the image under the keys the connector reads`() {
        val config = ConfigProvider.getConfig()
        assertThat(config.getOptionalValue("mp.messaging.incoming.approval-events-in.group.id", String::class.java))
            .hasValue("notification-service-approval")
        assertThat(
            config.getOptionalValue("mp.messaging.incoming.approval-events-in.auto.offset.reset", String::class.java),
        ).hasValue("earliest")
        assertThat(config.getOptionalValue("mp.messaging.incoming.approval-events-in.client.id", String::class.java))
            .hasValueSatisfying { assertThat(it).startsWith("notification-service-approval-").doesNotContain("\${") }
    }

    @Test
    fun `the same eventId delivered twice yields exactly one inbox row per co-signer and none for the initiator`() {
        val initiator = UUID.randomUUID()
        val signerA = UUID.randomUUID()
        val signerB = UUID.randomUUID()
        val payload = event(UUID.randomUUID(), initiator, listOf(initiator, signerA, signerB))

        assertThat(send(payload)).isEqualTo("acked")
        assertThat(send(payload)).isEqualTo("acked")

        listOf(signerA, signerB).forEach { signer ->
            val rows = rowsFor(signer)
            assertThat(rows).describedAs("rows for a co-signer").hasSize(1)
            val row = rows.single()
            assertThat(row.template).isEqualTo("APPROVAL_REQUIRED")
            assertThat(row.readAt).describedAs("a fresh inbox row is unread").isNull()
            assertThat(row.subject).isEqualTo("Čeká na tvůj podpis")
            assertThat(row.body).contains("Example Bakery s.r.o.")
        }
        assertThat(rowsFor(initiator)).describedAs("the initiator is never asked to co-sign").isEmpty()
    }

    @Test
    fun `APPROVAL_REQUIRED is pushed although every mutable category is muted, a PAYMENTS outcome is not`() {
        val initiator = UUID.randomUUID()
        val signer = UUID.randomUUID()
        seedDevice(signer)
        try {
            assertSecurityBypassesMute(initiator, signer)
        } finally {
            removeDevice(signer)
        }
    }

    private fun assertSecurityBypassesMute(initiator: UUID, signer: UUID) {
        muteEveryMutableCategory(signer)
        OffContextPushSender.SENT.clear()

        assertThat(send(event(UUID.randomUUID(), initiator, listOf(signer)))).isEqualTo("acked")

        val required = rowsFor(signer).single { it.template == "APPROVAL_REQUIRED" }
        assertThat(required.status).isEqualTo("SENT")
        val pushed = OffContextPushSender.SENT.filter {
            it.data["notificationId"] == required.notificationId.toString()
        }
        assertThat(pushed).hasSize(1)
        assertThat(pushed.single().data["deepLink"]).startsWith("openbank://business/approvals/")
        // Lock-screen text carries nothing about the company or the money (ADR-0135 §3).
        assertThat(pushed.single().title).doesNotContain("Example", "CZK")

        assertThat(send(event(UUID.randomUUID(), initiator, listOf(signer), type = "APPROVAL_COMPLETED")))
            .isEqualTo("acked")
        val completed = rowsFor(signer).single { it.template == "APPROVAL_COMPLETED" }
        assertThat(completed.status).describedAs("PAYMENTS-category outcome honours the mute").isEqualTo("SUPPRESSED")
    }
}
