// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.e2e

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.fx.application.port.out.CnbRateProvider
import com.openbank.libs.testing.containers.PostgresRedpandaRedisTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.QuarkusTestProfile.TestResourceEntry
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured
import io.restassured.http.ContentType
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
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
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * Real-broker wire E2E for the ČNB fixing relay (`fx-fixing-out` → `openbank.fx.fixing.published`).
 * The conversion wire suite covers `fx-events-out`; this one covers the fixing channel, which the
 * journey E2E also switches to in-memory. The whole loop is real: the operator ingest endpoint is
 * driven over the real HTTP surface, `CnbRateIngestionService` stores the fresh rates and writes
 * the `fx_outbox` row in the same transaction, the real `@Scheduled` dispatcher drains it to a
 * Redpanda container, and a fresh consumer group reads the record back.
 *
 * The ČNB feed is the one edge leaving this service: `CnbRateProvider` is an application port, so
 * the profile swaps in an `@Alternative` stub (`WireCnbFixingProvider`) returning a valid fixing
 * carrying EUR — the same seam `CnbFeedOutcomeContractIT` stubs. Everything downstream of the
 * fetch (parse, dedupe, store, outbox, dispatch, wire) is production code.
 *
 * Transport contract, measured against the shared OutboxKafkaHeaders: key ==
 * `UUID.nameUUIDFromBytes("CNB:<fixingDate>")` — the deterministic per-(source, day) partition
 * key `CnbRateIngestionService` computes, so every event about one fixing shares a partition and
 * stays ordered; `ce-type` = `fx.fixing.published.v1`, `ce-id` present and `idempotency-key` ==
 * ce-id (ADR-0050 N3).
 *
 * Payload contract: the serialized `FxFixingPublished` — `source` (CNB), `fixingDate`, `sequence`,
 * `quoteCurrency` (CZK), `validFrom`/`validTo`, `rates` (only the NEWLY stored rows, each
 * `{id, baseCurrency, bidRate}`), `occurredAt` — plus the base class's `sourceService`
 * ("fx-service", the producer attribution audit-service reads as the strongest claim,
 * #3994/#5256).
 *
 * The second test pins the documented idempotence: a re-ingest of a day whose rates are already
 * stored finds nothing fresh, emits NOTHING (ADR-0314 D5 — "a re-run that finds the day already
 * present emits nothing"), and the wire holds exactly one fixing event for the day.
 *
 * Boot hazards pinned in the profile, each measured on the earlier wire suites: the
 * hibernate-orm/jdbc switches for @Scheduled Panache work, a 1 s outbox tick, and the
 * `fx-fixing-out` connector pinned back to smallrye-kafka because the journey E2E switches it to
 * in-memory and only clear()s messages, not the switch, on stop.
 */
@QuarkusTest
@TestProfile(FxFixingWireE2E.WireProfile::class)
class FxFixingWireE2E {

    @Inject
    lateinit var dataSource: DataSource

    private val mapper = ObjectMapper()

    @Test
    @TestSecurity(user = "wire-operator-1", roles = ["ROLE_OPERATOR"])
    fun `an ingest that stores fresh rates lands the fixing event on the wire`() {
        val ingest = RestAssured.given()
            .contentType(ContentType.JSON)
            .post("/api/v1/fx/cnb/ingest?date=2026-05-30")
            .then()
            .extract()
            .response()
        assertThat(ingest.statusCode)
            .describedAs("POST /api/v1/fx/cnb/ingest: %s", ingest.body.asString())
            .isEqualTo(200)
        assertThat(ingest.jsonPath().getList<String>("currencies")).containsExactly("EUR")

        val record = awaitWire(fixingKey())
        assertThat(record.topic()).isEqualTo(FIXING_TOPIC)
        assertThat(record.key()).isEqualTo(fixingKey())
        val eventId = header(record, "ce-id")
        assertThat(eventId).isNotBlank()
        assertThat(header(record, "ce-type")).isEqualTo(FIXING_PUBLISHED_EVENT)
        assertThat(header(record, "idempotency-key"))
            .describedAs("the idempotency key is the event id, so at-least-once consumers can dedupe (ADR-0050 N3)")
            .isEqualTo(eventId)

        val body = mapper.readTree(record.value())
        assertThat(body["sourceService"].asText())
            .describedAs("producer attribution audit-service reads as the strongest claim (#3994/#5256)")
            .isEqualTo("fx-service")
        assertThat(body["source"].asText()).isEqualTo("CNB")
        assertThat(body["fixingDate"].asText()).isEqualTo(FIXING_DATE.toString())
        assertThat(body["sequence"].asInt()).isEqualTo(104)
        assertThat(body["quoteCurrency"].asText()).isEqualTo("CZK")
        assertThat(body["occurredAt"].asText()).isNotBlank()
        assertThat(body["validFrom"].asText()).isNotBlank()
        assertThat(body["validTo"].asText()).isNotBlank()
        val rates = body["rates"]
        assertThat(rates.isArray).isTrue()
        assertThat(rates).hasSize(1)
        assertThat(rates[0]["currency"].asText()).isEqualTo("EUR")
        assertThat(rates[0]["rateId"].asText()).isNotBlank()
        assertThat(rates[0]["ratePerUnit"].decimalValue()).isGreaterThan(java.math.BigDecimal.ZERO)

        // The relay marked the row SENT — the durable hand-off boundary the dispatcher owns.
        assertOutboxSent(fixingKey())
    }

    @Test
    @TestSecurity(user = "wire-operator-2", roles = ["ROLE_OPERATOR"])
    fun `a re ingest of a stored day emits nothing and the wire keeps exactly one fixing event`() {
        val day = "2026-06-01"
        val first = RestAssured.given()
            .contentType(ContentType.JSON)
            .post("/api/v1/fx/cnb/ingest?date=$day")
            .then()
            .extract()
            .response()
        assertThat(first.statusCode).isEqualTo(200)
        assertThat(first.jsonPath().getList<String>("currencies")).containsExactly("EUR")
        awaitWire(fixingKey(LocalDate.parse(day)))

        // The day is already stored — the run stores nothing fresh and must emit nothing
        // (ADR-0314 D5: the event is as idempotent as the ingestion itself).
        val second = RestAssured.given()
            .contentType(ContentType.JSON)
            .post("/api/v1/fx/cnb/ingest?date=$day")
            .then()
            .extract()
            .response()
        assertThat(second.statusCode).isEqualTo(200)
        assertThat(second.jsonPath().getInt("ingested"))
            .describedAs("a re-ingest of a stored day stores nothing new")
            .isZero()

        Thread.sleep(4_000)
        val observed = observed().filter { record -> record.key() == fixingKey(LocalDate.parse(day)) }
        assertThat(observed)
            .describedAs("a re-ingest must not emit a second fixing event for the day")
            .hasSize(1)
    }

    // ── wire read-back ───────────────────────────────────────────────────────────

    private fun awaitWire(key: String): ConsumerRecord<String, String> {
        val deadline = Instant.now().plusSeconds(45)
        while (Instant.now().isBefore(deadline)) {
            val found = observed().firstOrNull { record -> record.key() == key }
            if (found != null) return found
            Thread.sleep(200)
        }
        val seen = observed().joinToString(", ") { "${it.key()}/${header(it, "ce-type")}" }
        throw AssertionError("no fixing event for $key on the wire within 45s; observed: [$seen]")
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
            it.setString(2, FIXING_PUBLISHED_EVENT)
            val rs = it.executeQuery()
            rs.use { r -> return if (r.next()) r.getString(1) else null }
        }
    }

    private fun fixingKey(day: LocalDate = FIXING_DATE): String =
        UUID.nameUUIDFromBytes("CNB:$day".toByteArray()).toString()

    /**
     * The ČNB feed stand-in: a valid fixing for the stub day carrying EUR. Literal values only —
     * a `QuarkusTestProfile` loads in a different classloader from the test class, so companion
     * references here are not guaranteed to be the same instances the assertions read.
     */
    @Alternative
    @ApplicationScoped
    class WireCnbFixingProvider : CnbRateProvider {
        override suspend fun fetchFixing(date: LocalDate?): String {
            // One fixing day per requested date, so concurrently-ordered tests never share a
            // (source, day) partition key: the header date is what the parser derives the
            // fixing identity from.
            val day = date ?: LocalDate.of(2026, 5, 30)
            val header = "%02d.%02d.%04d #104".format(day.dayOfMonth, day.monthValue, day.year)
            return """
                $header
                země|měna|množství|kód|kurz
                EMU|euro|1|EUR|25,145
            """.trimIndent()
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

        override fun getEnabledAlternatives(): MutableSet<Class<*>> = mutableSetOf(WireCnbFixingProvider::class.java)

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
            // Suite-order poisoning guard: FxConversionJourneyE2E switches fx-fixing-out (with
            // the sibling channels) to the in-memory connector and only clear()s messages, not
            // the switch, on stop. In a full-module Gradle run that switch survives the profile
            // restart into this suite, so the connector is pinned back to smallrye-kafka.
            "mp.messaging.outgoing.fx-fixing-out.connector" to "smallrye-kafka",
        )
    }

    companion object {
        private const val FIXING_TOPIC = "openbank.fx.fixing.published"
        private const val FIXING_PUBLISHED_EVENT = "fx.fixing.published.v1"
        private val FIXING_DATE: LocalDate = LocalDate.of(2026, 5, 30)

        private val OBSERVED = CopyOnWriteArrayList<ConsumerRecord<String, String>>()

        @BeforeAll
        @JvmStatic
        fun wireTap() {
            OBSERVED.clear()
            ensureTopic(FIXING_TOPIC)
            val props = mapOf(
                "bootstrap.servers" to bootstrapServers(),
                "group.id" to "fx-fixing-wire-read-${UUID.randomUUID()}",
                "auto.offset.reset" to "earliest",
                "enable.auto.commit" to "true",
                "key.deserializer" to StringDeserializer::class.qualifiedName,
                "value.deserializer" to StringDeserializer::class.qualifiedName,
            )
            val consumer = KafkaConsumer<String, String>(props)
            consumer.subscribe(listOf(FIXING_TOPIC))
            Thread {
                while (true) {
                    consumer.poll(Duration.ofMillis(500)).forEach { OBSERVED.add(it) }
                }
            }.apply {
                isDaemon = true
                name = "fx-fixing-wire-tap"
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
