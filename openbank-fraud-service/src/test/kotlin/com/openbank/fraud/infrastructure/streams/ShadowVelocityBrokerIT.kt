// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.infrastructure.streams

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import com.openbank.libs.testing.containers.DockerRequirement
import com.openbank.libs.testing.evidence.TestInfrastructureEvidence
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.apache.kafka.streams.KafkaStreams
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.redpanda.RedpandaContainer
import org.testcontainers.utility.DockerImageName
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Broker-backed proof of account repartition, EOS visibility and changelog restoration. */
class ShadowVelocityBrokerIT {
    @TempDir
    lateinit var directory: Path
    private val mapper = ObjectMapper()
    private val outcomes = ConcurrentHashMap<String, AtomicInteger>()
    private val sourceMetadata = mutableMapOf<String, RecordMetadata>()
    private val latest = mutableMapOf<String, JsonNode>()
    private val applicationId = "openbank-fraud-shadow-velocity-it-${UUID.randomUUID()}"

    @Test
    @Timeout(value = 360, unit = TimeUnit.SECONDS)
    fun `repartitioned counts and event identities restore from changelog after fresh state restart`() {
        withBroker { bootstrap ->
            withClients(bootstrap) { producer, consumer ->
                val first = Signal()
                withWorker(bootstrap, directory.resolve("first")) { worker ->
                    sourceMetadata[first.eventId] = send(producer, first, 0)
                    val second = Signal()
                    sourceMetadata[second.eventId] = send(producer, second, 1)
                    awaitCounts(consumer, worker, 2, expectedDuplicates = 0)
                    assertInternalTopics(bootstrap)
                }
                drainCommitted(consumer, 2)
                assertThat(outcomes.getValue("accepted").get()).isEqualTo(2)
                val restoredState = directory.resolve("restored")
                assertThat(Files.exists(restoredState)).isFalse()
                withWorker(bootstrap, restoredState) { worker ->
                    val redelivery = send(producer, first, 0)
                    assertThat(redelivery.offset()).isGreaterThan(sourceMetadata.getValue(first.eventId).offset())
                    val third = Signal()
                    sourceMetadata[third.eventId] = send(producer, third, 1)
                    awaitCounts(consumer, worker, 3, expectedDuplicates = 1)
                }
                // Closing commits all remaining work; consume through the stable end offsets so
                // a transient count=3 cannot hide a later erroneous duplicate count=4.
                drainCommitted(consumer, 3)
                assertThat(outcomes.getValue("duplicate").get()).isEqualTo(1)
                // Restoration must resume committed source offsets, not reconstruct the totals
                // by replaying the original input into an empty deduplication store.
                assertThat(outcomes.getValue("accepted").get()).isEqualTo(4)
            }
        }
    }

    private fun assertInternalTopics(bootstrap: String) {
        val admin = AdminClient.create(mapOf("bootstrap.servers" to bootstrap, "request.timeout.ms" to "10000"))
        try {
            val topics = admin.listTopics().names().get(30, TimeUnit.SECONDS)
                .filter { it.startsWith("$applicationId-") }
            assertThat(topics).containsExactlyInAnyOrderElementsOf(
                listOf(
                    "by-account-repartition",
                    "event-identities-changelog",
                    "velocity-h1-changelog",
                    "velocity-h24-changelog",
                )
                    .map { "$applicationId-$it" },
            )
        } finally {
            admin.close(Duration.ofSeconds(10))
        }
    }

    private fun withBroker(test: (String) -> Unit) {
        DockerRequirement.require(ci = "true")
        val broker = RedpandaContainer(
            DockerImageName.parse(REDPANDA_IMAGE)
                .asCompatibleSubstituteFor("docker.redpanda.com/redpandadata/redpanda"),
        ).withStartupTimeout(Duration.ofSeconds(60))
        val scope = UUID.randomUUID().toString()
        try {
            broker.start()
            TestInfrastructureEvidence.record(
                "redpanda",
                REDPANDA_IMAGE,
                "started",
                scope,
                containerId = broker.containerId,
            )
            // Testcontainers starts Redpanda in dev-container mode for one broker. Explicit RF=1
            // below covers both public topics and Streams' internal changelog/repartition topics.
            createTopics(broker.bootstrapServers)
            test(broker.bootstrapServers)
        } finally {
            val containerId = broker.containerId
            broker.stop()
            if (containerId != null) {
                TestInfrastructureEvidence.record(
                    "redpanda",
                    REDPANDA_IMAGE,
                    "stopped",
                    scope,
                    containerId = containerId,
                )
            }
        }
    }

