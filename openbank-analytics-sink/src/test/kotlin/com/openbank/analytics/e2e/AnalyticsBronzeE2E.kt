// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.e2e

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.errors.TopicExistsException
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * Real-broker E2E for the whole `analytics-events-in` bronze pipeline — the single consumer that
 * drains all seventeen outbox topics into the analytics warehouse (ADR-0022). Before this suite the
 * module had boot smokes and mocked unit coverage, but the load-bearing WIRE behaviour was never
 * exercised end to end: body-first vs `ce-type`-header type resolution, topic/key attribution
 * fallback, PII masking before the >=10-year layer, the party-bearing quarantine guard, the
 * retry-then-nack contract with the connector's `dead-letter-queue` strategy, and the ADR-0252
 * synthetic taint header. All of it is asserted here against a real Redpanda broker and the real
 * ClickHouse HTTP path (capturing fake server — see [FakeClickHouseResource]).
 *
 * Negative assertions follow the sentinel discipline: "not written" is only observed after a
 * well-formed record published AFTER the message under test lands in bronze, which proves the
 * consumer provably moved past it.
 *
 * Tagged `integration` per this module's convention (see `build.gradle.kts`): excluded from the
 * offline default `test` task, run with `-PwithDocker`.
 */
@QuarkusTest
@TestProfile(AnalyticsBronzeE2EProfile::class)
@Tag("integration")
class AnalyticsBronzeE2E {

    private val mapper = ObjectMapper()

    // ── scenarios ────────────────────────────────────────────────────────────────

    @Test
    fun `an AccountCreated is attributed from its body and written to bronze with PII masked`() {
        val eventId = UUID.randomUUID()
        val aggregateId = UUID.randomUUID()
        val partyId = UUID.randomUUID()
        val rawEmail = "jana.novakova-${UUID.randomUUID()}@example.org"

        publish(
            ACCOUNT_TOPIC,
            key = partyId.toString(),
            payload = accountCreated(eventId, aggregateId, partyId, rawEmail),
        )

        val row = awaitBronzeRow("event_id", eventId.toString())
        assertThat(row["aggregate_type"]).isEqualTo("ACCOUNT")
        assertThat(row["aggregate_id"]).isEqualTo(aggregateId.toString())
        assertThat(row["event_type"]).isEqualTo("AccountCreated")
        assertThat(row["source_service"]).isEqualTo("account-service")
        assertThat(row["schema_version"]).isEqualTo(1)
        assertThat(row["synthetic"]).isEqualTo(false)
        assertThat(row["record_hash"] as String).isNotBlank()
        val payload = row["payload"] as String
        assertThat(payload).contains("\"email\"").contains("\"currency\":\"CZK\"")
        assertThat(payload)
            .describedAs("raw PII must never reach the >=10-year bronze layer")
            .doesNotContain(rawEmail)
        assertThat(payload).describedAs("the IBAN is masked too").doesNotContain(IBAN)
    }

    @Test
    fun `an event carrying no payload eventType is typed from the ce-type transport header`() {
        val cardId = UUID.randomUUID()
        val partyId = UUID.randomUUID()

        // CardIssued has NO eventType field of its own — the type travels only as the outbox
        // ce-type header. Byte-for-byte what card-service's outbox emits.
        publish(
            CARDS_TOPIC,
            key = cardId.toString(),
            headers = mapOf("ce-type" to "CardIssued"),
            payload = """{"cardId":"$cardId","partyId":"$partyId","occurredAt":"${Instant.now()}"}""",
        )

        val row = awaitBronzeRow("aggregate_id", cardId.toString())
        assertThat(row["event_type"]).isEqualTo("CardIssued")
        assertThat(row["aggregate_type"]).isEqualTo("CARD")
    }

