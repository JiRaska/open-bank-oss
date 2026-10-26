// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepainstant.e2e

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
 * Real-broker wire E2E for the SCT Inst outbox relay (`sct-inst-events-out` →
 * `openbank.sepa.instant.events`). Until this suite the producer side was stated as out of
 * scope: `SctInstLifecycleE2E` switches nothing to the broker itself but turns dispatch OFF, so
 * its outbox rows stayed local by design. Here the whole loop is real: the payment is submitted
 * and recalled over the real HTTP surface (screening/scheme/settlement stubs CLEAR/ACSC/booked,
 * the same trio the lifecycle E2E documents), the use case writes the `sct_inst_outbox` rows,
 * the real `@Scheduled` dispatcher drains them, and the topic is read back with a fresh
 * consumer group.
 *
 * Transport contract, measured against the shared OutboxKafkaHeaders: key == paymentId
 * (per-payment order), `ce-type` = the event class simple name (`SctInstPaymentSubmitted` /
 * `SctInstPaymentSettled` / `SctInstPaymentRecalled`), `ce-id` present and
 * `idempotency-key` == ce-id, so an at-least-once consumer can dedupe (ADR-0050 N3).
 *
 * The payload contract is the measured four-field envelope `SctInstEventPayloadCodec` builds —
 * `type`, `paymentId`, `occurredAt`, `sourceService` — and the suite pins that the recall
 * reason is NOT on the wire: `SctInstPaymentRecalled.recallReason` lives only in the durable
 * row, so the wire cannot leak the free-text dispute text downstream. `sourceService`
 * ("sepa-instant") is the producer attribution audit-service reads as the strongest claim
 * (#3994/#5256).
 *
 * Boot hazards pinned in the profile, each measured (the sepa-payment and notification wire
 * suites found the same classes of defect first):
 * - SctInstBootSmokeIT and SctInstOutboxDispatchConformanceIT switch `sct-inst-events-out` to
 *   the in-memory connector and only clear() on stop (messages, not the switch) — in a
 *   full-module Gradle run that switch survives the profile restart into this suite, so the
 *   connector is pinned back to smallrye-kafka;
 * - the @Scheduled dispatcher touches Panache from the scheduler thread, so the profile
 *   carries the hibernate-orm/jdbc switches;
 * - scheme submission is ON (born SETTLED through the real chain) and the outbox tick is
 *   shortened so the wire assertion does not wait five seconds per event;
 * - topic creation retries with fresh clients — under full-fork load the broker container
 *   reports "started" before its Kafka API answers (measured on the sepa-payment suite).
 */
@QuarkusTest
@TestProfile(SctInstEventsWireE2E.WireProfile::class)
class SctInstEventsWireE2E {

    @Inject
    lateinit var dataSource: DataSource

    private val mapper = ObjectMapper()

    @Test
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `a submitted payment lands submitted and settled events on the wire`() {
        val paymentId = submit(submitBody(UUID.randomUUID(), "wire-sub-${UUID.randomUUID()}"))

        val submitted = awaitWire(SUBMITTED_EVENT, paymentId)
        val submittedBody = mapper.readTree(submitted.value())
        assertThat(submittedBody["type"].asText()).isEqualTo(SUBMITTED_EVENT)
        assertThat(submittedBody["sourceService"].asText())
            .describedAs("producer attribution audit-service reads as the strongest claim (#3994/#5256)")
            .isEqualTo("sepa-instant")
        assertFourFieldEnvelope(submittedBody)

        val settled = awaitWire(SETTLED_EVENT, paymentId)
        val settledBody = mapper.readTree(settled.value())
        assertThat(settledBody["type"].asText()).isEqualTo(SETTLED_EVENT)
        assertFourFieldEnvelope(settledBody)

        // The transport contract on both records: key IS the payment id, CloudEvents headers.
        listOf(submitted, settled).forEach { record ->
            assertThat(record.topic()).isEqualTo(EVENTS_TOPIC)
            assertThat(record.key()).isEqualTo(paymentId)
            val eventId = header(record, "ce-id")
            assertThat(eventId).isNotBlank()
            assertThat(header(record, "idempotency-key"))
                .describedAs("the idempotency key is the event id, so at-least-once consumers can dedupe (ADR-0050 N3)")
                .isEqualTo(eventId)
        }
        assertThat(header(submitted, "ce-type")).isEqualTo(SUBMITTED_EVENT)
        assertThat(header(settled, "ce-type")).isEqualTo(SETTLED_EVENT)

        // The relay marked both rows SENT — the durable hand-off boundary the dispatcher owns.
        assertOutboxSent(paymentId, SUBMITTED_EVENT)
        assertOutboxSent(paymentId, SETTLED_EVENT)
    }

    @Test
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `a recall lands the recalled event on the wire without the free-text reason`() {
        val paymentId = submit(submitBody(UUID.randomUUID(), "wire-recall-${UUID.randomUUID()}"))
        awaitWire(SETTLED_EVENT, paymentId)

        val recalled = RestAssured.given()
            .contentType(ContentType.JSON)
            .body("""{"reason": "customer dispute wire"}""")
            .post("/api/v1/sepa-instant/$paymentId/recall")
            .then()
            .extract()
            .response()
        assertThat(recalled.statusCode)
            .describedAs("POST recall: %s", recalled.body.asString())
            .isEqualTo(200)

        val record = awaitWire(RECALLED_EVENT, paymentId)
        val body = mapper.readTree(record.value())
        assertThat(body["type"].asText()).isEqualTo(RECALLED_EVENT)
        assertFourFieldEnvelope(body)
        // The recall reason is intentionally NOT in the wire envelope — the four-field codec
        // carries only type/paymentId/occurredAt/sourceService, so a free-text dispute cannot
        // leak downstream of the broker. The reason stays in the durable row.
        assertThat(body.has("recallReason")).isFalse()
        assertThat(header(record, "ce-type")).isEqualTo(RECALLED_EVENT)
        assertOutboxSent(paymentId, RECALLED_EVENT)
    }

    @Test
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `an idempotent replay does not duplicate the submitted or settled event on the wire`() {
        val body = submitBody(UUID.randomUUID(), "wire-replay-${UUID.randomUUID()}")
        val paymentId = submit(body)
        awaitWire(SETTLED_EVENT, paymentId)

        // A client that lost the response resubmits under the same key: the stored row comes
        // back, and the wire must still hold exactly one submitted and one settled event.
        val replay = RestAssured.given()
            .contentType(ContentType.JSON)
            .body(body)
            .post("/api/v1/sepa-instant")
            .then()
            .extract()
            .response()
        assertThat(replay.statusCode).isEqualTo(201)
        assertThat(replay.jsonPath().getString("paymentId")).isEqualTo(paymentId)

        Thread.sleep(4_000)
        val submitted = observed().filter { header(it, "ce-type") == SUBMITTED_EVENT && paymentId(it) == paymentId }
        val settled = observed().filter { header(it, "ce-type") == SETTLED_EVENT && paymentId(it) == paymentId }
        assertThat(submitted)
            .describedAs("a deduplicated replay must not emit a second submitted event")
            .hasSize(1)
        assertThat(settled)
            .describedAs("a deduplicated replay must not emit a second settled event")
            .hasSize(1)
    }

    // ── HTTP driving ─────────────────────────────────────────────────────────────

    private fun submit(body: String): String {
        val response = RestAssured.given()
            .contentType(ContentType.JSON)
            .body(body)
            .post("/api/v1/sepa-instant")
            .then()
            .extract()
            .response()
        assertThat(response.statusCode)
            .describedAs("POST /api/v1/sepa-instant: %s", response.body.asString())
            .isEqualTo(201)
        return response.jsonPath().getString("paymentId")
    }

    private fun submitBody(debtorAccountId: UUID, key: String): String =
        """
        {
          "idempotencyKey": "$key",
          "debtorAccountId": "$debtorAccountId",
          "debtorIban": "$DEBTOR_IBAN",
          "debtorName": "Wire Debtor s.r.o.",
          "creditorIban": "$CREDITOR_IBAN",
          "creditorName": "Wire Creditor GmbH",
          "creditorBic": "COBADEFFXXX",
          "amount": 99.99,
          "currency": "EUR",
          "remittanceInfo": "wire SCT Inst",
          "endToEndId": "WIRE-${UUID.randomUUID()}"
        }
        """.trimIndent()

    // ── wire read-back ───────────────────────────────────────────────────────────

    private fun awaitWire(eventType: String, paymentId: String): ConsumerRecord<String, String> {
        val deadline = Instant.now().plusSeconds(45)
        while (Instant.now().isBefore(deadline)) {
            val found = observed().firstOrNull { record ->
                header(record, "ce-type") == eventType && paymentId(record) == paymentId
            }
            if (found != null) return found
            Thread.sleep(200)
        }
        val seen = observed().joinToString(", ") { "${it.key()}/${header(it, "ce-type")}" }
        throw AssertionError("no $eventType for $paymentId on the wire within 45s; observed: [$seen]")
    }

    private fun paymentId(record: ConsumerRecord<String, String>): String? = parse(record)?.get("paymentId")?.asText()

    private fun parse(record: ConsumerRecord<String, String>): com.fasterxml.jackson.databind.JsonNode? =
        runCatching { mapper.readTree(record.value()) }.getOrNull()

    private fun observed(): List<ConsumerRecord<String, String>> = OBSERVED

    private fun header(record: ConsumerRecord<String, String>, name: String): String? =
        record.headers().lastHeader(name)?.value()?.toString(Charsets.UTF_8)

    private fun assertFourFieldEnvelope(body: com.fasterxml.jackson.databind.JsonNode) {
        assertThat(body.fieldNames().asSequence().toList())
            .describedAs("the established four-field wire envelope (SctInstEventPayloadCodec)")
            .containsExactlyInAnyOrder("type", "paymentId", "occurredAt", "sourceService")
    }

    // ── outbox table read-back ───────────────────────────────────────────────────

    private fun assertOutboxSent(aggregateId: String, eventType: String) {
        val deadline = Instant.now().plusSeconds(15)
        while (Instant.now().isBefore(deadline)) {
            val status = outboxStatus(aggregateId, eventType)
            if (status == "SENT") return
            Thread.sleep(200)
        }
        throw AssertionError("sct_inst_outbox row for $aggregateId/$eventType never reached SENT")
    }

    private fun outboxStatus(aggregateId: String, eventType: String): String? =
        dataSource.connection.use { queryStatus(it, aggregateId, eventType) }

    private fun queryStatus(c: java.sql.Connection, aggregateId: String, eventType: String): String? {
        val ps = c.prepareStatement(
            "select status from sct_inst_outbox where aggregate_id = ? and event_type = ?",
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
                mapOf("db" to "openbank_sepa_instant_it"),
            ),
        )

        override fun getConfigOverrides(): Map<String, String> = mapOf(
            // Restart-safe ephemeral port for this profile's own Quarkus boot.
            "quarkus.http.test-port" to "0",
            // Born SETTLED through the real ADR-0104/0108 chain (same switch the lifecycle E2E
            // documents); dispatch stays on so the rows reach the broker.
            "openbank.sct-inst.scheme-submission.enabled" to "true",
            "openbank.outbox.dispatch-enabled" to "true",
            "openbank.outbox.poll-interval" to "1s",
            "openbank.outbox.initial-delay" to "1s",
            // The two switches the earlier wire suites measured as required for @Scheduled
            // Panache work: without them the reactive SessionFactory lazy synthetic bean cannot
            // be created outside an HTTP request.
            "quarkus.hibernate-orm.enabled" to "true",
            "quarkus.datasource.jdbc.enabled" to "true",
            // Suite-order poisoning guard: SctInstBootSmokeIT and
            // SctInstOutboxDispatchConformanceIT switch sct-inst-events-out to the in-memory
            // connector and only clear() on stop (messages, not the switch). In a full-module
            // Gradle run that switch survives the profile restart into this suite, so the
            // connector is pinned back to smallrye-kafka.
            "mp.messaging.outgoing.sct-inst-events-out.connector" to "smallrye-kafka",
        )
    }

    companion object {
        private const val EVENTS_TOPIC = "openbank.sepa.instant.events"
        private const val SUBMITTED_EVENT = "SctInstPaymentSubmitted"
        private const val SETTLED_EVENT = "SctInstPaymentSettled"
        private const val RECALLED_EVENT = "SctInstPaymentRecalled"
        private const val OPERATOR = "wire-operator-1"
        private const val DEBTOR_IBAN = "CZ6508000000192000145399"
        private const val CREDITOR_IBAN = "DE89370400440532013000"

        private val OBSERVED = CopyOnWriteArrayList<ConsumerRecord<String, String>>()

        @BeforeAll
        @JvmStatic
        fun wireTap() {
            OBSERVED.clear()
            ensureTopic(EVENTS_TOPIC)
            val props = mapOf(
                "bootstrap.servers" to bootstrapServers(),
                "group.id" to "sct-inst-wire-read-${UUID.randomUUID()}",
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
                name = "sct-inst-wire-tap"
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
