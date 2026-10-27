// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.e2e

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.testing.containers.PostgresRedpandaRedisTestResource
import com.openbank.notification.domain.model.NotificationChannel
import com.openbank.notification.domain.model.NotificationOutcomeEvent
import com.openbank.notification.domain.model.NotificationRequest
import com.openbank.notification.domain.model.NotificationTemplate
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.QuarkusTestProfile.TestResourceEntry
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.errors.TopicExistsException
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * Real-broker wire E2E for the notification delivery-outcome relay
 * (`notification-events-out` → `openbank.notification.outcomes.v1`, ADR-0239 D2). The
 * channel binding and the outbox that feeds it predate each other — until ADR-0239 the
 * outbox had no writer and this channel had no topic, so the service produced nothing.
 * This suite is the first end-to-end proof that a request entering
 * `openbank.notification.requests` actually leaves on the outcome topic.
 *
 * The whole loop is real: a `NotificationRequest` is produced to the requests topic over
 * the real Kafka surface, `NotificationConsumer` persists the notification row and the
 * outcome outbox row in one transaction (ADR-0003), the real `@Scheduled` dispatcher
 * drains the row to a Redpanda container, and a fresh consumer group reads the record
 * back. The PUSH provider edge leaves this service by design (ADR-0252 phase 0: every
 * push adapter is disabled), so a PUSH to a party with no registered device deterministically
 * lands `FAILED` + `no_active_device` — no external stub needed.
 *
 * Transport contract, measured against the shared OutboxKafkaHeaders: the partition key
 * is the notificationId (`outcomeMessage` documents why it cannot be the optional
 * correlation id), `ce-type` = `NotificationOutcome`, `ce-id` present and
 * `idempotency-key` == ce-id (ADR-0050 N3). The producer-side join key is the
 * producer-owned `correlationId` (ADR-0239 D1) — the test sets one and finds its outcome
 * on the wire by it.
 *
 * Payload contract: the serialized `NotificationOutcomeEvent` — `notificationId`,
 * `correlationId`, `partyId`, `channel`, `template`, `outcome`, `reason`, `occurredAt`,
 * `templateRevision`.
 *
 * The second test pins the documented deduplication: a replay carrying the same
 * `deduplicationKey` after the first fact reached a terminal state is a quiet no-op
 * ("Skipping completed notification fact") — no second notification row, no second
 * outcome event on the wire.
 *
 * Boot hazards pinned in the profile, each measured on the earlier wire suites: the
 * hibernate-orm/jdbc switches for @Scheduled Panache work, the scheduler re-enabled
 * (%test disables it), a 1 s outbox tick, and both connectors pinned to smallrye-kafka
 * because the module's IT suite switches them to in-memory and only clear()s messages,
 * not the switch, on stop.
 */
@QuarkusTest
@TestProfile(NotificationOutcomesWireE2E.WireProfile::class)
class NotificationOutcomesWireE2E {

    @Inject
    lateinit var dataSource: DataSource

    private val mapper = ObjectMapper()

    @Test
    fun `a push request with no device publishes its FAILED outcome on the wire`() {
        val partyId = UUID.randomUUID()
        val correlationId = UUID.randomUUID()
        sendRequest(
            NotificationRequest(
                partyId = partyId,
                channel = NotificationChannel.PUSH,
                template = NotificationTemplate.ACCOUNT_FROZEN,
                recipient = "wire-outcome@example.com",
                variables = mapOf("accountNumber" to "synthetic-account-token", "reason" to "wire probe"),
                correlationId = correlationId,
            ),
        )

        val record = awaitOutcome(correlationId)
        assertThat(record.topic()).isEqualTo(OUTCOMES_TOPIC)
        assertThat(record.key()).isNotBlank()
        val eventId = header(record, "ce-id")
        assertThat(eventId).isNotBlank()
        assertThat(header(record, "ce-type")).isEqualTo(NotificationOutcomeEvent.EVENT_TYPE)
        assertThat(header(record, "idempotency-key")).isEqualTo(eventId)

        val body = mapper.readTree(record.value())
        val notificationId = body["notificationId"].asText()
        assertThat(notificationId).isNotBlank()
        assertThat(body["correlationId"].asText()).isEqualTo(correlationId.toString())
        assertThat(body["partyId"].asText()).isEqualTo(partyId.toString())
        assertThat(body["channel"].asText()).isEqualTo("PUSH")
        assertThat(body["template"].asText()).isEqualTo("ACCOUNT_FROZEN")
        assertThat(body["outcome"].asText()).isEqualTo("FAILED")
        assertThat(body["reason"].asText()).isEqualTo(NotificationOutcomeEvent.REASON_NO_DEVICE)
        assertThat(body["occurredAt"].asText()).isNotBlank()

        // The relay marked the row SENT — the durable hand-off boundary the dispatcher owns.
        assertOutboxSent(notificationId)
    }

