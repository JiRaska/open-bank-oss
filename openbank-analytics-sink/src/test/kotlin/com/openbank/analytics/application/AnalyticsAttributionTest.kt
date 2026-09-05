// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.analytics.AnalyticsEnvelope
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Regression guard for issue #2598 — subscribed events landing in bronze as
 * `UNKNOWN`/`UNKNOWN`/`unknown` while their payloads were plainly identifiable.
 *
 * Root cause: the consumer read only the message BODY, and an outbox-relayed record's body is
 * `OutboxEntry.payload` — the bare domain event. The outbox's own addressing rides on the
 * transport (`ce-type` header, record key, topic), which a `String` signature discards. So every
 * producer publishing a bare payload lost three columns of attribution, and nothing errored: the
 * row landed, the consumer stayed healthy, the pipeline was green.
 *
 * The tests below cover BOTH halves. The classifier half is the fix; the metrics half is what
 * stops the next instance being equally silent — a component that cannot express its own failure
 * reports success, and that property, not the missing SCA branch, is why this survived.
 */
class AnalyticsAttributionTest {

    private val mapper = ObjectMapper()
    private val consumer = AnalyticsConsumer().apply {
        objectMapper = mapper
        clock = Clock.systemUTC()
    }

    // The three shapes observed on the sandbox in #2598, verbatim in structure.
    private val passkey = """{ "deviceId": "d-1", "credentialId": "MFkwEwYHKoZIzj0CAQ" }"""
    private val document =
        """{ "documentId": "doc-1", "templateCode": "RAMCOVA_SMLOUVA_CS", "templateVersion": 2 }"""
    private val ceremony = """{ "ceremonyId": "cer-1", "documentId": "doc-1", "at": "2026-07-01T00:00:00Z" }"""

    @Test
    fun `a passkey enrolment is attributed to PASSKEY from the body, not UNKNOWN`() {
        // #2629 taught the body heuristic credentialId -> PASSKEY, so the topic fallback is not
        // consulted here at all: body-first ordering resolves the type and idForType pairs it with
        // the credentialId (not the partition key), which is the bucketing already live in bronze.
        val env = consumer.toEnvelope(
            mapper.readTree(passkey),
            EventAddress(topic = "openbank.sca.events", key = "d-1", ceType = "sca.device.enrolled"),
        )

        assertThat(env.aggregateType).isEqualTo("PASSKEY")
        assertThat(env.eventType).isEqualTo("sca.device.enrolled")
        assertThat(env.sourceService).isEqualTo("openbank-sca-service")
        assertThat(env.aggregateId).isEqualTo("MFkwEwYHKoZIzj0CAQ")
    }

    @Test
    fun `a generated document is attributed to DOCUMENT and the document service`() {
        val env = consumer.toEnvelope(
            mapper.readTree(document),
            EventAddress(topic = "openbank.documents.document.event", key = "doc-1", ceType = "document.generated"),
        )

        assertThat(env.aggregateType).isEqualTo("DOCUMENT")
        assertThat(env.eventType).isEqualTo("document.generated")
        assertThat(env.sourceService).isEqualTo("openbank-document-service")
    }

    @Test
    fun `a signing-ceremony step is attributed rather than collapsed into the UNKNOWN bucket`() {
        val env = consumer.toEnvelope(
            mapper.readTree(ceremony),
            EventAddress(
                topic = "openbank.documents.document.event",
                key = "cer-1",
                ceType = "ceremony.step.completed",
            ),
        )

        assertThat(env.aggregateType).isEqualTo("DOCUMENT")
        assertThat(env.sourceService).isEqualTo("openbank-document-service")
    }

    @Test
    fun `the body still wins over the topic, so today's correctly-filed events do not move bucket`() {
        // This is the asymmetry that makes the change additive: it can only turn an UNKNOWN into a
        // value. A balance event carries an accountId and is filed under ACCOUNT today; letting the
        // topic win would refile it as BALANCE and split one aggregate across two buckets in the
        // silver views, which group by (aggregateType, aggregateId).
        val env = consumer.toEnvelope(
            mapper.readTree("""{ "accountId": "acc-1", "eventType": "BALANCE_UPDATED" }"""),
            EventAddress(topic = "openbank.balance.events", key = "acc-1", ceType = "balance.something.else"),
        )

        assertThat(env.aggregateType).isEqualTo("ACCOUNT")
        assertThat(env.eventType).isEqualTo("BALANCE_UPDATED")
    }