    @Test
    fun `an AccountCreated without a party is quarantined into dead letters, not written to bronze`() {
        val eventId = UUID.randomUUID()
        val aggregateId = UUID.randomUUID()

        publish(
            ACCOUNT_TOPIC,
            key = aggregateId.toString(),
            payload = accountCreatedNoParty(eventId, aggregateId),
        )

        val dead = awaitDeadLetterRow("AccountCreated carries no partyId")
        assertThat(dead["raw_payload"] as String).contains(eventId.toString())
        assertThat(dead["error"] as String).contains("AccountCreated carries no partyId")
        assertThat(dead["content_hash"] as String).isNotBlank()

        val sentinelId = UUID.randomUUID()
        publishHealthyAccountCreated(sentinelId)
        awaitBronzeRow("event_id", sentinelId.toString())
        assertThat(bronzeBodies())
            .describedAs("the party-less event must not land in bronze after the consumer moved past it")
            .noneMatch { it.contains(eventId.toString()) }
        assertThat(dlqRecordsWithKey(aggregateId.toString()))
            .describedAs("quarantine ACKs — the record must never reach the broker DLQ topic")
            .isEmpty()
    }

    @Test
    fun `a malformed payload is quarantined and acked, never parked on the broker DLQ topic`() {
        val key = UUID.randomUUID().toString()
        // Truncated mid-object (ends after a colon) — invalid JSON, and the trailing-char choice
        // avoids a run of quotes at the raw-string boundary, which Kotlin would parse as the terminator.
        val raw = """{"eventId":"$key","eventType":"AccountCreated","payload":{"iban":"$IBAN","unterminated":"""

        publish(ACCOUNT_TOPIC, key = key, payload = raw)

        val dead = awaitDeadLetterRow(key)
        assertThat(dead["raw_payload"] as String).isEqualTo(raw)
        assertThat(dead["error"] as String).isNotBlank()

        val sentinelId = UUID.randomUUID()
        publishHealthyAccountCreated(sentinelId)
        awaitBronzeRow("event_id", sentinelId.toString())
        assertThat(dlqRecordsWithKey(key))
            .describedAs("a poison pill is unretryable — acked, so the broker DLQ stays empty for it")
            .isEmpty()
    }

    @Test
    fun `a failing bronze write nacks and the connector parks the record on the broker DLQ topic`() {
        val eventId = UUID.randomUUID()
        val partyId = UUID.randomUUID()
        val key = UUID.randomUUID().toString()
        FakeClickHouseStore.failBronzeInserts.set(true)
        try {
            publish(ACCOUNT_TOPIC, key = key, payload = accountCreated(eventId, UUID.randomUUID(), partyId, null))

            val parked = dlqRecordsWithKey(key, timeout = Duration.ofSeconds(45))
            assertThat(parked)
                .describedAs("nack must hand the record to the dead-letter-queue strategy on the real broker")
                .hasSize(1)
            assertThat(parked.first()).contains(eventId.toString())
            assertThat(bronzeBodies()).noneMatch { it.contains(eventId.toString()) }
        } finally {
            FakeClickHouseStore.failBronzeInserts.set(false)
        }

        // Recovery: with ClickHouse healthy again the same consumer writes a new event to bronze.
        val recoveryId = UUID.randomUUID()
        publishHealthyAccountCreated(recoveryId)
        awaitBronzeRow("event_id", recoveryId.toString())
    }

    @Test
    fun `the synthetic taint header marks the bronze row synthetic, and only an exact true`() {
        val taintedId = UUID.randomUUID()
        val fuzzyId = UUID.randomUUID()
        val partyA = UUID.randomUUID()
        val partyB = UUID.randomUUID()

        publish(
            ACCOUNT_TOPIC,
            key = partyA.toString(),
            headers = mapOf(SYNTHETIC_HEADER to "true"),
            payload = accountCreated(taintedId, UUID.randomUUID(), partyA, null),
        )
        publish(
            ACCOUNT_TOPIC,
            key = partyB.toString(),
            headers = mapOf(SYNTHETIC_HEADER to "yes"),
            payload = accountCreated(fuzzyId, UUID.randomUUID(), partyB, null),
        )

        assertThat(awaitBronzeRow("event_id", taintedId.toString())["synthetic"]).isEqualTo(true)
        // Fail-to-REAL: only an exact `true` taints; `yes` must not suppress a row from FINREP etc.
        assertThat(awaitBronzeRow("event_id", fuzzyId.toString())["synthetic"]).isEqualTo(false)
    }

