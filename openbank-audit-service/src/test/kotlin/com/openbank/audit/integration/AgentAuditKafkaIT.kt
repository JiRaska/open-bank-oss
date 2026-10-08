// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.integration

import com.openbank.audit.it.AgentAuditKafkaTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Broker -> dedicated consumer -> real PostgreSQL, including offset and DLQ outcomes. */
@QuarkusTest
@TestProfile(AgentAuditKafkaIT.RealKafkaProfile::class)
class AgentAuditKafkaIT {
    class RealKafkaProfile : QuarkusTestProfile {
        override fun disableGlobalTestResources() = true
        override fun testResources() =
            listOf(QuarkusTestProfile.TestResourceEntry(AgentAuditKafkaTestResource::class.java))
    }

    @Test
    @Suppress("NestedBlockDepth") // The table lock, broker clients, and cleanup form one scenario.
    fun `offset follows durable insert and replay preserves one row while failed insert reaches DLQ`() {
        val bootstrap = config("kafka.bootstrap.servers")
        val first = UUID.randomUUID()
        val rejected = UUID.randomUUID()
        val firstPayload = payload(first, "resource-$first")
        val rejectedPayload = payload(rejected, "resource-$rejected")
        KafkaProducer<String, String>(
            mapOf(
                "bootstrap.servers" to bootstrap,
                "key.serializer" to "org.apache.kafka.common.serialization.StringSerializer",
                "value.serializer" to "org.apache.kafka.common.serialization.StringSerializer",
                "acks" to "all",
            ),
        ).use { producer ->
            Admin.create(mapOf("bootstrap.servers" to bootstrap)).use { admin ->
                // A separate SQL transaction prevents the INSERT from committing. The channel
                // must not commit its Kafka offset while this lock is held.
                var firstOffset = -1L
                jdbc().use { lock ->
                    lock.autoCommit = false
                    lock.createStatement().use { it.execute("LOCK TABLE audit_entries IN ACCESS EXCLUSIVE MODE") }
                    try {
                        val record = producer.send(
                            ProducerRecord(AgentAuditKafkaTestResource.INPUT_TOPIC, first.toString(), firstPayload),
                        )
                            .get(15, TimeUnit.SECONDS)
                        firstOffset = record.offset()
                        val partition = TopicPartition(record.topic(), record.partition())
                        awaitBlockedInsert()
                        assertThat(committed(admin, partition)).isLessThan(record.offset() + 1)
                    } finally {
                        lock.rollback()
                    }
                }
                awaitCommitted(admin, AgentAuditKafkaTestResource.INPUT_TOPIC, 0, firstOffset + 1)
                assertStored(first, "resource-$first", 1)
                val firstRecord = producer.send(
                    ProducerRecord(AgentAuditKafkaTestResource.INPUT_TOPIC, first.toString(), firstPayload),
                )
                    .get(15, TimeUnit.SECONDS)
                awaitCommitted(admin, firstRecord.topic(), firstRecord.partition(), firstRecord.offset() + 1)
                assertStored(first, "resource-$first", 1)

                val constraint = "reject_${rejected.toString().replace("-", "")}"
                sql(
                    "ALTER TABLE audit_entries ADD CONSTRAINT $constraint CHECK (aggregate_id <> 'resource-$rejected') NOT VALID",
                )
                try {
                    KafkaConsumer<String, String>(
                        mapOf(
                            "bootstrap.servers" to bootstrap,
                            "key.deserializer" to "org.apache.kafka.common.serialization.StringDeserializer",
                            "value.deserializer" to "org.apache.kafka.common.serialization.StringDeserializer",
                            "group.id" to "agent-dlq-probe-${UUID.randomUUID()}",
                            "auto.offset.reset" to "earliest",
                        ),
                    ).use { dlq ->
                        dlq.subscribe(listOf(AgentAuditKafkaTestResource.DLQ_TOPIC))
                        val failed = producer.send(
                            ProducerRecord(
                                AgentAuditKafkaTestResource.INPUT_TOPIC,
                                rejected.toString(),
                                rejectedPayload,
                            ),
                        ).get(15, TimeUnit.SECONDS)
                        val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
                        var deadLetter: String? = null
                        while (deadLetter == null && System.nanoTime() < deadline) {
                            deadLetter = dlq.poll(Duration.ofMillis(200)).firstOrNull()?.value()
                        }
                        assertThat(deadLetter).isEqualTo(rejectedPayload)
                        awaitCommitted(admin, failed.topic(), failed.partition(), failed.offset() + 1)
                        assertStored(rejected, "resource-$rejected", 0)
                    }
                } finally {
                    sql("ALTER TABLE audit_entries DROP CONSTRAINT $constraint")
                }
                val replay = producer.send(
                    ProducerRecord(AgentAuditKafkaTestResource.INPUT_TOPIC, rejected.toString(), rejectedPayload),
                )
                    .get(15, TimeUnit.SECONDS)
                awaitCommitted(admin, replay.topic(), replay.partition(), replay.offset() + 1)
                assertStored(rejected, "resource-$rejected", 1)
            }
        }
    }

