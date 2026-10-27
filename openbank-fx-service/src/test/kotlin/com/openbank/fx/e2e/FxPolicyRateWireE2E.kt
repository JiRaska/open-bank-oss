// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.e2e

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.fx.application.port.`in`.CnbPolicyRateUseCase
import com.openbank.fx.application.port.out.CnbPolicyRateDocument
import com.openbank.fx.application.port.out.CnbPolicyRateFeed
import com.openbank.fx.application.usecase.CnbPolicyRateService
import com.openbank.fx.domain.cnb.CnbPolicyInstrument
import com.openbank.libs.testing.containers.PostgresRedpandaRedisTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.QuarkusTestProfile.TestResourceEntry
import io.quarkus.test.junit.TestProfile
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.errors.TopicExistsException
import org.apache.kafka.common.serialization.StringDeserializer
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * Real-broker wire E2E for the ČNB policy-rate relay
 * (`fx-policy-rate-out` → `openbank.fx.cnb-policy-rate.published`). The fixing and conversion
 * wire suites cover the other two fx channels; this one covers the compacted policy-rate topic
 * the risk engine rebuilds its history from.
 *
 * The whole loop is real: the boot-time `@Scheduled` run (cron shrunk to `off`, the startup
 * trigger to a 1 s initial delay) fetches the three history files and the minimum-reserve
 * workbook through the `CnbPolicyRateFeed` port, `CnbPolicyRateService` upserts every fact and
 * writes one `fx_outbox` row per new/revised (instrument, effectiveFrom) in the same
 * transaction, the real dispatcher drains the 282 rows to a Redpanda container, and a fresh
 * consumer group reads the records back. The external ČNB download is the one edge leaving this
 * service — the profile swaps in an `@Alternative` stub serving the committed real ČNB files,
 * the same seam `CnbPolicyRateIngestionIT` stubs.
 *
 * Transport contract, measured against the shared OutboxKafkaHeaders: key ==
 * `UUID.nameUUIDFromBytes("CNB-POLICY:<instrument>:<effectiveFrom>")` — the deterministic
 * per-(instrument, effectiveFrom) compaction key `CnbPolicyRateService` computes, so a revision
 * lands after — and compacts over — the original; `ce-type` = `fx.cnb-policy-rate.published.v1`,
 * `ce-id` present.
 *
 * Payload contract: the serialized `CnbPolicyRatePublished` — `instrument`, `effectiveFrom`,
 * `rate` (a fraction: 0.0375 = 3,75 %), `sourceUrl`, `fetchedAt`, `contentSha256` (64 hex),
 * `note`, `revised`, `previousRate`, `occurredAt` — plus the base class's `sourceService`
 * ("fx-service", #3994/#5256).
 *
 * The second test pins the documented idempotence of the re-run: a second ingest of an already
 * stored history finds everything unchanged, publishes nothing, and the wire keeps exactly the
 * boot-run event count.
 *
 * Boot hazards pinned in the profile, each measured on the earlier wire suites: the
 * hibernate-orm/jdbc switches for @Scheduled Panache work, a 1 s outbox tick, the scheduler left
 * enabled, and the `fx-policy-rate-out` connector pinned to smallrye-kafka because the sibling
 * suites switch the fx channels to in-memory and only clear() messages, not the switch, on stop.
 */
@QuarkusTest
@TestProfile(FxPolicyRateWireE2E.WireProfile::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class FxPolicyRateWireE2E {

    @Inject
    lateinit var dataSource: DataSource

    @Inject
    lateinit var useCase: CnbPolicyRateUseCase

    private val mapper = ObjectMapper()

    @Test
    @Order(1)
    fun `an ingest of the repo history lands every fact on the compacted wire`() {
        val outcome = onEventLoop { useCase.ingest(REPO) }
        assertThat(outcome.counts.inserted).isEqualTo(REPO_ROWS)
        assertThat(outcome.published).isEqualTo(REPO_ROWS)

        awaitEventCount(REPO_ROWS)

        val key = CnbPolicyRateService.aggregateId(REPO, REPO_REVISION_DATE).toString()
        val record = observed().firstOrNull { it.key() == key }
        assertThat(record)
            .describedAs("wire holds the repo-rate fact keyed by (instrument, effectiveFrom)")
            .isNotNull
        record!!
        assertThat(record.topic()).isEqualTo(POLICY_TOPIC)
        val eventId = header(record, "ce-id")
        assertThat(eventId).isNotBlank()
        assertThat(header(record, "ce-type")).isEqualTo(POLICY_PUBLISHED_EVENT)
        assertThat(header(record, "idempotency-key")).isEqualTo(eventId)

        val body = mapper.readTree(record.value())
        assertThat(body["instrument"].asText()).isEqualTo(REPO.name)
        assertThat(body["effectiveFrom"].asText()).isEqualTo(REPO_REVISION_DATE.toString())
        assertThat(body["rate"].decimalValue()).isEqualByComparingTo("0.0375")
        assertThat(body["sourceService"].asText()).isEqualTo("fx-service")
        assertThat(body["sourceUrl"].asText()).isNotBlank()
        assertThat(body["contentSha256"].asText()).hasSize(64)
        assertThat(body["revised"].asBoolean()).isFalse()
        assertThat(body["occurredAt"].asText()).isNotBlank()

        // The relay marked the row SENT — the durable hand-off boundary the dispatcher owns.
        assertOutboxSent(key)
    }

    @Test
    @Order(2)
    fun `a re ingest of a stored history publishes nothing and the wire keeps the ingest count`() {
        awaitEventCount(REPO_ROWS)

        val outcome = onEventLoop { useCase.ingest(REPO) }
        assertThat(outcome.counts.inserted).isZero()
        assertThat(outcome.counts.revised).isZero()
        assertThat(outcome.counts.unchanged).isEqualTo(REPO_ROWS)
        assertThat(outcome.published).isZero()

        Thread.sleep(4_000)
        assertThat(observed().count { header(it, "ce-type") == POLICY_PUBLISHED_EVENT })
            .describedAs("a re-ingest of a stored history must not emit a second event per key")
            .isEqualTo(REPO_ROWS)
    }

    // ── wire read-back ───────────────────────────────────────────────────────────

    private fun awaitEventCount(expected: Int) {
        val deadline = Instant.now().plusSeconds(150)
        while (Instant.now().isBefore(deadline)) {
            val count = observed().count { header(it, "ce-type") == POLICY_PUBLISHED_EVENT }
            if (count >= expected) return
            Thread.sleep(250)
        }
        throw AssertionError("wire never held $expected policy-rate events within 150s")
    }

    private fun observed(): List<ConsumerRecord<String, String>> = OBSERVED

    private fun header(record: ConsumerRecord<String, String>, name: String): String? =
        record.headers().lastHeader(name)?.value()?.toString(Charsets.UTF_8)

    // ── direct use-case drive (no HTTP trigger exists for a re-run) ──────────────

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

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
            it.setString(2, POLICY_PUBLISHED_EVENT)
            val rs = it.executeQuery()
            rs.use { r -> return if (r.next()) r.getString(1) else null }
        }
    }

    /** Serves the committed real ČNB files — the parse/upsert/publish path stays production. */
    @Alternative
    @ApplicationScoped
    class WirePolicyRateFeed : CnbPolicyRateFeed {
        override suspend fun fetch(instrument: CnbPolicyInstrument): CnbPolicyRateDocument =
            CnbPolicyRateDocument("https://stub.invalid/${FILES.getValue(instrument)}", real(instrument))

        override suspend fun fetchMinimumReserves(): CnbPolicyRateDocument =
            CnbPolicyRateDocument("https://stub.invalid/$PMR", resource(PMR))
    }

    class WireProfile : QuarkusTestProfile {
        override fun disableGlobalTestResources() = true

        override fun testResources() = listOf(
            TestResourceEntry(
                PostgresRedpandaRedisTestResource::class.java,
                mapOf("db" to "openbank_fx_it"),
            ),
        )

        override fun getEnabledAlternatives(): MutableSet<Class<*>> = mutableSetOf(WirePolicyRateFeed::class.java)

        override fun getConfigOverrides(): Map<String, String> = mapOf(
            // Restart-safe ephemeral port for this profile's own Quarkus boot.
            "quarkus.http.test-port" to "0",
            // Dispatch stays on so the rows reach the broker; the tick is shortened so the wire
            // assertion does not wait five seconds per batch.
            "openbank.outbox.dispatch-enabled" to "true",
            "openbank.outbox.poll-interval" to "1s",
            "openbank.outbox.initial-delay" to "1s",
            // The two switches the earlier wire suites measured as required for @Scheduled
            // Panache work: without them the reactive SessionFactory lazy synthetic bean cannot
            // be created outside an HTTP request.
            "quarkus.hibernate-orm.enabled" to "true",
            "quarkus.datasource.jdbc.enabled" to "true",
            // The policy-rate scheduler stays OFF for the whole suite (the %test startup-every is
            // "off" and the cron is switched off here): its boot run would fire on a wall-clock
            // delay and, under full-fork load, can publish before the Redpanda Kafka API answers
            // (measured: "broker is not available" opens the dispatcher's circuit breaker and
            // dead-lines the channel for the whole run). The trigger is the use case driven
            // directly from the tests below, after @BeforeAll has proven the broker answers
            // (topic creation with a 120 s retry). The scheduler itself is exercised by
            // CnbPolicyRateIngestionIT; this suite proves the wire path.
            "quarkus.scheduler.enabled" to "true",
            "openbank.cnb.policy-rates.ingestion-cron" to "off",
            "openbank.cnb.policy-rates.initial-delay" to "2h",
            "openbank.cnb.policy-rates.startup-every" to "2h",
            // Suite-order poisoning guard: the sibling wire/journey suites switch the fx channels
            // to the in-memory connector and only clear() messages, not the switch, on stop. In a
            // full-module Gradle run that switch survives the profile restart into this suite, so
            // the connector is pinned back to smallrye-kafka.
            "mp.messaging.outgoing.fx-policy-rate-out.connector" to "smallrye-kafka",
        )
    }

    companion object {
        private const val POLICY_TOPIC = "openbank.fx.cnb-policy-rate.published"
        private const val POLICY_PUBLISHED_EVENT = "fx.cnb-policy-rate.published.v1"
        private const val PMR = "PMR_historie_zmen.xlsx"

        private val REPO = CnbPolicyInstrument.REPO_2W
        private val REPO_REVISION_DATE: LocalDate = LocalDate.of(2026, 6, 19)
        private const val REPO_ROWS = 117

        private val FILES = mapOf(
            CnbPolicyInstrument.REPO_2W to "vyvoj_repo_historie.txt",
            CnbPolicyInstrument.DISCOUNT to "vyvoj_diskontni_historie.txt",
            CnbPolicyInstrument.LOMBARD to "vyvoj_lombard_historie.txt",
        )

        private val OBSERVED = CopyOnWriteArrayList<ConsumerRecord<String, String>>()

        fun real(instrument: CnbPolicyInstrument): ByteArray = resource(FILES.getValue(instrument))

        fun resource(name: String): ByteArray = requireNotNull(
            FxPolicyRateWireE2E::class.java.getResourceAsStream("/cnb/policy-rates/$name"),
        ).readBytes()

        @BeforeAll
        @JvmStatic
        fun wireTap() {
            OBSERVED.clear()
            ensureTopic(POLICY_TOPIC)
            val props = mapOf(
                "bootstrap.servers" to bootstrapServers(),
                "group.id" to "fx-policy-rate-wire-read-${UUID.randomUUID()}",
                "auto.offset.reset" to "earliest",
                "enable.auto.commit" to "true",
                "key.deserializer" to StringDeserializer::class.qualifiedName,
                "value.deserializer" to StringDeserializer::class.qualifiedName,
            )
            val consumer = KafkaConsumer<String, String>(props)
            consumer.subscribe(listOf(POLICY_TOPIC))
            Thread {
                while (true) {
                    consumer.poll(Duration.ofMillis(500)).forEach { OBSERVED.add(it) }
                }
            }.apply {
                isDaemon = true
                name = "fx-policy-rate-wire-tap"
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
