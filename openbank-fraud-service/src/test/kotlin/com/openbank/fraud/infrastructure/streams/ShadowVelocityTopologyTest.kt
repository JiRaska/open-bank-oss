// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.infrastructure.streams

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.domain.feature.FeatureEvent
import com.openbank.libs.domain.feature.TRANSACTION_INITIATED
import com.openbank.libs.domain.feature.VELOCITY_TXN_COUNT_H1
import com.openbank.libs.domain.feature.VELOCITY_TXN_COUNT_H24
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.TestInputTopic
import org.apache.kafka.streams.TestOutputTopic
import org.apache.kafka.streams.TopologyTestDriver
import org.apache.kafka.streams.test.TestRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Properties
import java.util.UUID

class ShadowVelocityTopologyTest {
    @TempDir
    lateinit var state: Path
    private val mapper = ObjectMapper()
    private val outcomes = mutableMapOf<String, Int>()
    private lateinit var driver: TopologyTestDriver
    private lateinit var input: TestInputTopic<String, String>
    private lateinit var output: TestOutputTopic<String, String>

    @BeforeEach
    fun start() {
        val topology = ShadowVelocityTopology(mapper, Clock.fixed(NOW, ZoneOffset.UTC)) {
            outcomes[it] = outcomes.getOrDefault(it, 0) + 1
        }.build(2)
        val properties = Properties().apply {
            put(StreamsConfig.APPLICATION_ID_CONFIG, "shadow-velocity-topology-test")
            put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092")
            put(StreamsConfig.STATE_DIR_CONFIG, state.toString())
            put(StreamsConfig.STATESTORE_CACHE_MAX_BYTES_CONFIG, 0)
        }
        driver = TopologyTestDriver(topology, properties, NOW)
        input = driver.createInputTopic(ShadowVelocityTopology.INPUT_TOPIC, StringSerializer(), StringSerializer())
        output =
            driver.createOutputTopic(ShadowVelocityTopology.OUTPUT_TOPIC, StringDeserializer(), StringDeserializer())
    }

    @AfterEach
    fun stop() = driver.close()