    @Test
    fun `a replay with the same deduplication key publishes no second outcome`() {
        val partyId = UUID.randomUUID()
        val deduplicationKey = UUID.randomUUID()
        val first = request(partyId, UUID.randomUUID(), deduplicationKey)
        sendRequest(first)
        val firstRecord = awaitOutcome(first.correlationId!!)
        val firstBody = mapper.readTree(firstRecord.value())
        assertThat(firstBody["partyId"].asText()).isEqualTo(partyId.toString())

        // The first fact is terminal now; the replay must be a quiet no-op.
        sendRequest(request(partyId, UUID.randomUUID(), deduplicationKey))

        Thread.sleep(4_000)
        val observed = observed().filter { record ->
            header(record, "ce-type") == NotificationOutcomeEvent.EVENT_TYPE &&
                runCatching {
                    mapper.readTree(record.value())["partyId"].asText() == partyId.toString()
                }.getOrDefault(false)
        }
        assertThat(observed)
            .describedAs("a deduplicated replay must not emit a second outcome for the same fact")
            .hasSize(1)
    }

    // ── request producer ─────────────────────────────────────────────────────────

    private fun request(partyId: UUID, correlationId: UUID, deduplicationKey: UUID) = NotificationRequest(
        partyId = partyId,
        channel = NotificationChannel.PUSH,
        template = NotificationTemplate.ACCOUNT_FROZEN,
        recipient = "wire-dedup@example.com",
        variables = mapOf("accountNumber" to "synthetic-account-token", "reason" to "wire probe"),
        correlationId = correlationId,
        deduplicationKey = deduplicationKey,
    )

    private fun sendRequest(request: NotificationRequest) {
        val props = mapOf(
            "bootstrap.servers" to bootstrapServers(),
            "key.serializer" to StringSerializer::class.qualifiedName,
            "value.serializer" to StringSerializer::class.qualifiedName,
            "acks" to "1",
        )
        KafkaProducer<String, String>(props).use { producer ->
            producer.send(ProducerRecord(REQUESTS_TOPIC, mapper.writeValueAsString(request))).get(20, TimeUnit.SECONDS)
            producer.flush()
        }
    }

    // ── wire read-back ───────────────────────────────────────────────────────────

    private fun awaitOutcome(correlationId: UUID): ConsumerRecord<String, String> {
        val deadline = Instant.now().plusSeconds(60)
        while (Instant.now().isBefore(deadline)) {
            val found = observed().firstOrNull { record ->
                header(record, "ce-type") == NotificationOutcomeEvent.EVENT_TYPE &&
                    runCatching {
                        mapper.readTree(record.value())["correlationId"]?.asText() == correlationId.toString()
                    }.getOrDefault(false)
            }
            if (found != null) return found
            Thread.sleep(250)
        }
        throw AssertionError("no NotificationOutcome event for correlation $correlationId within 60s")
    }

    private fun observed(): List<ConsumerRecord<String, String>> = OBSERVED

    private fun header(record: ConsumerRecord<String, String>, name: String): String? =
        record.headers().lastHeader(name)?.value()?.toString(Charsets.UTF_8)

    // ── outbox table read-back ───────────────────────────────────────────────────

    private fun assertOutboxSent(aggregateId: String) {
        val deadline = Instant.now().plusSeconds(15)
        while (Instant.now().isBefore(deadline)) {
            val status = outboxStatus(aggregateId)
            if (status == "SENT") return
            Thread.sleep(200)
        }
        throw AssertionError("notification_outbox row for $aggregateId never reached SENT")
    }

    private fun outboxStatus(id: String): String? = dataSource.connection.use { queryStatus(it, id) }

    private fun queryStatus(c: java.sql.Connection, aggregateId: String): String? {
        val ps = c.prepareStatement(
            "select status from notification_outbox where aggregate_id = ? and event_type = ?",
        )
        ps.use {
            it.setObject(1, UUID.fromString(aggregateId))
            it.setString(2, NotificationOutcomeEvent.EVENT_TYPE)
            val rs = it.executeQuery()
            rs.use { r -> return if (r.next()) r.getString(1) else null }
        }
    }