    private fun createTopics(bootstrap: String) {
        val admin = AdminClient.create(mapOf("bootstrap.servers" to bootstrap, "request.timeout.ms" to "10000"))
        try {
            admin.createTopics(
                listOf(
                    NewTopic(ShadowVelocityTopology.INPUT_TOPIC, PARTITIONS, 1.toShort()),
                    NewTopic(ShadowVelocityTopology.OUTPUT_TOPIC, PARTITIONS, 1.toShort()),
                ),
            ).all().get(30, TimeUnit.SECONDS)
        } finally {
            admin.close(Duration.ofSeconds(10))
        }
    }

    private fun withClients(
        bootstrap: String,
        test: (KafkaProducer<String, String>, KafkaConsumer<String, String>) -> Unit,
    ) {
        val producer = KafkaProducer<String, String>(
            mapOf(
                "bootstrap.servers" to bootstrap,
                "acks" to "all",
                "max.block.ms" to "10000",
                "delivery.timeout.ms" to "15000",
                "request.timeout.ms" to "10000",
                "key.serializer" to StringSerializer::class.java,
                "value.serializer" to StringSerializer::class.java,
            ),
        )
        try {
            val consumer = KafkaConsumer<String, String>(
                mapOf(
                    "bootstrap.servers" to bootstrap,
                    "isolation.level" to "read_committed",
                    "enable.auto.commit" to "false",
                    "auto.offset.reset" to "earliest",
                    "default.api.timeout.ms" to "10000",
                    "request.timeout.ms" to "10000",
                    "key.deserializer" to StringDeserializer::class.java,
                    "value.deserializer" to StringDeserializer::class.java,
                ),
            )
            try {
                consumer.assign((0 until PARTITIONS).map { TopicPartition(ShadowVelocityTopology.OUTPUT_TOPIC, it) })
                consumer.seekToBeginning(consumer.assignment())
                test(producer, consumer)
            } finally {
                consumer.close(Duration.ofSeconds(10))
            }
        } finally {
            producer.close(Duration.ofSeconds(10))
        }
    }