    @Test
    fun `an event with no body identifiers attributes from its topic and record key`() {
        val key = UUID.randomUUID().toString()

        publish(
            SCA_TOPIC,
            key = key,
            payload = """{"eventType":"DeviceEnrolled","occurredAt":"${Instant.now()}",""" +
                """"payload":{"deviceId":"device-${UUID.randomUUID()}"}}""",
        )

        val row = awaitBronzeRow("aggregate_id", key)
        assertThat(row["aggregate_type"]).isEqualTo("SCA")
        assertThat(row["event_type"]).isEqualTo("DeviceEnrolled")
        assertThat(row["source_service"]).isEqualTo("sca-service")
    }

    // ── Kafka producer / broker helpers ──────────────────────────────────────────

    private fun publish(topic: String, key: String, headers: Map<String, String>? = null, payload: String) {
        val props = mapOf(
            "bootstrap.servers" to bootstrapServers(),
            "key.serializer" to StringSerializer::class.qualifiedName,
            "value.serializer" to StringSerializer::class.qualifiedName,
            "acks" to "all",
        )
        KafkaProducer<String, String>(props).use { p ->
            val record = ProducerRecord<String, String>(topic, key, payload)
            headers?.forEach { (k, v) -> record.headers().add(k, v.toByteArray()) }
            p.send(record).get(10, TimeUnit.SECONDS)
        }
    }

    private fun publishHealthyAccountCreated(eventId: UUID) {
        // Carries a partyId on purpose: AccountCreated is party-bearing (see PARTY_BEARING_EVENT_TYPES),
        // so a party-less sentinel would itself be quarantined and the await would time out.
        publish(
            ACCOUNT_TOPIC,
            key = UUID.randomUUID().toString(),
            payload = accountCreated(eventId, UUID.randomUUID(), UUID.randomUUID(), null),
        )
    }

    /** Records currently parked on the broker DLQ topic carrying [key]; polls up to [timeout]. */
    private fun dlqRecordsWithKey(key: String, timeout: Duration = Duration.ofSeconds(10)): List<String> {
        val props = mapOf(
            "bootstrap.servers" to bootstrapServers(),
            "group.id" to "dlq-read-${UUID.randomUUID()}",
            "auto.offset.reset" to "earliest",
            "enable.auto.commit" to "false",
            "key.deserializer" to StringDeserializer::class.qualifiedName,
            "value.deserializer" to StringDeserializer::class.qualifiedName,
        )
        KafkaConsumer<String, String>(props).use { consumer ->
            consumer.subscribe(listOf(DLQ_TOPIC))
            val found = mutableListOf<String>()
            val deadline = Instant.now().plus(timeout)
            while (Instant.now().isBefore(deadline) && found.isEmpty()) {
                collectMatching(consumer, key, found)
            }
            return found
        }
    }

    private fun collectMatching(consumer: KafkaConsumer<String, String>, key: String, found: MutableList<String>) {
        consumer.poll(Duration.ofMillis(500)).filter { it.key() == key }.mapTo(found) { it.value() }
    }

    // ── fake-ClickHouse read-back ────────────────────────────────────────────────

    private fun awaitBronzeRow(column: String, value: String): Map<String, Any?> =
        awaitRow("bronze_events", column, value, Duration.ofSeconds(30))

    private fun awaitDeadLetterRow(snippet: String): Map<String, Any?> {
        val deadline = Instant.now().plus(Duration.ofSeconds(30))
        while (Instant.now().isBefore(deadline)) {
            val row = FakeClickHouseStore.rows("dead_letter_events")
                .mapNotNull { parseRow(it.body) }
                .firstOrNull { snippet in (it["raw_payload"] as String) || snippet in (it["error"] as String) }
            if (row != null) return row
            Thread.sleep(100)
        }
        throw AssertionError("no dead_letter_events row matching '$snippet' within 30s")
    }