    private fun payload(id: UUID, aggregateId: String) = """
        {"eventId":"$id","eventType":"agent.mcp.tool_call","aggregateType":"mcp.tool",
         "aggregateId":"$aggregateId","sourceService":"agent-service","actorId":"agent:test-$id",
         "actorType":"AI_AGENT","occurredAt":"2026-08-21T12:34:56Z"}
    """.trimIndent()

    private fun committed(admin: Admin, partition: TopicPartition): Long =
        admin.listConsumerGroupOffsets(AgentAuditKafkaTestResource.GROUP)
            .partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS)[partition]?.offset() ?: -1

    private fun awaitBlockedInsert() {
        val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        while (System.nanoTime() < deadline) {
            val blocked = jdbc().use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT count(*) FROM pg_stat_activity " +
                            "WHERE wait_event_type = 'Lock' AND query ILIKE '%audit_entries%' " +
                            "AND pid <> pg_backend_pid()",
                    ).use { rows ->
                        rows.next()
                        rows.getInt(1)
                    }
                }
            }
            if (blocked > 0) return
            Thread.sleep(100)
        }
        error("Agent audit consumer did not reach the locked audit_entries write")
    }

    private fun awaitCommitted(admin: Admin, topic: String, partition: Int, expected: Long) {
        val address = TopicPartition(topic, partition)
        val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        while (System.nanoTime() < deadline) {
            if (committed(admin, address) >= expected) return
            Thread.sleep(100)
        }
        error("Agent audit consumer did not commit offset $expected")
    }

    private fun assertStored(id: UUID, aggregateId: String, expected: Int) {
        jdbc().use { connection ->
            connection.prepareStatement(
                "SELECT aggregate_id, source_service FROM audit_entries WHERE entry_id = ?",
            ).use { query ->
                query.setObject(1, id)
                query.executeQuery().use { rows ->
                    if (expected == 0) {
                        assertThat(rows.next()).isFalse()
                    } else {
                        assertThat(rows.next()).isTrue()
                        assertThat(rows.getString("aggregate_id")).isEqualTo(aggregateId)
                        assertThat(rows.getString("source_service")).isEqualTo("agent-service")
                        assertThat(rows.next()).isFalse()
                    }
                }
            }
        }
    }

    private fun config(name: String) = ConfigProvider.getConfig().getValue(name, String::class.java)

    private fun jdbc() = DriverManager.getConnection(
        config("quarkus.datasource.jdbc.url"),
        config("quarkus.datasource.username"),
        config("quarkus.datasource.password"),
    )

    private fun sql(statement: String) {
        jdbc().use { connection -> connection.createStatement().use { it.execute(statement) } }
    }
}
