// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clearing.e2e

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
 * Real-broker wire E2E for the clearing settle spine (`clearing-events-out` →
 * `openbank.clearing.batch.event`). The sibling suites stop short of the broker:
 * `ClearingOutboxDispatchConformanceIT` switches the outgoing channel to the in-memory
 * connector, and `ClearingLifecycleE2E` runs the whole journey with dispatch off, so the
 * `clearing_outbox` rows were asserted in Postgres but nothing ever reached a broker. Here the
 * whole loop is real: items are submitted and the batch settled over the real HTTP surface,
 * `#8509`'s single `settleWithEvents` transaction commits the batch + items + outbox rows, the
 * real `@Scheduled` dispatcher drains them to a Redpanda container, and a fresh consumer group
 * reads the records back.
 *
 * Transport contract, measured against the shared OutboxKafkaHeaders: key == aggregate id
 * (the batch id for `batch.settled`/`net_settlement.post`, the item id for `item.cleared`),
 * `ce-type` = the outbox event type column (the dotted-lowercase spelling, also duplicated
 * into the JSON body for AuditConsumer), `ce-id` present and `idempotency-key` == ce-id, so an
 * at-least-once consumer can dedupe (ADR-0050 N3). The v2 outbox loop (ADR-0327 D6) dispatches
 * rows in parallel, so the wire order is NOT asserted — only per-key presence.
 *
 * Payload contract: every body carries `eventType` and `sourceService` ("clearing-service",
 * the producer attribution audit-service reads as the strongest claim, #3994/#5256); the
 * `net_settlement.post` command carries the deterministic `clearing-net-settlement-{batchId}`
 * journal idempotency key (ADR-0281) so a redelivery collapses onto the one booked journal.
 *
 * The second test closes the loop the producer-only suites cannot: the channel
 * `clearing-net-settlement-in` self-consumes the command off this service's own topic
 * (ADR-0281) and posts the journal to ledger-service. No ledger exists in the test JVM, the
 * post fails after `EventRetry`'s three attempts, and the record must land on the EXPLICIT
 * DLQ topic `openbank.dlq.clearing-service.clearing-net-settlement-in` — the dead-letter
 * wiring #5745 demands (nested `dead-letter-queue.topic`, a dotted leaf key would be quoted
 * by SmallRye's YAML source and never read, #686).
 *
 * Boot hazards pinned in the profile, each measured on the earlier wire suites: the
 * hibernate-orm/jdbc switches for @Scheduled Panache work, a 1 s outbox tick, and the
 * `clearing-events-out` connector pinned back to smallrye-kafka because the conformance
 * suite switches it to in-memory and only clear()s messages, not the switch, on stop.
 */
@QuarkusTest
@TestProfile(ClearingEventsWireE2E.WireProfile::class)
class ClearingEventsWireE2E {

    @Inject
    lateinit var dataSource: DataSource

    private val mapper = ObjectMapper()

    @Test
    @TestSecurity(user = "wire-payments-1", roles = ["ROLE_PAYMENTS"])
    fun `settling a batch lands the settled cleared and net settlement events on the wire`() {
        val paymentId1 = UUID.randomUUID()
        val paymentId2 = UUID.randomUUID()
        val item1 = submit(paymentId1, "WIRE-C-001", "100.50")
        val item2 = submit(paymentId2, "WIRE-C-002", "200.25")
        val itemIds = listOf(item1, item2)

        val cycle = triggerCycle()
        val batch = (cycle["batches"] as List<Map<String, Any?>>).single { it["currency"] == "EUR" }
        val batchId = batch["id"] as String

        val settled = settle(batchId)
        assertThat(settled["status"]).isEqualTo("SETTLED")

        // ── batch.settled ──────────────────────────────────────────────────────────
        val settledRecord = awaitWire(BATCH_SETTLED_EVENT, batchId)
        assertTransport(settledRecord, batchId, BATCH_SETTLED_EVENT)
        val settledBody = mapper.readTree(settledRecord.value())
        assertThat(settledBody["eventType"].asText()).isEqualTo(BATCH_SETTLED_EVENT)
        assertThat(settledBody["sourceService"].asText())
            .describedAs("producer attribution audit-service reads as the strongest claim (#3994/#5256)")
            .isEqualTo("clearing-service")
        assertThat(settledBody["batchId"].asText()).isEqualTo(batchId)
        assertThat(settledBody["itemCount"].asInt()).isEqualTo(2)
        assertThat(settledBody["occurredAt"].asText()).isNotBlank()

        // ── net_settlement.post ────────────────────────────────────────────────────
        val postRecord = awaitWire(NET_SETTLEMENT_POST_EVENT, batchId)
        assertTransport(postRecord, batchId, NET_SETTLEMENT_POST_EVENT)
        val postBody = mapper.readTree(postRecord.value())
        assertThat(postBody["eventType"].asText()).isEqualTo(NET_SETTLEMENT_POST_EVENT)
        assertThat(postBody["sourceService"].asText()).isEqualTo("clearing-service")
        assertThat(postBody["batchId"].asText()).isEqualTo(batchId)
        assertThat(postBody["idempotencyKey"].asText())
            .describedAs("the deterministic journal key — a redelivery collapses onto one journal (ADR-0281)")
            .isEqualTo("clearing-net-settlement-$batchId")
        assertThat(postBody["settlementAmount"].asText()).isEqualTo("300.75")
        assertThat(postBody["currency"].asText()).isEqualTo("EUR")

        // ── item.cleared × 2 ───────────────────────────────────────────────────────
        val clearedRecords = itemIds.map { itemId -> awaitWire(ITEM_CLEARED_EVENT, itemId) }
        clearedRecords.forEach { record ->
            assertTransport(record, record.key(), ITEM_CLEARED_EVENT)
            val body = mapper.readTree(record.value())
            assertThat(body["eventType"].asText()).isEqualTo(ITEM_CLEARED_EVENT)
            assertThat(body["sourceService"].asText()).isEqualTo("clearing-service")
            assertThat(itemIds).contains(body["itemId"].asText())
        }

        // The relay marked every row SENT — the durable hand-off boundary the dispatcher owns.
        assertOutboxSent(batchId, BATCH_SETTLED_EVENT)
        assertOutboxSent(batchId, NET_SETTLEMENT_POST_EVENT)
        itemIds.forEach { assertOutboxSent(it, ITEM_CLEARED_EVENT) }
    }

    @Test
    @TestSecurity(user = "wire-payments-2", roles = ["ROLE_PAYMENTS"])
    fun `the failed net settlement self consumption dead letters to the explicit dlq topic`() {
        submit(UUID.randomUUID(), "WIRE-C-003", "50.00")
        val cycle = triggerCycle()
        val batch = (cycle["batches"] as List<Map<String, Any?>>).single { it["currency"] == "EUR" }
        val batchId = batch["id"] as String
        settle(batchId)

        // The self-consumer read the command off this service's own topic, the ledger post
        // failed after EventRetry's three attempts (no ledger in the test JVM), and the
        // connector must park the record on the EXPLICIT DLQ topic (#5745).
        val deadline = Instant.now().plusSeconds(90)
        while (Instant.now().isBefore(deadline)) {
            val found = dlqObserved().firstOrNull { record ->
                header(record, "ce-type") == NET_SETTLEMENT_POST_EVENT && record.key() == batchId
            }
            if (found != null) {
                // SmallRye's dead-letter producer re-serializes the payload as a JSON string, so
                // the DLQ value is double-encoded (a quoted, escaped JSON document) — unwrap one
                // textual layer before reading fields.
                val body = runCatching {
                    val first = mapper.readTree(found.value())
                    if (first.isTextual) mapper.readTree(first.asText()) else first
                }.getOrNull()
                assertThat(body?.get("idempotencyKey")?.asText())
                    .describedAs(
                        "DLQ record key=%s ce-type=%s value=%.500s",
                        found.key(),
                        header(found, "ce-type"),
                        found.value(),
                    )
                    .isEqualTo("clearing-net-settlement-$batchId")
                return
            }
            Thread.sleep(300)
        }
        throw AssertionError("net_settlement.post for batch $batchId never reached the DLQ topic within 90s")
    }

    // ── HTTP driving ─────────────────────────────────────────────────────────────

    private fun submit(paymentId: UUID, reference: String, amount: String): String {
        val response = RestAssured.given()
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "paymentId": "$paymentId",
                  "paymentReference": "$reference",
                  "debtorIban": "CZ6508000000192000145399",
                  "creditorIban": "DE89370400440532013000",
                  "debtorBic": "GIBACZPX",
                  "creditorBic": "COBADEFF",
                  "amount": "$amount",
                  "currency": "EUR",
                  "rail": "SEPA_SCT",
                  "endToEndId": "WIRE-$reference",
                  "remittanceInfo": "wire $reference"
                }
                """.trimIndent(),
            )
            .post("/api/v1/clearing/submit")
            .then()
            .extract()
            .response()
        assertThat(response.statusCode)
            .describedAs("POST /api/v1/clearing/submit: %s", response.body.asString())
            .isEqualTo(201)
        return response.jsonPath().getString("id")
    }

    private fun triggerCycle(): Map<String, Any?> = RestAssured.given()
        .contentType(ContentType.JSON)
        .post("/api/v1/clearing/cycle/trigger?rail=SEPA_SCT")
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getMap<String, Any?>("")

    private fun settle(batchId: String): Map<String, Any?> = RestAssured.given()
        .contentType(ContentType.JSON)
        .post("/api/v1/clearing/batches/$batchId/settle")
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getMap<String, Any?>("")

    // ── wire read-back ───────────────────────────────────────────────────────────

    private fun awaitWire(eventType: String, key: String): ConsumerRecord<String, String> {
        val deadline = Instant.now().plusSeconds(45)
        while (Instant.now().isBefore(deadline)) {
            val found = observed().firstOrNull { record ->
                header(record, "ce-type") == eventType && record.key() == key
            }
            if (found != null) return found
            Thread.sleep(200)
        }
        val seen = observed().joinToString(", ") { "${it.key()}/${header(it, "ce-type")}" }
        throw AssertionError("no $eventType for $key on the wire within 45s; observed: [$seen]")
    }

    private fun assertTransport(record: ConsumerRecord<String, String>, key: String, eventType: String) {
        assertThat(record.topic()).isEqualTo(EVENTS_TOPIC)
        assertThat(record.key()).isEqualTo(key)
        assertThat(header(record, "ce-type")).isEqualTo(eventType)
        val eventId = header(record, "ce-id")
        assertThat(eventId).isNotBlank()
        assertThat(header(record, "idempotency-key"))
            .describedAs("the idempotency key is the event id, so at-least-once consumers can dedupe (ADR-0050 N3)")
            .isEqualTo(eventId)
    }

    private fun observed(): List<ConsumerRecord<String, String>> = OBSERVED

    private fun dlqObserved(): List<ConsumerRecord<String, String>> = DLQ_OBSERVED

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
        throw AssertionError("clearing_outbox row for $aggregateId/$eventType never reached SENT")
    }

    private fun outboxStatus(aggregateId: String, eventType: String): String? =
        dataSource.connection.use { queryStatus(it, aggregateId, eventType) }

    private fun queryStatus(c: java.sql.Connection, aggregateId: String, eventType: String): String? {
        val ps = c.prepareStatement(
            "select status from clearing_outbox where aggregate_id = ? and event_type = ?",
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
            TestResourceEntry(
                PostgresRedpandaRedisTestResource::class.java,
                mapOf("db" to "openbank_clearing_it"),
            ),
        )

        override fun getConfigOverrides(): Map<String, String> = mapOf(
            // Restart-safe ephemeral port for this profile's own Quarkus boot.
            "quarkus.http.test-port" to "0",
            // Dispatch stays on so the rows reach the broker; the tick is shortened so the wire
            // assertion does not wait five seconds per event.
            "openbank.outbox.dispatch-enabled" to "true",
            "openbank.outbox.poll-interval" to "1s",
            "openbank.outbox.initial-delay" to "1s",
            // The two switches the earlier wire suites measured as required for @Scheduled
            // Panache work: without them the reactive SessionFactory lazy synthetic bean cannot
            // be created outside an HTTP request.
            "quarkus.hibernate-orm.enabled" to "true",
            "quarkus.datasource.jdbc.enabled" to "true",
            // Suite-order poisoning guard: ClearingOutboxDispatchConformanceIT switches
            // clearing-events-out to the in-memory connector and only clear()s messages, not the
            // switch, on stop. In a full-module Gradle run that switch survives the profile
            // restart into this suite, so the connector is pinned back to smallrye-kafka.
            "mp.messaging.outgoing.clearing-events-out.connector" to "smallrye-kafka",
        )
    }

    companion object {
        private const val EVENTS_TOPIC = "openbank.clearing.batch.event"
        private const val DLQ_TOPIC = "openbank.dlq.clearing-service.clearing-net-settlement-in"
        private const val BATCH_SETTLED_EVENT = "openbank.clearing.batch.settled"
        private const val ITEM_CLEARED_EVENT = "openbank.clearing.item.cleared"
        private const val NET_SETTLEMENT_POST_EVENT = "openbank.clearing.net_settlement.post"

        private val OBSERVED = CopyOnWriteArrayList<ConsumerRecord<String, String>>()
        private val DLQ_OBSERVED = CopyOnWriteArrayList<ConsumerRecord<String, String>>()

        @BeforeAll
        @JvmStatic
        fun wireTap() {
            OBSERVED.clear()
            DLQ_OBSERVED.clear()
            ensureTopic(EVENTS_TOPIC)
            ensureTopic(DLQ_TOPIC)
            tap(EVENTS_TOPIC, OBSERVED, "clearing-wire-tap")
            tap(DLQ_TOPIC, DLQ_OBSERVED, "clearing-dlq-tap")
        }

        private fun tap(
            topic: String,
            sink: CopyOnWriteArrayList<ConsumerRecord<String, String>>,
            threadName: String,
        ) {
            val props = mapOf(
                "bootstrap.servers" to bootstrapServers(),
                "group.id" to "clearing-wire-read-${UUID.randomUUID()}",
                "auto.offset.reset" to "earliest",
                "enable.auto.commit" to "true",
                "key.deserializer" to StringDeserializer::class.qualifiedName,
                "value.deserializer" to StringDeserializer::class.qualifiedName,
            )
            val consumer = KafkaConsumer<String, String>(props)
            consumer.subscribe(listOf(topic))
            Thread {
                while (true) {
                    consumer.poll(Duration.ofMillis(500)).forEach { sink.add(it) }
                }
            }.apply {
                isDaemon = true
                name = threadName
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
