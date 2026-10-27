// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.e2e

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.testing.containers.PostgresRedpandaRedisTestResource
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
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * Real-broker wire E2E for the SWIFT outbox relay (`swift-events-out` →
 * `openbank.payments.swift.event`). The sibling suites stop at durable local state:
 * `SwiftLifecycleE2E`, `SwiftOutboxAtomicityIT`, `SwiftOperatorTransitionOutboxIT` and
 * `SwiftOutboxDispatchConformanceIT` all switch the channel to the in-memory connector, so the
 * `swift_outbox` rows were asserted in Postgres but nothing ever reached a broker. Here the whole
 * loop is real: the message is sent and operator-transitioned over the real HTTP surface, the
 * transition writes its outbox row in the same transaction (#8718), the real `@Scheduled`
 * dispatcher drains it to a Redpanda container, and a fresh consumer group reads the record back.
 *
 * Transport contract, measured against the shared OutboxKafkaHeaders: key == swiftMessageId,
 * `ce-type` = `swift.message.status-changed` (the dotted-lowercase outbox event type column —
 * unlike clearing, swift does NOT duplicate `eventType` into the body; the header is the only
 * type discriminator), `ce-id` present and `idempotency-key` == ce-id, so an at-least-once
 * consumer can dedupe (ADR-0050 N3).
 *
 * Payload contract: the eight-key envelope `SwiftService.statusChangedEvent` builds —
 * `sourceService` ("swift-service", the producer attribution audit-service reads as the
 * strongest claim, #3994/#5256), `swiftMessageId`, `paymentSagaRef`, `status`, `messageType`,
 * `amount`, `currency`, `occurredAt` — and the suite pins what is deliberately NOT on the wire:
 * `ackRef` and `rejectionReason` stay out of the payload (#8718 fixed the missing OCCURRENCES of
 * an already-published event, not by widening it), so a free-text reject reason cannot leak
 * downstream of the broker.
 *
 * Boot hazards pinned in the profile, each measured on the earlier wire suites (notification,
 * sepa-payment, sepa-instant, domestic-payment, clearing-service): the hibernate-orm/jdbc
 * switches for @Scheduled Panache work, a 1 s outbox tick, and the `swift-events-out` connector
 * pinned back to smallrye-kafka because the sibling suites switch it to in-memory and only
 * clear() messages, not the switch, on stop.
 */
@QuarkusTest
@TestProfile(SwiftEventsWireE2E.WireProfile::class)
class SwiftEventsWireE2E {

    @Inject
    lateinit var dataSource: DataSource

    private val mapper = ObjectMapper()

    @Test
    @TestSecurity(user = "wire-operator-1", roles = ["ROLE_OPERATOR"])
    fun `an ack lands the status changed event on the wire`() {
        val id = send(requestBody("wire-ack-${UUID.randomUUID()}", UUID.randomUUID().toString().take(12), 12500))

        val ack = RestAssured.given()
            .contentType(ContentType.JSON)
            .body("""{"ackRef": "WIRE-ACK-1"}""")
            .post("/api/v1/swift/$id/ack")
            .then()
            .extract()
            .response()
        assertThat(ack.statusCode)
            .describedAs("POST /api/v1/swift/$id/ack: %s", ack.body.asString())
            .isEqualTo(200)

        val record = awaitWire(id)
        assertThat(record.topic()).isEqualTo(EVENTS_TOPIC)
        assertThat(record.key()).isEqualTo(id)
        val eventId = header(record, "ce-id")
        assertThat(eventId).isNotBlank()
        assertThat(header(record, "ce-type")).isEqualTo(STATUS_CHANGED_EVENT)
        assertThat(header(record, "idempotency-key"))
            .describedAs("the idempotency key is the event id, so at-least-once consumers can dedupe (ADR-0050 N3)")
            .isEqualTo(eventId)

        val body = mapper.readTree(record.value())
        assertThat(body.fieldNames().asSequence().toList())
            .describedAs("the eight-key SwiftMessageEventPayload envelope (docs/asyncapi/openbank-events.yaml)")
            .containsExactlyInAnyOrder(
                "sourceService",
                "swiftMessageId",
                "paymentSagaRef",
                "status",
                "messageType",
                "amount",
                "currency",
                "occurredAt",
            )
        assertThat(body["sourceService"].asText())
            .describedAs("producer attribution audit-service reads as the strongest claim (#3994/#5256)")
            .isEqualTo("swift-service")
        assertThat(body["swiftMessageId"].asText()).isEqualTo(id)
        assertThat(body["status"].asText()).isEqualTo("ACKNOWLEDGED")
        assertThat(body["messageType"].asText()).isEqualTo("MT103")
        assertThat(body["amount"].decimalValue()).isEqualByComparingTo("125")
        assertThat(body["currency"].asText()).isEqualTo("EUR")
        // The ack reference is deliberately NOT on the wire (#8718) — the enum value alone
        // carries the transition, so no operator input leaks downstream of the broker.
        assertThat(body.has("ackRef")).isFalse()

        // The relay marked the row SENT — the durable hand-off boundary the dispatcher owns.
        assertOutboxSent(id)
    }

    @Test
    @TestSecurity(user = "wire-operator-2", roles = ["ROLE_OPERATOR"])
    fun `a reject lands the status changed event without the free text reason`() {
        val id = send(requestBody("wire-rej-${UUID.randomUUID()}", UUID.randomUUID().toString().take(12), 2000))

        val reject = RestAssured.given()
            .contentType(ContentType.JSON)
            .body("""{"reason": "sanctions hold wire"}""")
            .post("/api/v1/swift/$id/reject")
            .then()
            .extract()
            .response()
        assertThat(reject.statusCode)
            .describedAs("POST /api/v1/swift/$id/reject: %s", reject.body.asString())
            .isEqualTo(200)

        val record = awaitWire(id)
        val body = mapper.readTree(record.value())
        assertThat(header(record, "ce-type")).isEqualTo(STATUS_CHANGED_EVENT)
        assertThat(body["status"].asText()).isEqualTo("REJECTED")
        // The free-text reject reason is deliberately NOT in the wire envelope — it stays in the
        // durable row, so a sanctions narrative cannot leak downstream of the broker.
        assertThat(body.has("rejectionReason")).isFalse()
        assertOutboxSent(id)
    }

    @Test
    @TestSecurity(user = "wire-operator-3", roles = ["ROLE_OPERATOR"])
    fun `an idempotent replay does not duplicate the status changed event on the wire`() {
        val key = "wire-replay-${UUID.randomUUID()}"
        val ref = UUID.randomUUID().toString().take(12)
        val id = send(requestBody(key, ref, 5000))

        // A client that lost the response resubmits under the same key: the stored message comes
        // back, and the wire must still hold exactly one event once the ack lands.
        val replay = send(requestBody(key, ref, 99999))
        assertThat(replay).isEqualTo(id)

        RestAssured.given()
            .contentType(ContentType.JSON)
            .body("""{"ackRef": "WIRE-ACK-REPLAY"}""")
            .post("/api/v1/swift/$id/ack")
            .then()
            .statusCode(200)
        awaitWire(id)

        Thread.sleep(4_000)
        val observed = observed().filter { record -> record.key() == id }
        assertThat(observed)
            .describedAs("a deduplicated replay must not emit a second status-changed event")
            .hasSize(1)
    }

    // ── HTTP driving ─────────────────────────────────────────────────────────────

    private fun send(body: String): String {
        val response = RestAssured.given()
            .contentType(ContentType.JSON)
            .body(body)
            .post("/api/v1/swift")
            .then()
            .extract()
            .response()
        assertThat(response.statusCode)
            .describedAs("POST /api/v1/swift: %s", response.body.asString())
            .isEqualTo(201)
        return response.jsonPath().getString("id")
    }

    private fun requestBody(key: String, ref: String, amountMinorUnits: Int): String =
        """
        {
          "idempotencyKey": "$key",
          "messageType": "MT103",
          "senderBic": "OPBKCZPP",
          "receiverBic": "DEUTDEFF",
          "transactionReference": "$ref",
          "relatedReference": null,
          "valueDate": "20261020",
          "currency": "EUR",
          "amountMinorUnits": $amountMinorUnits,
          "orderingCustomerAccount": "DE89370400440532013000",
          "orderingCustomerAccountId": null,
          "orderingCustomerName": "Wire Ordering",
          "beneficiaryAccount": "GB33BUKB20201555555555",
          "beneficiaryName": "Wire Beneficiary",
          "remittanceInfo": "wire event",
          "chargeCode": "SHA",
          "priority": "NORMAL"
        }
        """.trimIndent()

    // ── wire read-back ───────────────────────────────────────────────────────────

    private fun awaitWire(messageId: String): ConsumerRecord<String, String> {
        val deadline = Instant.now().plusSeconds(45)
        while (Instant.now().isBefore(deadline)) {
            val found = observed().firstOrNull { record -> record.key() == messageId }
            if (found != null) return found
            Thread.sleep(200)
        }
        val seen = observed().joinToString(", ") { "${it.key()}/${header(it, "ce-type")}" }
        throw AssertionError("no status-changed event for $messageId on the wire within 45s; observed: [$seen]")
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
        throw AssertionError("swift_outbox row for $aggregateId never reached SENT")
    }

    private fun outboxStatus(id: String): String? = dataSource.connection.use { queryStatus(it, id) }

    private fun queryStatus(c: java.sql.Connection, aggregateId: String): String? {
        val ps = c.prepareStatement(
            "select status from swift_outbox where aggregate_id = ? and event_type = ?",
        )
        ps.use {
            it.setObject(1, UUID.fromString(aggregateId))
            it.setString(2, STATUS_CHANGED_EVENT)
            val rs = it.executeQuery()
            rs.use { r -> return if (r.next()) r.getString(1) else null }
        }
    }

    class WireProfile : QuarkusTestProfile {
        override fun disableGlobalTestResources() = true

        override fun testResources() = listOf(
            TestResourceEntry(
                PostgresRedpandaRedisTestResource::class.java,
                mapOf("db" to "openbank_swift_it"),
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
            // Suite-order poisoning guard: SwiftLifecycleE2E, SwiftOutboxAtomicityIT,
            // SwiftOperatorTransitionOutboxIT and SwiftOutboxDispatchConformanceIT switch
            // swift-events-out to the in-memory connector and only clear() messages, not the
            // switch, on stop. In a full-module Gradle run that switch survives the profile
            // restart into this suite, so the connector is pinned back to smallrye-kafka.
            "mp.messaging.outgoing.swift-events-out.connector" to "smallrye-kafka",
        )
    }

    companion object {
        private const val EVENTS_TOPIC = "openbank.payments.swift.event"
        private const val STATUS_CHANGED_EVENT = "swift.message.status-changed"

        private val OBSERVED = CopyOnWriteArrayList<ConsumerRecord<String, String>>()

        @BeforeAll
        @JvmStatic
        fun wireTap() {
            OBSERVED.clear()
            ensureTopic(EVENTS_TOPIC)
            val props = mapOf(
                "bootstrap.servers" to bootstrapServers(),
                "group.id" to "swift-wire-read-${UUID.randomUUID()}",
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
                name = "swift-wire-tap"
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