    class WireProfile : QuarkusTestProfile {
        override fun disableGlobalTestResources() = true

        override fun testResources() = listOf(
            TestResourceEntry(
                PostgresRedpandaRedisTestResource::class.java,
                mapOf("db" to "openbank_notification_it"),
            ),
        )

        override fun getConfigOverrides(): Map<String, String> = mapOf(
            // Restart-safe ephemeral port for this profile's own Quarkus boot.
            "quarkus.http.test-port" to "0",
            // Dispatch stays on so the row reaches the broker; the tick is shortened so the wire
            // assertion does not wait five seconds per event.
            "openbank.outbox.dispatch-enabled" to "true",
            "openbank.outbox.poll-interval" to "1s",
            "openbank.outbox.initial-delay" to "1s",
            // The two switches the earlier wire suites measured as required for @Scheduled
            // Panache work: without them the reactive SessionFactory lazy synthetic bean cannot
            // be created outside an HTTP request.
            "quarkus.hibernate-orm.enabled" to "true",
            "quarkus.datasource.jdbc.enabled" to "true",
            // %test disables the scheduler so the consumer IT never races an unrelated
            // background dispatch — this suite is the one that needs the real @Scheduled
            // dispatcher.
            "quarkus.scheduler.enabled" to "true",
            // Suite-order poisoning guard: the module's IT suite switches both channels to
            // in-memory and only clear()s messages, not the switch, on stop. In a full-module
            // Gradle run that switch survives the profile restart into this suite, so both
            // connectors are pinned back to smallrye-kafka.
            "mp.messaging.incoming.notification-events-in.connector" to "smallrye-kafka",
            "mp.messaging.outgoing.notification-events-out.connector" to "smallrye-kafka",
            // The connector default is `latest`: a request produced before the service's consumer
            // group has finished its first rebalance is silently skipped (measured — the first
            // test's request never arrived while a later one did). A fresh container topic makes
            // earliest safe: the suite must see every request it produces.
            "mp.messaging.incoming.notification-events-in.auto.offset.reset" to "earliest",
        )
    }

    companion object {
        private const val REQUESTS_TOPIC = "openbank.notification.requests"
        private const val OUTCOMES_TOPIC = "openbank.notification.outcomes.v1"

        private val OBSERVED = CopyOnWriteArrayList<ConsumerRecord<String, String>>()

        @BeforeAll
        @JvmStatic
        fun wireTap() {
            OBSERVED.clear()
            ensureTopic(OUTCOMES_TOPIC)
            val props = mapOf(
                "bootstrap.servers" to bootstrapServers(),
                "group.id" to "notification-outcomes-wire-read-${UUID.randomUUID()}",
                "auto.offset.reset" to "earliest",
                "enable.auto.commit" to "true",
                "key.deserializer" to StringDeserializer::class.qualifiedName,
                "value.deserializer" to StringDeserializer::class.qualifiedName,
            )
            val consumer = KafkaConsumer<String, String>(props)
            consumer.subscribe(listOf(OUTCOMES_TOPIC))
            Thread {
                while (true) {
                    consumer.poll(Duration.ofMillis(500)).forEach { OBSERVED.add(it) }
                }
            }.apply {
                isDaemon = true
                name = "notification-outcomes-wire-tap"
                start()
            }
        }

        private fun ensureTopic(topic: String) {
            // Under full-fork load the broker container reports "started" before its Kafka API
            // answers (measured on the sepa-payment suite: a bare 30 s createTopics timed out
            // against a live broker next to a 52 s Quarkus boot). Retry with fresh clients.
            val deadline = Instant.now().plusSeconds(120)
            while (true) {
                val outcome = runCatching { createTopicOnce(topic) }
                if (outcome.isSuccess) return
                if (Instant.now().isAfter(deadline)) {
                    throw AssertionError(
                        "broker never accepted topic creation within 120s",
                        outcome.exceptionOrNull(),
                    )
                }
                Thread.sleep(2_000)
            }
        }

        private fun createTopicOnce(topic: String) {
            Admin.create(mapOf("bootstrap.servers" to bootstrapServers())).use { admin ->
                try {
                    admin.createTopics(listOf(NewTopic(topic, 3, 1))).all().get(20, TimeUnit.SECONDS)
                } catch (e: ExecutionException) {
                    if (e.cause !is TopicExistsException) throw e
                }
            }
        }

        private fun bootstrapServers(): String =
            ConfigProvider.getConfig().getValue("kafka.bootstrap.servers", String::class.java)
    }
}