    private fun awaitRow(table: String, column: String, value: String, timeout: Duration): Map<String, Any?> {
        val deadline = Instant.now().plus(timeout)
        while (Instant.now().isBefore(deadline)) {
            val row = FakeClickHouseStore.rows(table)
                .mapNotNull { parseRow(it.body) }
                .firstOrNull { it[column] == value }
            if (row != null) return row
            Thread.sleep(100)
        }
        throw AssertionError("no $table row with $column=$value within ${timeout.seconds}s")
    }

    private fun parseRow(body: String): Map<String, Any?>? = runCatching {
        @Suppress("UNCHECKED_CAST")
        mapper.readValue(body.lineSequence().first(), Map::class.java) as Map<String, Any?>
    }.getOrNull()

    private fun bronzeBodies(): List<String> = FakeClickHouseStore.rows("bronze_events").map { it.body }

    // ── fixtures ─────────────────────────────────────────────────────────────────

    private fun accountCreated(eventId: UUID, aggregateId: UUID, partyId: UUID, email: String?): String {
        val emailField = email?.let { ",\"email\":\"$it\"" }.orEmpty()
        return """{"eventId":"$eventId","aggregateId":"$aggregateId","aggregateType":"Account",""" +
            """"eventType":"AccountCreated","version":0,"occurredAt":"${Instant.now()}",""" +
            """"sourceService":"account-service","traceId":"${UUID.randomUUID()}",""" +
            """"payload":{"partyId":"$partyId","accountNumber":"$IBAN","currency":"CZK"$emailField}}"""
    }

    private fun accountCreatedNoParty(eventId: UUID, aggregateId: UUID): String =
        """{"eventId":"$eventId","aggregateId":"$aggregateId","aggregateType":"Account",""" +
            """"eventType":"AccountCreated","version":0,"occurredAt":"${Instant.now()}",""" +
            """"sourceService":"account-service",""" +
            """"payload":{"accountNumber":"$IBAN","currency":"CZK"}}"""

    companion object {
        private const val ACCOUNT_TOPIC = "openbank.accounts.account.created"
        private const val CARDS_TOPIC = "openbank.cards.events"
        private const val SCA_TOPIC = "openbank.sca.events"
        private const val DLQ_TOPIC = "openbank.dlq.analytics-sink.analytics-events-in"
        private const val SYNTHETIC_HEADER = "x-openbank-synthetic"
        private const val IBAN = "CZ6508000000192000145399"

        private val CONSUMED_TOPICS = listOf(
            "openbank.accounts.account.created",
            "openbank.transactions.transaction.initiated",
            "openbank.balance.events",
            "openbank.party.events",
            "openbank.kyc.events",
            "openbank.consent.events",
            "openbank.sca.events",
            "openbank.documents.document.event",
            "openbank.onboarding.funnel.events",
            "openbank.feedback.events",
            "openbank.engagement.events",
            "openbank.credit.funnel.events",
            "openbank.cards.events",
            "openbank.lending.events",
            "openbank.standing-orders.order.event",
            "openbank.fx.conversion.completed",
            "openbank.kyb.events",
        )

        @BeforeAll
        @JvmStatic
        fun ensureTopics() {
            FakeClickHouseStore.clear()
            Admin.create(mapOf("bootstrap.servers" to bootstrapServers())).use { admin ->
                val existing = admin.listTopics().names().get(30, TimeUnit.SECONDS)
                val wanted = (CONSUMED_TOPICS + DLQ_TOPIC).filter { it !in existing }
                wanted.forEach { topic -> createIgnoringRace(admin, topic) }
            }
        }

        private fun createIgnoringRace(admin: Admin, topic: String) {
            try {
                admin.createTopics(listOf(NewTopic(topic, 1, 1))).all().get(30, TimeUnit.SECONDS)
            } catch (e: ExecutionException) {
                if (e.cause !is TopicExistsException) throw e
            }
        }

        private fun bootstrapServers(): String =
            ConfigProvider.getConfig().getValue("kafka.bootstrap.servers", String::class.java)
    }
}
