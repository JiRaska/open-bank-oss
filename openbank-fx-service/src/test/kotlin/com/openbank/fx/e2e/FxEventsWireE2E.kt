// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.e2e

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.fx.domain.screening.ScreeningMatchStatus
import com.openbank.fx.domain.screening.ScreeningResult
import com.openbank.fx.domain.screening.ScreeningRole
import com.openbank.fx.infrastructure.client.SanctionsScreeningAdapter
import com.openbank.libs.testing.containers.PostgresRedpandaRedisTestResource
import io.mockk.coEvery
import io.mockk.mockk
import io.quarkus.test.junit.QuarkusMock
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
import org.junit.jupiter.api.BeforeEach
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
 * Real-broker wire E2E for the FX conversion relay (`fx-events-out` →
 * `openbank.fx.conversion.completed`). Until this suite the conversion outbox row was asserted
 * in Postgres and the dispatch was stated out of scope: `FxConversionJourneyE2E` switches all
 * three outgoing channels to the in-memory connector, so nothing ever reached a broker. Here the
 * whole loop is real: the conversion is executed over the real HTTP surface, `FxService.settle`
 * writes the `fx_outbox` row in the same transaction as the conversion, the real `@Scheduled`
 * dispatcher drains it to a Redpanda container, and a fresh consumer group reads the record back.
 *
 * The one stubbed edge, stated like the journey E2E: sanctions screening is a call OUT of this
 * service to sanctions-service, which is not running here, and the use case fails CLOSED — an
 * unreachable screening routes every conversion to HOLD and settles nothing. The
 * `SanctionsScreeningAdapter` is therefore stubbed to CLEAR via `QuarkusMock`; fraud scoring
 * fails open by design (#4221) and runs after the write, so it is left alone.
 *
 * Transport contract, measured against the shared OutboxKafkaHeaders: key == conversionId,
 * `ce-type` = `fx.conversion.executed.v1` (the only type discriminator — the serialized event
 * body has no `eventType` field), `ce-id` present and `idempotency-key` == ce-id, so an
 * at-least-once consumer can dedupe (ADR-0050 N3).
 *
 * Payload contract: the serialized `FxConversionExecuted` — `conversionId`, `partyId`,
 * `fromCurrency`, `toCurrency`, `fromAmount`, `toAmount`, `rate`, `occurredAt` — plus the base
 * class's `sourceService` ("fx-service", the producer attribution audit-service reads as the
 * strongest claim, #3994/#5256).
 *
 * Boot hazards pinned in the profile, each measured on the earlier wire suites (notification
 * through sdd): the hibernate-orm/jdbc switches for @Scheduled Panache work, a 1 s outbox tick,
 * and the `fx-events-out` connector pinned back to smallrye-kafka because the journey E2E
 * switches it to in-memory and only clear()s messages, not the switch, on stop.
 */
@QuarkusTest
@TestProfile(FxEventsWireE2E.WireProfile::class)
class FxEventsWireE2E {

    @Inject
    lateinit var dataSource: DataSource

    private val mapper = ObjectMapper()

    @BeforeEach
    fun screeningIsClear() {
        val port = mockk<SanctionsScreeningAdapter>()
        coEvery { port.screen(any(), any(), any()) } answers {
            ScreeningResult(
                subject = firstArg(),
                role = ScreeningRole.DEBTOR,
                status = ScreeningMatchStatus.CLEAR,
                score = 0.0,
                matchedEntity = null,
            )
        }
        QuarkusMock.installMockForType(port, SanctionsScreeningAdapter::class.java)
    }

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_PAYMENTS"])
    fun `a settled conversion lands the executed event on the wire`() {
        val conversionId = convert("wire-conv-${UUID.randomUUID()}")

        val record = awaitWire(conversionId)
        assertThat(record.topic()).isEqualTo(EVENTS_TOPIC)
        assertThat(record.key()).isEqualTo(conversionId)
        val eventId = header(record, "ce-id")
        assertThat(eventId).isNotBlank()
        assertThat(header(record, "ce-type")).isEqualTo(CONVERSION_EXECUTED_EVENT)
        assertThat(header(record, "idempotency-key"))
            .describedAs("the idempotency key is the event id, so at-least-once consumers can dedupe (ADR-0050 N3)")
            .isEqualTo(eventId)

        val body = mapper.readTree(record.value())
        assertThat(body.fieldNames().asSequence().toList())
            .describedAs("the serialized FxConversionExecuted plus the base-class sourceService")
            .containsExactlyInAnyOrder(
                "conversionId",
                "partyId",
                "fromCurrency",
                "toCurrency",
                "fromAmount",
                "toAmount",
                "rate",
                "occurredAt",
                "sourceService",
            )
        assertThat(body["sourceService"].asText())
            .describedAs("producer attribution audit-service reads as the strongest claim (#3994/#5256)")
            .isEqualTo("fx-service")
        assertThat(body["conversionId"].asText()).isEqualTo(conversionId)
        assertThat(body["partyId"].asText()).isEqualTo(PARTY_ID)
        assertThat(body["fromCurrency"].asText()).isEqualTo("CZK")
        assertThat(body["toCurrency"].asText()).isEqualTo("EUR")
        assertThat(body["fromAmount"].asLong()).isEqualTo(FROM_MINOR_UNITS)
        assertThat(body["toAmount"].asLong())
            .describedAs("a SETTLED conversion must credit a non-zero amount")
            .isGreaterThan(0)
        assertThat(body["rate"].decimalValue()).isGreaterThan(BigDecimal.ZERO)

        // The relay marked the row SENT — the durable hand-off boundary the dispatcher owns.
        assertOutboxSent(conversionId)
    }

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_PAYMENTS"])
    fun `an idempotent replay does not duplicate the executed event on the wire`() {
        val key = "wire-replay-${UUID.randomUUID()}"
        val conversionId = convert(key)
        awaitWire(conversionId)

        // A client that lost the response retries under the same Idempotency-Key: the stored
        // conversion comes back, and the wire must still hold exactly one executed event.
        val replay = convert(key)
        assertThat(replay).isEqualTo(conversionId)

        Thread.sleep(4_000)
        val observed = observed().filter { record -> record.key() == conversionId }
        assertThat(observed)
            .describedAs("a deduplicated replay must not emit a second executed event")
            .hasSize(1)
    }

    // ── HTTP driving ─────────────────────────────────────────────────────────────

    private fun convert(idempotencyKey: String): String {
        val response = RestAssured.given()
            .contentType(ContentType.JSON)
            .header("Idempotency-Key", idempotencyKey)
            .body(
                """
                {
                  "partyId": "$PARTY_ID",
                  "accountId": null,
                  "partyName": "Wire Party",
                  "fromCurrency": "CZK",
                  "toCurrency": "EUR",
                  "fromAmountMinorUnits": $FROM_MINOR_UNITS
                }
                """.trimIndent(),
            )
            .post("/api/v1/fx/convert")
            .then()
            .extract()
            .response()
        assertThat(response.statusCode)
            .describedAs("POST /api/v1/fx/convert: %s", response.body.asString())
            .isEqualTo(201)
        assertThat(response.jsonPath().getString("status")).isEqualTo("SETTLED")
        return response.jsonPath().getString("id")
    }

    // ── wire read-back ───────────────────────────────────────────────────────────

    private fun awaitWire(conversionId: String): ConsumerRecord<String, String> {
        val deadline = Instant.now().plusSeconds(45)
        while (Instant.now().isBefore(deadline)) {
            val found = observed().firstOrNull { record -> record.key() == conversionId }
            if (found != null) return found
            Thread.sleep(200)
        }
        val seen = observed().joinToString(", ") { "${it.key()}/${header(it, "ce-type")}" }
        throw AssertionError("no executed event for $conversionId on the wire within 45s; observed: [$seen]")
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
        throw AssertionError("fx_outbox row for $aggregateId never reached SENT")
    }

    private fun outboxStatus(id: String): String? = dataSource.connection.use { queryStatus(it, id) }

    private fun queryStatus(c: java.sql.Connection, aggregateId: String): String? {
        val ps = c.prepareStatement(
            "select status from fx_outbox where aggregate_id = ? and event_type = ?",
        )
        ps.use {
            it.setObject(1, UUID.fromString(aggregateId))
            it.setString(2, CONVERSION_EXECUTED_EVENT)
            val rs = it.executeQuery()
            rs.use { r -> return if (r.next()) r.getString(1) else null }
        }
    }

    class WireProfile : QuarkusTestProfile {
        override fun disableGlobalTestResources() = true

        override fun testResources() = listOf(
            TestResourceEntry(
                PostgresRedpandaRedisTestResource::class.java,
                mapOf("db" to "openbank_fx_it"),
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
            // Suite-order poisoning guard: FxConversionJourneyE2E switches fx-events-out (with
            // the two sibling channels) to the in-memory connector and only clear()s messages,
            // not the switch, on stop. In a full-module Gradle run that switch survives the
            // profile restart into this suite, so the connector is pinned back to smallrye-kafka.
            "mp.messaging.outgoing.fx-events-out.connector" to "smallrye-kafka",
        )
    }

    companion object {
        private const val EVENTS_TOPIC = "openbank.fx.conversion.completed"
        private const val CONVERSION_EXECUTED_EVENT = "fx.conversion.executed.v1"
        private const val ACTOR = "00000000-0000-0000-0000-0000000000e2"
        private const val PARTY_ID = "00000000-3333-0000-0000-000000000001"
        private const val FROM_MINOR_UNITS = 100_000L

        private val OBSERVED = CopyOnWriteArrayList<ConsumerRecord<String, String>>()

        @BeforeAll
        @JvmStatic
        fun wireTap() {
            OBSERVED.clear()
            ensureTopic(EVENTS_TOPIC)
            val props = mapOf(
                "bootstrap.servers" to bootstrapServers(),
                "group.id" to "fx-wire-read-${UUID.randomUUID()}",
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
                name = "fx-wire-tap"
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
