// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.e2e

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.testing.evidence.TestInfrastructureEvidence
import com.openbank.sepa.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.QuarkusTestProfile.TestResourceEntry
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.errors.TopicExistsException
import org.apache.kafka.common.serialization.StringDeserializer
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
 * Real-broker wire E2E for the sepa-payment outbox relay (`events-out` →
 * `openbank.sepa.payment.events`). Until this suite the producer side was proven only up to the
 * InMemoryConnector sink: `SepaPaymentJourneyE2E` switches the channel to in-memory and the
 * journey stops at durable local state. Here the whole loop is real: the payment is submitted
 * over the real HTTP surface, the use case writes the `sepa_payment_outbox` row, the real
 * `@Scheduled` dispatcher drains it, and the topic is read back with a fresh consumer group.
 *
 * What is real and what is stood in for: Postgres (full Flyway chain) and Valkey run via
 * `PostgresRedisTestResource`; the idempotency store, the use case, the outbox write, the
 * dispatcher and the emitter all run for real against an isolated Redpanda.
 *
 * Transport contract, measured against the shared OutboxKafkaHeaders: key == paymentId
 * (partitionKey = aggregateId), `ce-type` = "sepa.payment.created" / "sepa.payment.status-changed"
 * (the outbox event_type), `ce-id` present and `idempotency-key` == ce-id, so an at-least-once
 * consumer can dedupe (ADR-0050 N3). Payloads are the SepaPaymentCreatedEvent /
 * SepaPaymentStatusChangedEvent shapes, including the `sourceService: "sepa-payment"` producer
 * attribution audit-service reads as the strongest claim (#3994/#5256).
 *
 * Boot hazards pinned in the profile, each measured while building this suite (the notification
 * wire suite found the same classes of defect first):
 * - the module's other suites switch `events-out` to the in-memory connector and only
 *   `clear()` on stop (messages, not the switch) — in a full-module Gradle run the switch
 *   survives the profile restart into this suite, so the connector is pinned back to
 *   smallrye-kafka;
 * - the @Scheduled dispatcher touches Panache from the scheduler thread; without
 *   `quarkus.hibernate-orm.enabled` / `quarkus.datasource.jdbc.enabled` the reactive
 *   SessionFactory lazy synthetic bean cannot be created in that context;
 * - the outbox tick is shortened so the wire assertion does not wait five seconds per event.
 */
@QuarkusTest
@TestProfile(SepaPaymentEventsWireE2E.WireProfile::class)
class SepaPaymentEventsWireE2E {

    @Inject
    lateinit var dataSource: DataSource

    private val mapper = ObjectMapper()

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_PAYMENTS"])
    fun `a submitted payment lands its created event on the wire`() {
        val idempotencyKey = "wire-created-${UUID.randomUUID()}"
        val endToEndId = "WIRE-CREATED-${UUID.randomUUID()}"
        val paymentId = submit(idempotencyKey, endToEndId)

        val record = awaitWire(CREATED_EVENT, paymentId)
        val body = mapper.readTree(record.value())
        assertThat(body["paymentId"].asText()).isEqualTo(paymentId)
        assertThat(body["idempotencyKey"].asText()).isEqualTo(idempotencyKey)
        assertThat(body["type"].asText()).isEqualTo("SCT")
        assertThat(body["status"].asText()).isEqualTo("RECEIVED")
        assertThat(body["currency"].asText()).isEqualTo("EUR")
        assertThat(body["endToEndId"].asText()).isEqualTo(endToEndId)
        assertThat(body["sourceService"].asText())
            .describedAs("producer attribution audit-service reads as the strongest claim (#3994/#5256)")
            .isEqualTo("sepa-payment")
        Instant.parse(body["occurredAt"].asText())

        // The transport contract: key IS the payment id, CloudEvents headers present.
        assertThat(record.topic()).isEqualTo(EVENTS_TOPIC)
        assertThat(record.key()).isEqualTo(paymentId)
        assertThat(header(record, "ce-type")).isEqualTo(CREATED_EVENT)
        val eventId = header(record, "ce-id")
        assertThat(eventId).isNotBlank()
        assertThat(header(record, "idempotency-key"))
            .describedAs("the idempotency key is the event id, so at-least-once consumers can dedupe (ADR-0050 N3)")
            .isEqualTo(eventId)

        // The relay marked the row SENT — the durable hand-off boundary the dispatcher owns.
        assertOutboxSent(paymentId, CREATED_EVENT)
    }

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_PAYMENTS"])
    fun `status transitions land the complete status-changed chain on the wire`() {
        val paymentId = submit("wire-status-${UUID.randomUUID()}", "WIRE-STATUS-${UUID.randomUUID()}")
        awaitWire(CREATED_EVENT, paymentId)

        transition(paymentId, "VALIDATED")
        transition(paymentId, "PROCESSING")
        transition(paymentId, "COMPLETED")

        // The chain must be complete: every hop carries its previous status, so a skipped
        // transition is visible as a missing hop. Ordering is deliberately NOT asserted — the
        // v2 outbox loop dispatches the batch concurrently (ADR-0327 D6 SEND_CONCURRENCY), and
        // the measured wire shows PROCESSING→COMPLETED landing before RECEIVED→VALIDATED.
        // Consumers must chain by version/previousStatus, not by arrival order.
        val seen = CopyOnWriteArrayList<Pair<String, String>>()
        val deadline = Instant.now().plusSeconds(45)
        val expected = listOf(
            "RECEIVED" to "VALIDATED",
            "VALIDATED" to "PROCESSING",
            "PROCESSING" to "COMPLETED",
        )
        while (Instant.now().isBefore(deadline) && seen.size < expected.size) {
            observed()
                .filter { header(it, "ce-type") == STATUS_CHANGED_EVENT }
                .mapNotNull { parse(it) }
                .filter { it["paymentId"]?.asText() == paymentId }
                .forEach { body ->
                    val hop = body["previousStatus"].asText() to body["newStatus"].asText()
                    if (hop !in seen) seen.add(hop)
                }
            Thread.sleep(200)
        }
        assertThat(seen.toList())
            .describedAs(
                "the status chain on the wire must contain exactly the three hops " +
                    "RECEIVED→VALIDATED→PROCESSING→COMPLETED",
            )
            .containsExactlyInAnyOrderElementsOf(expected)
        val completed = observed()
            .filter { header(it, "ce-type") == STATUS_CHANGED_EVENT }
            .mapNotNull { parse(it) }
            .first { it["paymentId"]?.asText() == paymentId && it["newStatus"]?.asText() == "COMPLETED" }
        assertThat(completed["rejectReason"].isNull).isTrue()
        assertThat(completed["sourceService"].asText()).isEqualTo("sepa-payment")
    }

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_PAYMENTS"])
    fun `an idempotency replay does not duplicate the created event on the wire`() {
        val idempotencyKey = "wire-replay-${UUID.randomUUID()}"
        val endToEndId = "WIRE-REPLAY-${UUID.randomUUID()}"
        // One body instance: requestBody() mints a fresh debtorAccountId per call, and a replay
        // with any byte different is a 409 key-reuse, not a replay.
        val body = requestBody(endToEndId)
        val paymentId = submitBody(idempotencyKey, body)
        awaitWire(CREATED_EVENT, paymentId)

        // A client that lost the response resubmits the same key and body — the stored answer
        // comes back, and the wire must still hold exactly one created event for this payment.
        val replayed = submitRaw(idempotencyKey, body)
        assertThat(replayed.statusCode).isEqualTo(201)
        assertThat(replayed.header("X-Idempotency-Replayed")).isEqualTo("true")
        assertThat(replayed.jsonPath().getString("id")).isEqualTo(paymentId)

        Thread.sleep(4_000)
        val created = observed().filter { record ->
            header(record, "ce-type") == CREATED_EVENT &&
                parse(record)?.get("paymentId")?.asText() == paymentId
        }
        assertThat(created)
            .describedAs("a deduplicated replay must not emit a second created event")
            .hasSize(1)
    }

    // ── HTTP driving ─────────────────────────────────────────────────────────────

    private fun submit(idempotencyKey: String, endToEndId: String): String =
        submitBody(idempotencyKey, requestBody(endToEndId))

    private fun submitBody(idempotencyKey: String, body: String): String {
        val response = submitRaw(idempotencyKey, body)
        assertThat(response.statusCode)
            .describedAs("POST /api/v1/sepa-payments: %s", response.body.asString())
            .isEqualTo(201)
        val paymentId = response.jsonPath().getString("id")
        assertThat(response.header("X-Idempotency-Replayed")).isNull()
        return paymentId
    }

    private fun submitRaw(idempotencyKey: String, body: String) = RestAssured.given()
        .contentType(ContentType.JSON)
        .header("Idempotency-Key", idempotencyKey)
        .body(body)
        .post("/api/v1/sepa-payments")
        .then()
        .extract()
        .response()

    private fun transition(paymentId: String, targetStatus: String) {
        val response = RestAssured.given()
            .contentType(ContentType.JSON)
            .body("""{"targetStatus": "$targetStatus"}""")
            .patch("/api/v1/sepa-payments/$paymentId/status")
            .then()
            .extract()
            .response()
        assertThat(response.statusCode)
            .describedAs("PATCH status → $targetStatus: %s", response.body.asString())
            .isEqualTo(200)
    }

    private fun requestBody(endToEndId: String): String =
        """
        {
          "type": "SCT",
          "debtorAccountId": "${UUID.randomUUID()}",
          "debtorIban": "$DEBTOR_IBAN",
          "debtorName": "Wire Debtor s.r.o.",
          "creditorIban": "$CREDITOR_IBAN",
          "creditorName": "Wire Creditor GmbH",
          "creditorBic": null,
          "amount": 250.00,
          "currency": "EUR",
          "remittanceInfo": "wire E2E remittance",
          "endToEndId": "$endToEndId"
        }
        """.trimIndent()

    // ── wire read-back ───────────────────────────────────────────────────────────

    private fun awaitWire(eventType: String, paymentId: String): ConsumerRecord<String, String> {
        val deadline = Instant.now().plusSeconds(45)
        while (Instant.now().isBefore(deadline)) {
            val found = observed().firstOrNull { record ->
                header(record, "ce-type") == eventType &&
                    parse(record)?.get("paymentId")?.asText() == paymentId
            }
            if (found != null) return found
            Thread.sleep(200)
        }
        val seen = observed().joinToString(", ") { "${it.key()}/${header(it, "ce-type")}" }
        throw AssertionError("no $eventType for $paymentId on the wire within 45s; observed: [$seen]")
    }

    private fun parse(record: ConsumerRecord<String, String>): com.fasterxml.jackson.databind.JsonNode? =
        runCatching { mapper.readTree(record.value()) }.getOrNull()

    private fun observed(): List<ConsumerRecord<String, String>> = OBSERVED

    private fun header(record: ConsumerRecord<String, String>, name: String): String? =
        record.headers().lastHeader(name)?.value()?.toString(Charsets.UTF_8)

    // ── outbox table read-back ───────────────────────────────────────────────────

    private fun assertOutboxSent(aggregateId: String, eventType: String) {
        val deadline = Instant.now().plusSeconds(15)
        while (Instant.now().isBefore(deadline)) {
            val status = outboxStatus(aggregateId, eventType)
            if (status == "SENT") return
            Thread.sleep(200)
        }
        throw AssertionError("sepa_payment_outbox row for $aggregateId/$eventType never reached SENT")
    }

    private fun outboxStatus(aggregateId: String, eventType: String): String? =
        dataSource.connection.use { queryStatus(it, aggregateId, eventType) }

    private fun queryStatus(c: java.sql.Connection, aggregateId: String, eventType: String): String? {
        val ps = c.prepareStatement(
            "select status from sepa_payment_outbox where aggregate_id = ? and event_type = ?",
        )
        ps.use {
            it.setObject(1, UUID.fromString(aggregateId))
            it.setString(2, eventType)
            val rs = it.executeQuery()
            rs.use { r -> return if (r.next()) r.getString(1) else null }
        }
    }

    class WireProfile : QuarkusTestProfile {
        override fun disableGlobalTestResources() = true

        override fun testResources() = listOf(
            TestResourceEntry(PostgresRedisTestResource::class.java),
            TestResourceEntry(SepaWireRedpandaResource::class.java),
        )

        override fun getConfigOverrides(): Map<String, String> = mapOf(
            // Restart-safe ephemeral port for this profile's own Quarkus boot.
            "quarkus.http.test-port" to "0",
            // The dispatcher is a real @Scheduled tick in this module; shorten it so the wire
            // assertion does not wait five seconds per event.
            "quarkus.scheduler.enabled" to "true",
            "openbank.outbox.poll-interval" to "1s",
            "openbank.outbox.initial-delay" to "1s",
            // The two switches the notification wire suite measured as required for @Scheduled
            // Panache work: without them the reactive SessionFactory lazy synthetic bean cannot
            // be created outside an HTTP request.
            "quarkus.hibernate-orm.enabled" to "true",
            "quarkus.datasource.jdbc.enabled" to "true",
            // Suite-order poisoning guard: SepaPaymentJourneyE2E switches events-out to the
            // in-memory connector and only clear()s on stop (messages, not the switch). In a
            // full-module Gradle run that switch survives the profile restart into this suite,
            // so the connector is pinned back to smallrye-kafka.
            "mp.messaging.outgoing.events-out.connector" to "smallrye-kafka",
        )
    }

    companion object {
        private const val EVENTS_TOPIC = "openbank.sepa.payment.events"
        private const val CREATED_EVENT = "sepa.payment.created"
        private const val STATUS_CHANGED_EVENT = "sepa.payment.status-changed"
        private const val ACTOR = "wire-operator-1"
        private const val DEBTOR_IBAN = "DE89370400440532013000"
        private const val CREDITOR_IBAN = "FR1420041010050500013M02606"

        private val OBSERVED = CopyOnWriteArrayList<ConsumerRecord<String, String>>()

        @BeforeAll
        @JvmStatic
        fun wireTap() {
            OBSERVED.clear()
            ensureTopic(EVENTS_TOPIC)
            val props = mapOf(
                "bootstrap.servers" to bootstrapServers(),
                "group.id" to "sepa-payment-wire-read-${UUID.randomUUID()}",
                "auto.offset.reset" to "earliest",
                "enable.auto.commit" to "true",
                "key.deserializer" to StringDeserializer::class.qualifiedName,
                "value.deserializer" to StringDeserializer::class.qualifiedName,
            )
            val consumer = KafkaConsumer<String, String>(props)
            consumer.subscribe(listOf(EVENTS_TOPIC))
            Thread {
                while (true) {
                    consumer.poll(Duration.ofMillis(500)).forEach { OBSERVED.add(it) }
                }
            }.apply {
                isDaemon = true
                name = "sepa-payment-wire-tap"
                start()
            }
        }

        private fun ensureTopic(topic: String) {
            // In a full-module Gradle run the broker container reports "started" while its
            // Kafka API is still coming up under the load of the previous suites' containers
            // (measured: a 52 s Quarkus boot in the same JVM, then a bare 30 s createTopics
            // timeout against a live broker). Retry with fresh clients until the API answers.
            val deadline = Instant.now().plusSeconds(120)
            while (true) {
                val outcome = runCatching { createTopicOnce(topic) }
                if (outcome.isSuccess) return
                if (Instant.now().isAfter(deadline)) {
                    throw AssertionError("broker never accepted topic creation within 120s", outcome.exceptionOrNull())
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

/**
 * Isolated Redpanda (Kafka API) for the wire suite: the real outbox relay publishes
 * `openbank.sepa.payment.events` to it. Returning the bootstrap servers as config properties
 * (not YAML) is the #686-safe idiom the fleet's other broker resources use.
 */
class SepaWireRedpandaResource : QuarkusTestResourceLifecycleManager {

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