    @Test
    fun `emitted snapshots satisfy the published version one wire schema`() {
        send("2026-10-25T11:45:00Z")
        val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("docs/asyncapi/openbank-events.yaml")) }
        val doc = Files.newBufferedReader(root.resolve("docs/asyncapi/openbank-events.yaml")).use {
            mapper.valueToTree<JsonNode>(Yaml().load<Any>(it))
        }
        val schema = doc.path("components").path("schemas").path("FraudShadowVelocityPayload")
        assertThat(schema.path("type").asText()).isEqualTo("object")
        val serviceDoc = Files.newBufferedReader(
            root.resolve("openbank-contracts/openbank-fraud-service/asyncapi.yaml"),
        ).use {
            mapper.valueToTree<JsonNode>(Yaml().load<Any>(it))
        }
        assertThat(serviceDoc.path("components").path("schemas").path("FraudShadowVelocityPayload")).isEqualTo(schema)
        snapshots().values.forEach { snapshot ->
            assertThat(snapshot.fieldNames().asSequence().toSet())
                .containsExactlyInAnyOrderElementsOf(schema.path("required").map { it.asText() })
            schema.path("properties").properties().forEach { (name, rule) ->
                assertWireField(snapshot.path(name), rule, name)
            }
        }
    }

    private fun assertWireField(value: JsonNode, rule: JsonNode, name: String) {
        if (rule.path("type").asText() == "integer") {
            assertThat(value.isIntegralNumber).describedAs(name).isTrue()
            if (rule.has("minimum")) assertThat(value.asLong()).isGreaterThanOrEqualTo(rule.path("minimum").asLong())
        } else {
            assertThat(value.isTextual).describedAs(name).isTrue()
        }
        if (rule.has("const")) assertThat(value.asText()).isEqualTo(rule.path("const").asText())
        if (rule.has("enum")) assertThat(value.asText()).isIn(rule.path("enum").map { it.asText() })
        when (rule.path("format").asText()) {
            "uuid" -> UUID.fromString(value.asText())
            "date-time" -> Instant.parse(value.asText())
        }
    }

    @Test
    fun `controlled completed input matches shared H1 and H24 feature definitions`() {
        val times = listOf("2026-10-25T11:45:00Z", "2026-10-25T11:46:00Z")
        times.forEach { send(it) }
        val events = times.map { FeatureEvent(ACCOUNT, TRANSACTION_INITIATED, Instant.parse(it)) }
        val asOf = Instant.parse("2026-10-25T11:47:00Z")
        val latest = snapshots().values.associateBy { it.path("window").asText() }
        assertThat(latest.getValue("H1").path("count").asDouble())
            .isEqualTo(VELOCITY_TXN_COUNT_H1.compute(asOf, events)).isEqualTo(2.0)
        assertThat(latest.getValue("H24").path("count").asDouble())
            .isEqualTo(VELOCITY_TXN_COUNT_H24.compute(asOf, events)).isEqualTo(2.0)
    }

    @Test
    fun `distinct transaction keys and same timestamps count separately after account repartition`() {
        send("2026-10-25T10:30:00Z")
        send("2026-10-25T10:30:00Z")
        val latest = snapshots()
        assertThat(latest.values.map { it.path("count").asLong() }).containsExactlyInAnyOrder(2, 2)
        assertThat(latest.values.map { it.path("accountId").asText() }).containsOnly(ACCOUNT)
        assertThat(
            latest.values.map {
                it.path("sourceTopic").asText()
            },
        ).containsOnly(ShadowVelocityTopology.INPUT_TOPIC)
        assertThat(latest.values.map { it.path("sourceOffset").asLong() }).containsOnly(1)
    }

    @Test
    fun `redelivery at a new offset is deduplicated using outbox identity`() {
        val identity = UUID.randomUUID().toString()
        send("2026-10-25T10:30:00Z", identity)
        send("2026-10-25T10:30:00Z", identity)
        assertThat(snapshots().values.map { it.path("count").asLong() }).containsExactlyInAnyOrder(1, 1)
        assertThat(outcomes["duplicate"]).isEqualTo(1)
    }

    @Test
    fun `out of order events update windows until their end plus grace`() {
        send("2026-10-25T11:45:00Z")
        send("2026-10-25T10:30:00Z")
        val latest = snapshots()
        assertThat(latest.values.filter { it.path("window").asText() == "H1" }.map { it.path("count").asLong() })
            .containsExactlyInAnyOrder(1, 1)
        assertThat(latest.values.single { it.path("window").asText() == "H24" }.path("count").asLong()).isEqualTo(2)
    }

    @Test
    fun `closed hourly window does not prevent still open daily aggregation`() {
        send("2026-10-25T12:30:00Z")
        send("2026-10-25T10:30:00Z")
        val latest = snapshots()
        assertThat(latest.values.count { it.path("window").asText() == "H1" }).isEqualTo(1)
        assertThat(latest.values.single { it.path("window").asText() == "H24" }.path("count").asLong()).isEqualTo(2)
    }

    @Test
    fun `daily buckets use UTC boundaries`() {
        send("2026-10-24T23:59:59Z")
        send("2026-10-25T00:00:00Z")
        assertThat(
            snapshots().values.filter { it.path("window").asText() == "H24" }
                .map { it.path("windowStart").asText() },
        )
            .containsExactlyInAnyOrder("2026-10-24T00:00:00Z", "2026-10-25T00:00:00Z")
    }

    @Test
    fun `late records cannot resurrect a closed daily bucket`() {
        send("2026-10-25T12:00:00Z")
        send("2026-10-23T10:00:00Z")
        assertThat(outcomes["late"]).isEqualTo(1)
        assertThat(snapshots().values.map { it.path("count").asLong() }).containsExactlyInAnyOrder(1, 1)
    }

    @Test
    fun `lifecycle events and synthetic traffic never update feature buckets`() {
        send("2026-10-25T10:00:00Z", kind = "openbank.transactions.transaction.completed")
        send("2026-10-25T10:00:00Z", synthetic = true)
        assertThat(output.isEmpty).isTrue()
        assertThat(outcomes["ignored_type"]).isEqualTo(1)
        assertThat(outcomes["synthetic"]).isEqualTo(1)
    }

    @Test
    fun `invalid event time and future events cannot poison stream watermark`() {
        send("")
        send("2027-01-01T00:00:00Z")
        send("2026-10-25T10:00:00Z")
        assertThat(outcomes["invalid_event_time"]).isEqualTo(1)
        assertThat(outcomes["invalid_identity"]).isEqualTo(1)
        assertThat(snapshots().values.map { it.path("count").asLong() }).containsExactlyInAnyOrder(1, 1)
    }

    @Test
    fun `missing outbox identity and malformed JSON are rejected without fallback`() {
        input.pipeInput(TestRecord("transaction", "{}", headers(null), NOW))
        input.pipeInput(TestRecord("transaction", "{", headers(UUID.randomUUID().toString()), NOW))
        assertThat(output.isEmpty).isTrue()
        assertThat(outcomes["invalid_identity"]).isEqualTo(1)
        assertThat(outcomes["invalid_payload"]).isEqualTo(1)
    }

    private fun send(
        time: String,
        identity: String = UUID.randomUUID().toString(),
        kind: String = ShadowVelocityTopology.INPUT_TOPIC,
        synthetic: Boolean = false,
    ) {
        // Real producer shape: body DomainEvent ID and outbox ce-id are independent UUIDs.
        val body = mapper.createObjectNode().put("eventId", UUID.randomUUID().toString())
            .put("eventType", "TransactionInitiated").put("sourceAccountId", ACCOUNT).put("occurredAt", time)
        val headers = headers(identity, kind)
        if (synthetic) headers.add(OutboxKafkaHeaders.HEADER_SYNTHETIC, "true".toByteArray())
        input.pipeInput(TestRecord(UUID.randomUUID().toString(), body.toString(), headers, NOW))
    }

    private fun headers(identity: String?, kind: String = ShadowVelocityTopology.INPUT_TOPIC): RecordHeaders =
        RecordHeaders().apply {
            add(OutboxKafkaHeaders.HEADER_EVENT_TYPE, kind.toByteArray())
            identity?.let { add(OutboxKafkaHeaders.HEADER_EVENT_ID, it.toByteArray()) }
        }

    private fun snapshots(): Map<String, JsonNode> = output.readKeyValuesToList().associate {
        it.key to
            mapper.readTree(it.value)
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-10-25T12:30:00Z")
        const val ACCOUNT = "00000000-0000-0000-0000-000000000101"
    }
}