    @Test
    fun `an explicit envelope is untouched by the topic fallbacks`() {
        val env = consumer.toEnvelope(
            mapper.readTree(
                """
                { "aggregateType": "PARTY", "aggregateId": "p-1", "eventType": "party.created",
                  "sourceService": "openbank-party-service" }
                """.trimIndent(),
            ),
            EventAddress(topic = "openbank.sca.events", key = "ignored", ceType = "ignored"),
        )

        assertThat(env.aggregateType).isEqualTo("PARTY")
        assertThat(env.aggregateId).isEqualTo("p-1")
        assertThat(env.eventType).isEqualTo("party.created")
        assertThat(env.sourceService).isEqualTo("openbank-party-service")
    }

    // ---------------------------------------------------------------------------------------------
    // Issue #4553 — the producer's spelling used to survive verbatim while both fallbacks emit
    // uppercase, so aggregate_type recorded which path attributed the event. Measured on the sandbox
    // warehouse: ACCOUNT 294 rows / Account 17, five account ids under BOTH, and therefore two
    // current-state rows per account in silver_current_state (it groups by (type, id), so
    // last-writer-wins cannot fire across the split). Transaction and Consent existed only in mixed
    // case, so every consumer comparing against 'TRANSACTION' matched nothing.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a producer's mixed-case aggregateType is normalised, so one aggregate cannot split in two`() {
        val mixed = consumer.toEnvelope(
            mapper.readTree(
                """{ "aggregateType": "Account", "aggregateId": "acc-1", "eventType": "AccountCreated" }""",
            ),
        )
        val upper = consumer.toEnvelope(
            mapper.readTree(
                """{ "aggregateType": "ACCOUNT", "aggregateId": "acc-1", "eventType": "BALANCE_UPDATED" }""",
            ),
        )