    private fun withWorker(bootstrap: String, stateDirectory: Path, test: (Worker) -> Unit) {
        val properties = Properties().apply {
            put(StreamsConfig.APPLICATION_ID_CONFIG, applicationId)
            put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap)
            put(StreamsConfig.STATE_DIR_CONFIG, stateDirectory.toString())
            put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, StreamsConfig.EXACTLY_ONCE_V2)
            put(StreamsConfig.NUM_STREAM_THREADS_CONFIG, 1)
            put(StreamsConfig.REPLICATION_FACTOR_CONFIG, 1)
            put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 100)
            put(StreamsConfig.STATESTORE_CACHE_MAX_BYTES_CONFIG, 0)
            put("auto.offset.reset", "earliest")
            put("consumer.default.api.timeout.ms", 10000)
            put("producer.max.block.ms", 10000)
        }
        val topology = ShadowVelocityTopology(mapper, Clock.fixed(EVENT_TIME, ZoneOffset.UTC)) {
            outcomes.computeIfAbsent(it) { AtomicInteger() }.incrementAndGet()
        }.build(PARTITIONS)
        val worker = Worker(KafkaStreams(topology, properties))
        try {
            worker.start()
            test(worker)
        } finally {
            worker.close()
        }
    }

    private fun send(producer: KafkaProducer<String, String>, signal: Signal, partition: Int): RecordMetadata {
        val payload = mapper.createObjectNode()
            .put("eventId", signal.bodyId)
            .put("eventType", "TransactionInitiated")
            .put("aggregateId", signal.transactionId)
            .put("sourceAccountId", ACCOUNT)
            .put("occurredAt", EVENT_TIME.toString()).toString()
        val record = ProducerRecord(
            ShadowVelocityTopology.INPUT_TOPIC,
            partition,
            EVENT_TIME.toEpochMilli(),
            signal.transactionId,
            payload,
        )
        record.headers().add(OutboxKafkaHeaders.HEADER_EVENT_ID, signal.eventId.toByteArray(Charsets.UTF_8))
        record.headers().add(
            OutboxKafkaHeaders.HEADER_EVENT_TYPE,
            ShadowVelocityTopology.INPUT_TOPIC.toByteArray(Charsets.UTF_8),
        )
        return producer.send(record).get(20, TimeUnit.SECONDS)
    }

    private fun awaitCounts(
        consumer: KafkaConsumer<String, String>,
        worker: Worker,
        count: Long,
        expectedDuplicates: Int,
    ) {
        val deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos()
        while (System.nanoTime() < deadline) {
            worker.assertHealthy()
            observe(consumer, count)
            if (hasCounts(count) && (outcomes["duplicate"]?.get() ?: 0) == expectedDuplicates) return
        }
        assertThat(hasCounts(count)).describedAs("both committed windows reach count %s", count).isTrue()
        assertThat(outcomes["duplicate"]?.get() ?: 0).isEqualTo(expectedDuplicates)
    }

    private fun drainCommitted(consumer: KafkaConsumer<String, String>, count: Long) {
        val ends = consumer.endOffsets(consumer.assignment(), Duration.ofSeconds(10))
        val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        while (ends.any { (partition, offset) -> consumer.position(partition) < offset }) {
            check(System.nanoTime() < deadline) { "Committed shadow output did not drain before deadline" }
            observe(consumer, count)
        }
        assertThat(hasCounts(count)).isTrue()
    }

    private fun observe(consumer: KafkaConsumer<String, String>, maximumCount: Long) {
        for (record in consumer.poll(Duration.ofMillis(250))) {
            val body = mapper.readTree(record.value())
            val window = body.path("window").asText()
            assertThat(window).isIn("H1", "H24")
            assertThat(body.path("accountId").asText()).isEqualTo(ACCOUNT)
            assertThat(body.path("count").asLong()).isBetween(1, maximumCount)
            val source = sourceMetadata.getValue(body.path("eventId").asText())
            assertThat(body.path("sourceTopic").asText()).isEqualTo(ShadowVelocityTopology.INPUT_TOPIC)
            assertThat(body.path("sourcePartition").asInt()).isEqualTo(source.partition())
            assertThat(body.path("sourceOffset").asLong()).isEqualTo(source.offset())
            latest[window] = body
        }
    }

    private fun hasCounts(count: Long): Boolean =
        latest.keys == setOf("H1", "H24") && latest.values.all { it.path("count").asLong() == count }

    private data class Signal(
        val eventId: String = UUID.randomUUID().toString(),
        val bodyId: String = UUID.randomUUID().toString(),
        val transactionId: String = UUID.randomUUID().toString(),
    )

    private class Worker(private val streams: KafkaStreams) {
        private val running = CountDownLatch(1)
        private val failure = AtomicReference<Throwable>()

        fun start() {
            streams.setStateListener { next, _ ->
                if (next == KafkaStreams.State.RUNNING) running.countDown()
            }
            streams.setUncaughtExceptionHandler {
                failure.compareAndSet(null, it)
                running.countDown()
                StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.SHUTDOWN_CLIENT
            }
            streams.start()
            assertThat(running.await(60, TimeUnit.SECONDS))
                .describedAs("Streams reaches RUNNING after restoration").isTrue()
            assertHealthy()
        }

        fun assertHealthy() {
            assertThat(failure.get()).describedAs("Streams background failure").isNull()
        }

        fun close() {
            assertThat(streams.close(Duration.ofSeconds(30)))
                .describedAs("Streams closes and commits within budget").isTrue()
            assertHealthy()
        }
    }

    private companion object {
        const val REDPANDA_IMAGE = "redpandadata/redpanda:v24.1.2"
        const val PARTITIONS = 2
        const val ACCOUNT = "00000000-0000-0000-0000-000000000123"
        val EVENT_TIME: Instant = Instant.parse("2026-10-25T10:30:00Z")
    }
}
