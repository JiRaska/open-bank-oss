// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.e2e

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.testing.evidence.TestInfrastructureEvidence
import com.openbank.notification.domain.model.NotificationOutcomeEvent
import com.openbank.notification.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
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
import org.opentest4j.TestAbortedException
import org.testcontainers.DockerClientFactory
import org.testcontainers.redpanda.RedpandaContainer
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * Real-broker wire E2E for the notification delivery-outcome relay (`notification-events-out` →
 * `openbank.notification.outcomes.v1`, ADR-0239 D2 — the fleet's first versioned topic). Until
 * this suite the outcome side was proven only up to the InMemoryConnector sink:
 * NotificationConsumerIT switches both channels to in-memory and reads the outcome out of the
 * sink, while NotificationOutboxWriteIT stops at the `notification_outbox` table. Here the whole
 * loop is real: the test publishes a NotificationRequest onto `openbank.notification.requests`
 * with a plain Kafka producer, the real NotificationConsumer processes it (Postgres +
 * transactional outbox), the real dispatcher ticks, and the topic is read back with a fresh
 * consumer group.
 *
 * What is real and what is stood in for: Postgres, the consumer, the consent/preference gates,
 * the outbox write and the dispatcher all run for real. The mailer is the %test-profile mock
 * (`quarkus.mailer.mock=true`), which is itself load-bearing evidence: an EMAIL request through
 * the mocked mailer is SUPPRESSED with reason `mailer_mocked`, never SENT — the "successful
 * no-op must not share a boolean with success" rule from ADR-0252 phase 0 stated as a wire
 * assertion. A PUSH request for a party with no registered device keeps its original row
 * `FAILED` but, with the #4363 e-mail fallback policy switched on, the wire carries the precise
 * `REROUTED` outcome (reason `rerouted_no_active_device`) atomically with a durable generic
 * EMAIL fallback request (#7463) — the fallback goes through the mocked mailer and suppresses,
 * so one PUSH request yields two outcome events on the wire, one correlation id.
 *
 * Transport contract, measured against the shared OutboxKafkaHeaders: key == notificationId,
 * `ce-type` = "NotificationOutcome" (one event type for every outcome — consumers discriminate
 * by the payload's `outcome` field, additively), `ce-id` present, `idempotency-key` == ce-id.
 */
@QuarkusTest
@TestProfile(NotificationOutcomesWireE2E.WireProfile::class)
class NotificationOutcomesWireE2E {

    @Inject
    lateinit var dataSource: DataSource

    private val mapper = ObjectMapper()

    @Test
    fun `an email request lands its suppressed outcome on the wire with the producer correlation id`() {
        val partyId = UUID.randomUUID()
        val correlationId = UUID.randomUUID()
        publishRequest(
            partyId,
            """
            {"partyId":"$partyId","channel":"EMAIL","template":"WELCOME","recipient":"wire@example.com",
             "variables":{"name":"Wire"},"correlationId":"$correlationId"}
            """.trimIndent(),
        )

        val record = awaitOutcome { body ->
            body["correlationId"]?.asText() == correlationId.toString()
        }
        val body = mapper.readTree(record.value())
        assertThat(body["outcome"].asText()).isEqualTo("SUPPRESSED")
        assertThat(body["reason"].asText())
            .describedAs("ADR-0252 phase 0: the mocked-mailer no-op must read as SUPPRESSED, never SENT")
            .isEqualTo(NotificationOutcomeEvent.REASON_MAILER_MOCKED)
        assertThat(body["partyId"].asText()).isEqualTo(partyId.toString())
        assertThat(body["channel"].asText()).isEqualTo("EMAIL")
        assertThat(body["template"].asText()).isEqualTo("WELCOME")
        Instant.parse(body["occurredAt"].asText())

        // The transport contract: key IS the notification id, CloudEvents headers present.
        val notificationId = body["notificationId"].asText()
        assertThat(record.topic()).isEqualTo(OUTCOMES_TOPIC)
        assertThat(record.key()).isEqualTo(notificationId)
        assertThat(ceType(record))
            .describedAs("every outcome is ONE event type; consumers discriminate additively by payload")
            .isEqualTo("NotificationOutcome")
        val eventId = header(record, "ce-id")
        assertThat(eventId).isNotBlank()
        assertThat(header(record, "idempotency-key"))
            .describedAs("the idempotency key is the event id, so at-least-once consumers can dedupe")
            .isEqualTo(eventId)

        // The relay marked the row SENT — the durable hand-off boundary the dispatcher owns.
        assertOutboxSent(notificationId)
    }

    @Test
    fun `a push without a device fails on the wire and its email fallback suppresses, dedup stable`() {
        val partyId = UUID.randomUUID()
        val correlationId = UUID.randomUUID()
        val deduplicationKey = UUID.randomUUID()
        // PUSH resolves its destination from the device registry, so `recipient` is carried
        // opaquely — but the #7463 fallback re-sends it as EMAIL, and EMAIL resolves the address
        // verbatim when it already is one (#3581, fail-closed on a party-UUID lookup). An address
        // here therefore proves the fallback really delivers; a bare party UUID would prove the
        // no_deliverable_recipient shape instead (covered by unit tests).
        val request =
            """
            {"partyId":"$partyId","channel":"PUSH","template":"DELEGATION_FIRST_USE",
             "recipient":"wire-push@example.com",
             "variables":{},"correlationId":"$correlationId","deduplicationKey":"$deduplicationKey"}
            """.trimIndent()
        publishRequest(partyId, request)

        val push = awaitOutcome { body ->
            body["correlationId"]?.asText() == correlationId.toString() && body["channel"]?.asText() == "PUSH"
        }
        val pushBody = mapper.readTree(push.value())
        // With the #4363 fallback policy switched ON the original row is persisted as FAILED but
        // the wire carries the precise REROUTED outcome (reason rerouted_no_active_device) — the
        // fallback request commits in the same transaction, so REROUTED on the wire implies a
        // durable fallback row. The fallback-OFF FAILED/no_active_device shape is proven by
        // PushFallbackDefaultOffIT.
        assertThat(pushBody["outcome"].asText()).isEqualTo("REROUTED")
        assertThat(pushBody["reason"].asText()).isEqualTo(NotificationOutcomeEvent.REASON_REROUTED_NO_DEVICE)
        assertThat(push.key()).isEqualTo(pushBody["notificationId"].asText())

        // #7463: the failed PUSH creates a separate generic EMAIL fallback — its own row, its own
        // outcome, the SAME producer correlation id.
        val fallback = awaitOutcome { body ->
            body["correlationId"]?.asText() == correlationId.toString() && body["channel"]?.asText() == "EMAIL"
        }
        val fallbackBody = mapper.readTree(fallback.value())
        assertThat(fallbackBody["outcome"].asText()).isEqualTo("SUPPRESSED")
        assertThat(fallbackBody["reason"].asText()).isEqualTo(NotificationOutcomeEvent.REASON_MAILER_MOCKED)
        assertThat(fallbackBody["notificationId"].asText())
            .describedAs("the fallback is a separate notification with its own id")
            .isNotEqualTo(pushBody["notificationId"].asText())

        // Dedup sentinel: redelivering the same request (same deduplicationKey — what a producer
        // retry does after a lost ack) must produce no further outcome events.
        publishRequest(partyId, request)
        Thread.sleep(4_000)
        val total = observed().filter { record ->
            val body = parse(record)
            body?.get("correlationId")?.asText() == correlationId.toString()
        }
        assertThat(total)
            .describedAs("a deduplicated redelivery must not emit a third outcome")
            .hasSize(2)
    }

    // ── request publishing ───────────────────────────────────────────────────────

    private fun publishRequest(partyId: UUID, json: String) {
        val props = mapOf(
            "bootstrap.servers" to bootstrapServers(),
            "key.serializer" to StringSerializer::class.qualifiedName,
            "value.serializer" to StringSerializer::class.qualifiedName,
            "acks" to "all",
        )
        KafkaProducer<String, String>(props).use { producer ->
            producer.send(ProducerRecord(REQUESTS_TOPIC, partyId.toString(), json)).get(30, TimeUnit.SECONDS)
        }
    }

    // ── wire read-back ───────────────────────────────────────────────────────────

    private fun awaitOutcome(
        match: (com.fasterxml.jackson.databind.JsonNode) -> Boolean,
    ): ConsumerRecord<String, String> {
        val deadline = Instant.now().plusSeconds(45)
        while (Instant.now().isBefore(deadline)) {
            val found = observed().firstOrNull { record ->
                parse(record)?.let(match) == true
            }
            if (found != null) return found
            Thread.sleep(200)
        }
        val seen = observed().joinToString(", ") { "${it.key()}/${parse(it)?.get("outcome")?.asText()}" }
        throw AssertionError("no matching outcome on the wire within 45s; observed: [$seen]")
    }

    private fun parse(record: ConsumerRecord<String, String>): com.fasterxml.jackson.databind.JsonNode? =
        runCatching { mapper.readTree(record.value()) }.getOrNull()

    private fun observed(): List<ConsumerRecord<String, String>> = OBSERVED

    private fun ceType(record: ConsumerRecord<String, String>): String? = header(record, "ce-type")

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

    private fun outboxStatus(aggregateId: String): String? = dataSource.connection.use { queryStatus(it, aggregateId) }

    private fun queryStatus(c: java.sql.Connection, aggregateId: String): String? {
        val ps = c.prepareStatement(
            "select status from notification_outbox where aggregate_id = ? and event_type = ?",
        )
        ps.use {
            it.setObject(1, UUID.fromString(aggregateId))
            it.setString(2, "NotificationOutcome")
            val rs = it.executeQuery()
            rs.use { r -> return if (r.next()) r.getString(1) else null }
        }
    }

    class WireProfile : QuarkusTestProfile {
        override fun disableGlobalTestResources() = true

        // The infra comes through the PROFILE, not class-level @QuarkusTestResource, on purpose:
        // config returned by restrictToAnnotatedClass resources is not augmentation-visible, so
        // Kafka Dev Services launched a second broker and the wire stayed empty (measured in
        // consent-service; this module's suites switch both channels to in-memory and never
        // observe the real relay). Profile test resources are visible when the profile's
        // application is built.
        override fun testResources() = listOf(
            TestResourceEntry(PostgresTestResource::class.java),
            TestResourceEntry(NotificationWireRedpandaResource::class.java),
        )

        override fun getConfigOverrides(): Map<String, String> = mapOf(
            // A profile forces this class its own Quarkus boot; pin an ephemeral port so the
            // restart cannot die on QuarkusBindException against the still-bound 8081.
            "quarkus.http.test-port" to "0",
            // Belt and braces on top of the resource's global kill switch: never let Dev Services
            // stand up a second broker for this boot.
            "quarkus.kafka.devservices.enabled" to "false",
            // The module's %test profile switches the scheduler OFF; the outbox dispatcher never
            // ticks without this override. Dispatch stays on (main's
            // openbank.outbox.dispatch-enabled=true is inherited) but the tick is shortened so
            // the wire assertion does not wait five seconds per event.
            "quarkus.scheduler.enabled" to "true",
            "openbank.outbox.poll-interval" to "1s",
            "openbank.outbox.initial-delay" to "1s",
            // The two switches DeviceTokenSweepCronIT proved this module's scheduler boots need:
            // without them the reactive SessionFactory comes up with no ReactiveConnectionPool
            // service, and every @Scheduled Panache touch dies with
            // UnknownServiceException[ReactiveConnectionPool] (measured on this suite — the
            // dispatcher tick and the gauge refresh both failed that way while HTTP-driven
            // tests in the same boot kept passing).
            "quarkus.hibernate-orm.enabled" to "true",
            "quarkus.datasource.jdbc.enabled" to "true",
            // Suite-order poisoning guard: most of this module's ITs switch both channels to the
            // in-memory connector via InMemoryConnector.switch*ChannelsToInMemory and only call
            // clear() on stop (clear wipes messages, not the connector switch). In a full-module
            // Gradle run those switches survive across the Quarkus profile restart into this
            // suite — measured: no SRMSG Kafka logs at all in this boot, the consumer never saw
            // the published request, and the wire stayed empty. Pinning the connectors back to
            // smallrye-kafka makes the suite independent of the order Gradle picked.
            "mp.messaging.incoming.notification-events-in.connector" to "smallrye-kafka",
            "mp.messaging.outgoing.notification-events-out.connector" to "smallrye-kafka",
            // The app leaves auto.offset.reset to the gitops ConfigMap, so the test default is
            // Kafka's `latest`: a request published before the consumer group finishes its
            // first rebalance (measured at ~50 s on this module's six-channel boot) is skipped
            // and the wire assertion times out on an empty topic. Fresh broker per run, so
            // `earliest` cannot replay anything.
            "mp.messaging.incoming.notification-events-in.auto.offset.reset" to "earliest",
            // #4363: e-mail fallback for a failed PUSH is a reviewed-but-default-OFF policy; the
            // wire suite turns it on deliberately so the #7463 fallback shape (FAILED PUSH plus
            // suppressed generic EMAIL fallback, one correlation id) is proven end to end.
            "openbank.notification.push-fallback.enabled" to "true",
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
                "group.id" to "notification-wire-read-${UUID.randomUUID()}",
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
                name = "notification-wire-tap"
                start()
            }
        }

        private fun ensureTopic(topic: String) {
            Admin.create(mapOf("bootstrap.servers" to bootstrapServers())).use { admin ->
                try {
                    admin.createTopics(listOf(NewTopic(topic, 3, 1))).all().get(30, TimeUnit.SECONDS)
                } catch (e: ExecutionException) {
                    if (e.cause !is TopicExistsException) throw e
                }
            }
        }

        private fun bootstrapServers(): String =
            ConfigProvider.getConfig().getValue("kafka.bootstrap.servers", String::class.java)
    }
}

/**
 * Isolated Redpanda (Kafka API) for the wire suite: the real consumer reads
 * `openbank.notification.requests` from it and the real outbox relay publishes
 * `openbank.notification.outcomes.v1` to it. Returning the bootstrap servers as config
 * properties (not YAML) is the #686-safe idiom the fleet's other broker resources use.
 */
class NotificationWireRedpandaResource : QuarkusTestResourceLifecycleManager {

    private var redpanda: RedpandaContainer? = null

    override fun start(): Map<String, String> {
        if (!DockerClientFactory.instance().isDockerAvailable) {
            throw TestAbortedException("Docker not available — skipping Testcontainers IT")
        }
        val rp = RedpandaContainer(
            DockerImageName.parse(REDPANDA_IMAGE)
                .asCompatibleSubstituteFor("docker.redpanda.com/redpandadata/redpanda"),
        )
        try {
            rp.start()
        } catch (e: Exception) {
            throw TestAbortedException("Redpanda failed to start — skipping IT: ${e.message}", e)
        }
        redpanda = rp
        TestInfrastructureEvidence.record("redpanda", REDPANDA_IMAGE, "started")
        return mapOf(
            "kafka.bootstrap.servers" to rp.bootstrapServers,
            "mp.messaging.connector.smallrye-kafka.bootstrap.servers" to rp.bootstrapServers,
            "quarkus.devservices.enabled" to "false",
        )
    }

    override fun stop() {
        redpanda?.let {
            it.stop()
            TestInfrastructureEvidence.record("redpanda", REDPANDA_IMAGE, "stopped")
        }
    }

    private companion object {
        const val REDPANDA_IMAGE = "redpandadata/redpanda:v24.1.2"
    }
}