        assertThat(mixed.aggregateType).isEqualTo("ACCOUNT")
        // The property that matters is not the string — it is that both events reduce to ONE silver
        // group. Asserting the pair is what a single isEqualTo("ACCOUNT") cannot express.
        assertThat(mixed.aggregateType to mixed.aggregateId)
            .describedAs("both spellings of one account must land in the same silver group")
            .isEqualTo(upper.aggregateType to upper.aggregateId)
    }

    @Test
    fun `a mixed-case type still resolves its own id field, never the accountId fallback`() {
        // idForType is a `when (type)` over uppercase literals, so before normalisation a
        // "Transaction" envelope fell through to `?: node["accountId"]` — pairing a TRANSACTION type
        // with an ACCOUNT id, which is precisely what resolveAggregateId's KDoc exists to prevent and
        // what would collapse every transaction on an account into one aggregate in silver.
        val env = consumer.toEnvelope(
            mapper.readTree("""{ "aggregateType": "Transaction", "transactionId": "tx-1", "accountId": "acc-1" }"""),
        )

        assertThat(env.aggregateType).isEqualTo("TRANSACTION")
        assertThat(env.aggregateId).isEqualTo("tx-1")
    }

    @Test
    fun `normalisation does not manufacture attribution for an unidentifiable event`() {
        // The UNKNOWN sentinel must survive uppercasing unchanged: #2598's whole point is that an
        // unattributed event stays visibly unattributed rather than being quietly bucketed.
        val env = consumer.toEnvelope(mapper.readTree("""{ "somethingElse": "x" }"""), EventAddress.NONE)

        assertThat(env.aggregateType).isEqualTo("UNKNOWN")
    }

    @Test
    fun `every subscribed topic resolves to a domain and a service`() {
        // Scope derived from the config, never hand-kept: a list maintained separately from the
        // thing it covers reads as PASSING when it is short, never as unchecked. Adding a topic to
        // application.yaml without an attribution for it fails here.
        val yaml = File("src/main/resources/application.yaml").readText()
        val topics = Regex("^\\s*topics:\\s*(\\S+)\\s*$", RegexOption.MULTILINE)
            .find(yaml)!!
            .groupValues[1]
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        assertThat(topics).hasSizeGreaterThanOrEqualTo(10)
        for (topic in topics) {
            assertThat(TopicAttribution.aggregateType(topic))
                .describedAs("aggregateType for subscribed topic %s", topic)
                .isNotNull()
            assertThat(TopicAttribution.sourceService(topic))
                .describedAs("sourceService for subscribed topic %s", topic)
                .isNotNull()
        }
    }

    @Test
    fun `a topic that does not follow the fleet convention is honestly unattributable`() {
        // The probe must be able to express the failure: if this returned a value for anything,
        // the assertions above would be vacuous.
        assertThat(TopicAttribution.aggregateType("some.other.topic")).isNull()
        assertThat(TopicAttribution.sourceService(null)).isNull()
        assertThat(TopicAttribution.aggregateType("")).isNull()
    }

    // ── half two: losing attribution must stop being silent ───────────────────────────────────

    private fun envelope(aggregateType: String, eventType: String, sourceService: String) = AnalyticsEnvelope(
        eventId = UUID.randomUUID(),
        aggregateType = aggregateType,
        aggregateId = "a-1",
        aggregateVersion = 0,
        eventType = eventType,
        occurredAt = Instant.EPOCH,
        sourceService = sourceService,
        schemaVersion = 1,
        ingestedAt = Instant.now(),
    )

    @Test
    fun `an unattributable event is counted per field, so a flat-zero counter is an alert expression`() {
        val registry = SimpleMeterRegistry()
        val metrics = IngestAttributionMetrics().apply { this.registry = registry }

        val unresolved = metrics.record(envelope("UNKNOWN", "UNKNOWN", "unknown"), "openbank.mystery.events")

        assertThat(unresolved).containsExactlyInAnyOrder("aggregate_type", "event_type", "source_service")
        for (field in unresolved) {
            assertThat(registry.counter("openbank_analytics_unattributed_total", "field", field).count())
                .describedAs("counter for %s", field)
                .isEqualTo(1.0)
        }
    }

    @Test
    fun `a partially attributed event counts only the field it lost`() {
        val registry = SimpleMeterRegistry()
        val metrics = IngestAttributionMetrics().apply { this.registry = registry }

        val unresolved = metrics.record(envelope("SCA", "UNKNOWN", "openbank-sca-service"), "openbank.sca.events")

        assertThat(unresolved).containsExactly("event_type")
        assertThat(registry.counter("openbank_analytics_unattributed_total", "field", "aggregate_type").count())
            .isZero()
    }

    @Test
    fun `a fully attributed event counts nothing, so the counter stays flat in a healthy pipeline`() {
        val registry = SimpleMeterRegistry()
        val metrics = IngestAttributionMetrics().apply { this.registry = registry }

        val unresolved = metrics.record(
            envelope("SCA", "sca.device.enrolled", "openbank-sca-service"),
            "openbank.sca.events",
        )

        assertThat(unresolved).isEmpty()
        assertThat(registry.find("openbank_analytics_unattributed_total").counters()).isEmpty()
    }

    @Test
    fun `the three sandbox shapes from the issue are all attributable end to end`() {
        val registry = SimpleMeterRegistry()
        val metrics = IngestAttributionMetrics().apply { this.registry = registry }
        val cases = listOf(
            passkey to EventAddress("openbank.sca.events", "d-1", "sca.device.enrolled"),
            document to EventAddress("openbank.documents.document.event", "doc-1", "document.generated"),
            ceremony to EventAddress("openbank.documents.document.event", "cer-1", "ceremony.step.completed"),
        )

        for ((body, address) in cases) {
            val env = consumer.toEnvelope(mapper.readTree(body), address)
            assertThat(metrics.record(env, address.topic))
                .describedAs("unresolved fields for %s", address.topic)
                .isEmpty()
        }
        // and the counter never moved — the reproduction query in #2598 would now return 0 rows
        assertThat(registry.find("openbank_analytics_unattributed_total").counters()).isEmpty()
    }
}
