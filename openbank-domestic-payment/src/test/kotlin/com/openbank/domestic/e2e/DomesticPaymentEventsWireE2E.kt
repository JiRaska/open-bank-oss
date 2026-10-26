// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.e2e

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.testing.containers.PostgresRedpandaRedisTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.QuarkusTestProfile.TestResourceEntry
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
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
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * Real-broker wire E2E for the domestic payment outbox relay (`events-out` →
 * `openbank.domestic.payment.events`). Until this suite the money-path create flow stopped at
 * durable local state: `DomesticPaymentOutboxAtomicityIT`, `DomesticPaymentTraceContractIT` and
 * `DelegatedSpendReservationTaintIT` all switch `events-out` to the in-memory connector, so the
 * outbox row was asserted in Postgres but nothing ever reached a broker. Here the whole loop is
 * real: the payment is submitted over the real HTTP surface (the no-op Temporal workflow from
 * `WorkflowClientTestProducer` answers the ADR-0120 fire-and-forget start), the use case commits
 * the `domestic_payment_outbox` row in the same transaction as the payment, the real `@Scheduled`
 * dispatcher drains it, and the topic is read back with a fresh consumer group.
 *
 * Transport contract, measured against the shared OutboxKafkaHeaders: key == paymentId
 * (per-payment order), `ce-type` = the outbox event type column (`domestic.payment.created`,
 * the dotted-lowercase spelling — the SCREAMING_SNAKE_CASE `DOMESTIC_PAYMENT_CREATED` body field
 * is a different audience, the AuditConsumer discriminator, #3994), `ce-id` present and
 * `idempotency-key` == ce-id, so an at-least-once consumer can dedupe (ADR-0050 N3).
 *
 * Payload contract: the serialized `DomesticPaymentCreatedEvent` — `eventType`,
 * `sourceService` ("domestic-payment", the producer attribution audit-service reads as the
 * strongest claim, #3994/#5256), `idempotencyKey` echoing the request header,
 * `aggregateRevision` = 1 on the created event, and no `rejectReason`/`rejectDetail` leakage
 * shape at all on this event type.
 *
 * Boot hazards pinned in the profile, each measured on the earlier wire suites (notification,
 * sepa-payment, sepa-instant):
 * - three sibling suites switch `events-out` to the in-memory connector and only clear() on
 *   stop (messages, not the switch) — in a full-module Gradle run that switch survives the
 *   profile restart into this suite, so the connector is pinned back to smallrye-kafka;
 * - the @Scheduled dispatcher touches Panache from the scheduler thread, so the profile carries
 *   the hibernate-orm/jdbc switches;
 * - the outbox tick is shortened so the wire assertion does not wait five seconds per event;
 * - topic creation retries with fresh clients — under full-fork load the broker container
 *   reports "started" before its Kafka API answers.
 */
@QuarkusTest
@TestProfile(DomesticPaymentEventsWireE2E.WireProfile::class)
class DomesticPaymentEventsWireE2E {

    @Inject
    lateinit var dataSource: DataSource

    private val mapper = ObjectMapper()

    @Test
    @TestSecurity(user = ACTOR_ID, roles = ["ROLE_PAYMENTS"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = ACTOR_ID),
            Claim(key = "preferred_username", value = ACTOR_ID),
        ],
    )
    fun `a created payment lands the created event on the wire`() {
        val key = "wire-created-${UUID.randomUUID()}"
        val created = post(createBody(), key)
        assertThat(created.statusCode)
            .describedAs("POST /api/v1/domestic-payments: %s", created.body.asString())
            .isEqualTo(201)
        val paymentId = created.jsonPath().getString("id")

        val record = awaitWire(paymentId)
        assertThat(record.topic()).isEqualTo(EVENTS_TOPIC)
        assertThat(record.key()).isEqualTo(paymentId)
        val eventId = header(record, "ce-id")
        assertThat(eventId).isNotBlank()
        assertThat(header(record, "ce-type")).isEqualTo(CREATED_EVENT)
        assertThat(header(record, "idempotency-key"))
            .describedAs("the idempotency key is the event id, so at-least-once consumers can dedupe (ADR-0050 N3)")
            .isEqualTo(eventId)

        val body = mapper.readTree(record.value())
        // The body discriminator is SCREAMING_SNAKE_CASE for AuditConsumer, a different audience
        // than the dotted-lowercase ce-type header (#3994) — this suite pins both spellings.
        assertThat(body["eventType"].asText()).isEqualTo("DOMESTIC_PAYMENT_CREATED")
        assertThat(body["sourceService"].asText())
            .describedAs("producer attribution audit-service reads as the strongest claim (#3994/#5256)")
            .isEqualTo("domestic-payment")
        assertThat(body["paymentId"].asText()).isEqualTo(paymentId)
        assertThat(body["idempotencyKey"].asText()).isEqualTo(key)
        assertThat(body["status"].asText()).isEqualTo("RECEIVED")
        assertThat(body["amount"].decimalValue()).isEqualByComparingTo(BigDecimal("1234.56"))
        assertThat(body["currency"].asText()).isEqualTo("CZK")
        assertThat(body["aggregateRevision"].asLong()).isEqualTo(1)
        assertThat(body.has("rejectReason")).isFalse()

        // The relay marked the row SENT — the durable hand-off boundary the dispatcher owns.
        assertOutboxSent(paymentId, CREATED_EVENT)
    }

    @Test
    @TestSecurity(user = ACTOR_ID, roles = ["ROLE_PAYMENTS"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = ACTOR_ID),
            Claim(key = "preferred_username", value = ACTOR_ID),
        ],
    )
    fun `an idempotent replay does not duplicate the created event on the wire`() {
        val key = "wire-replay-${UUID.randomUUID()}"
        val body = createBody()
        val created = post(body, key)
        assertThat(created.statusCode).isEqualTo(201)
        val paymentId = created.jsonPath().getString("id")
        awaitWire(paymentId)

        // A client that lost the response resubmits under the same key: the stored row comes
        // back flagged via X-Idempotency-Replayed, and the wire must still hold exactly one
        // created event.
        val replay = RestAssured.given()
            .contentType(ContentType.JSON)
            .header("Idempotency-Key", key)
            .body(body)
            .post("/api/v1/domestic-payments")
            .then()
            .extract()
            .response()
        assertThat(replay.statusCode).isEqualTo(201)
        assertThat(replay.getHeader("X-Idempotency-Replayed")).isEqualTo("true")
        assertThat(replay.jsonPath().getString("id")).isEqualTo(paymentId)

        Thread.sleep(4_000)
        val observed = observed().filter { paymentId(it) == paymentId }
        assertThat(observed)
            .describedAs("a deduplicated replay must not emit a second created event")
            .hasSize(1)
    }

    // ── HTTP driving ─────────────────────────────────────────────────────────────

    private fun post(body: String, key: String): io.restassured.response.Response = RestAssured.given()
        .contentType(ContentType.JSON)
        .header("Idempotency-Key", key)
        .body(body)
        .post("/api/v1/domestic-payments")
        .then()
        .extract()
        .response()

    private fun createBody(): String =
        """
        {
          "debtorAccountId": "${UUID.randomUUID()}",
          "debtorAccountNumber": "1234567890",
          "debtorBankCode": "0800",
          "debtorName": "Wire Debtor",
          "creditorAccountNumber": "9876543210",
          "creditorBankCode": "0100",
          "creditorName": "Wire Creditor",
          "amount": ${BigDecimal("1234.56")},
          "currency": "CZK",
          "variableSymbol": "2026001",
          "specificSymbol": null,
          "constantSymbol": "0308",
          "messageForPayee": "wire event",
          "priority": "STANDARD",
          "transferScope": null
        }
        """.trimIndent()

    // ── wire read-back ───────────────────────────────────────────────────────────

    private fun awaitWire(paymentId: String): ConsumerRecord<String, String> {
        val deadline = Instant.now().plusSeconds(45)
        while (Instant.now().isBefore(deadline)) {
            val found = observed().firstOrNull { record -> paymentId(record) == paymentId }
            if (found != null) return found
            Thread.sleep(200)
        }
        val seen = observed().joinToString(", ") { "${it.key()}/${header(it, "ce-type")}" }
        throw AssertionError("no created event for $paymentId on the wire within 45s; observed: [$seen]")
    }

    private fun paymentId(record: ConsumerRecord<String, String>): String? =
        runCatching { mapper.readTree(record.value()) }.getOrNull()?.get("paymentId")?.asText()

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
        throw AssertionError("domestic_payment_outbox row for $aggregateId/$eventType never reached SENT")
    }

    private fun outboxStatus(aggregateId: String, eventType: String): String? =
        dataSource.connection.use { queryStatus(it, aggregateId, eventType) }

    private fun queryStatus(c: java.sql.Connection, aggregateId: String, eventType: String): String? {
        val ps = c.prepareStatement(
            "select status from domestic_payment_outbox where aggregate_id = ? and event_type = ?",
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
                mapOf("db" to "openbank_domestic_payment_it"),
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
            // Suite-order poisoning guard: DomesticPaymentOutboxAtomicityIT,
            // DomesticPaymentTraceContractIT and DelegatedSpendReservationTaintIT switch
            // events-out to the in-memory connector and only clear() on stop (messages, not the
            // switch). In a full-module Gradle run that switch survives the profile restart into
            // this suite, so the connector is pinned back to smallrye-kafka.
            "mp.messaging.outgoing.events-out.connector" to "smallrye-kafka",
        )
    }

    companion object {
        private const val EVENTS_TOPIC = "openbank.domestic.payment.events"
        private const val CREATED_EVENT = "domestic.payment.created"
        private const val ACTOR_ID = "00000000-0000-0000-0000-0000000000e2"

        private val OBSERVED = CopyOnWriteArrayList<ConsumerRecord<String, String>>()

        @BeforeAll
        @JvmStatic
        fun wireTap() {
            OBSERVED.clear()
            ensureTopic(EVENTS_TOPIC)
            val props = mapOf(
                "bootstrap.servers" to bootstrapServers(),
                "group.id" to "domestic-wire-read-${UUID.randomUUID()}",
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
                name = "domestic-wire-tap"
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
