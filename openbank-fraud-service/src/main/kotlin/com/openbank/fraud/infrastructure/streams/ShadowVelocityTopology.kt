// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.infrastructure.streams

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.Topology
import org.apache.kafka.streams.kstream.Consumed
import org.apache.kafka.streams.kstream.Grouped
import org.apache.kafka.streams.kstream.KStream
import org.apache.kafka.streams.kstream.Materialized
import org.apache.kafka.streams.kstream.Produced
import org.apache.kafka.streams.kstream.Repartitioned
import org.apache.kafka.streams.kstream.TimeWindows
import org.apache.kafka.streams.processor.api.ContextualFixedKeyProcessor
import org.apache.kafka.streams.processor.api.FixedKeyProcessorSupplier
import org.apache.kafka.streams.processor.api.FixedKeyRecord
import org.apache.kafka.streams.processor.api.ProcessorSupplier
import org.apache.kafka.streams.state.Stores
import org.apache.kafka.streams.state.WindowStore
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Independent H1/H24 bucket counters. Nothing in the verdict path reads these outputs. */
class ShadowVelocityTopology(
    private val mapper: ObjectMapper,
    private val clock: Clock,
    private val outcome: (String) -> Unit,
) {
    fun build(partitions: Int): Topology {
        require(partitions in 1..MAX_PARTITIONS)
        val builder = StreamsBuilder()
        builder.addStateStore(
            Stores.windowStoreBuilder(
                Stores.persistentWindowStore(DEDUP_STORE, RETENTION, RETENTION, false),
                Serdes.String(),
                Serdes.Long(),
            ),
        )
        val parser = ShadowSignalParser(mapper, clock)
        val consumed = Consumed.with(Serdes.String(), Serdes.String()).withTimestampExtractor { record, partitionTime ->
            // Invalid input is rejected/counts in the decoder; it cannot advance event time.
            parser.decode(record.headers(), record.value() as? String) {}?.occurredAt?.toEpochMilli()
                ?: partitionTime.coerceAtLeast(0)
        }
        val signals = builder.stream(INPUT_TOPIC, consumed)
            .process(ProcessorSupplier { ShadowSignalDecoder(mapper, clock, outcome) })
            // Source keys identify transactions, so grouping requires an actual account repartition.
            .selectKey { _, value -> mapper.readTree(value).path("accountId").asText() }
            .repartition(
                Repartitioned.with(
                    Serdes.String(),
                    Serdes.String(),
                ).withName("by-account").withNumberOfPartitions(partitions),
            )
            .processValues(FixedKeyProcessorSupplier { Deduplicate(mapper, outcome) }, DEDUP_STORE)
        aggregate(signals, "H1", Duration.ofHours(1))
        aggregate(signals, "H24", Duration.ofDays(1))
        return builder.build()
    }

    private fun aggregate(signals: KStream<String, String>, name: String, size: Duration) {
        signals.groupByKey(Grouped.with(Serdes.String(), Serdes.String()))
            .windowedBy(TimeWindows.ofSizeAndGrace(size, GRACE))
            .aggregate(
                { "{\"count\":0}" },
                { _, signal, previous ->
                    mapper.readTree(signal).deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
                        .put("count", mapper.readTree(previous).path("count").asLong() + 1).toString()
                },
                Materialized.`as`<String, String, WindowStore<org.apache.kafka.common.utils.Bytes, ByteArray>>(
                    "velocity-${name.lowercase()}",
                )
                    .withKeySerde(Serdes.String()).withValueSerde(Serdes.String()).withRetention(size.plus(GRACE)),
            ).toStream().mapValues { window, aggregate ->
                val value = mapper.readTree(aggregate)
                mapper.writeValueAsString(
                    FraudShadowVelocity(
                        accountId = value.path("accountId").asText(),
                        eventId = value.path("eventId").asText(),
                        occurredAt = value.path("occurredAt").asText(),
                        sourceTopic = value.path("sourceTopic").asText(),
                        sourcePartition = value.path("sourcePartition").asInt(),
                        sourceOffset = value.path("sourceOffset").asLong(),
                        window = name,
                        windowStart = Instant.ofEpochMilli(window.window().start()).toString(),
                        windowEndExclusive = Instant.ofEpochMilli(window.window().end()).toString(),
                        count = value.path("count").asLong(),
                    ),
                )
            }.selectKey { window, _ -> "${window.key()}:$name:${window.window().start()}" }
            .to(OUTPUT_TOPIC, Produced.with(Serdes.String(), Serdes.String()))
    }

    private class Deduplicate(private val mapper: ObjectMapper, private val outcome: (String) -> Unit) :
        ContextualFixedKeyProcessor<String, String, String>() {
        override fun process(record: FixedKeyRecord<String, String>) {
            val watermark = context().currentStreamTimeMs()
            val dayEnd = Instant.ofEpochMilli(record.timestamp()).truncatedTo(ChronoUnit.DAYS).plus(Duration.ofDays(1))
            if (watermark >= dayEnd.plus(GRACE).toEpochMilli()) return outcome("late")
            val seen = context().getStateStore<WindowStore<String, Long>>(DEDUP_STORE)
            val identity = "${record.key()}:${mapper.readTree(record.value()).path("eventId").asText()}"
            val duplicate = seen.fetch(identity, Instant.EPOCH, Instant.ofEpochMilli(Long.MAX_VALUE)).use {
                it.hasNext()
            }
            if (duplicate) return outcome("duplicate")
            seen.put(identity, record.timestamp(), record.timestamp())
            context().forward(record)
        }
    }

    companion object {
        const val INPUT_TOPIC = "openbank.transactions.transaction.initiated"
        const val OUTPUT_TOPIC = "openbank.fraud.shadow.velocity.v1"
        private const val DEDUP_STORE = "event-identities"
        private const val MAX_PARTITIONS = 32
        val GRACE: Duration = Duration.ofHours(1)
        private val RETENTION: Duration = Duration.ofDays(2)
    }
}
