// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.integration

import com.openbank.audit.it.AuditKafkaTestResource
import com.openbank.audit.it.AuditKafkaTestResource.Companion.DLQ_TOPIC
import com.openbank.audit.it.AuditKafkaTestResource.Companion.INPUT_TOPIC
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

@QuarkusTest
@TestProfile(AuditDlqIT.RealKafkaProfile::class)
class AuditDlqIT {
    class RealKafkaProfile : QuarkusTestProfile {
        override fun disableGlobalTestResources() = true
        override fun testResources() = listOf(QuarkusTestProfile.TestResourceEntry(AuditKafkaTestResource::class.java))
    }

    @Test
    @Suppress("NestedBlockDepth") // Keep broker/SQL resources and fault cleanup paired in this integration scenario.
    fun `poison and store failure reach the broker DLQ while subsequent audit records persist`() {
        val rejected = UUID.randomUUID()
        val accepted = UUID.randomUUID()
        val poison = "not-json-${UUID.randomUUID()}"
        val rejectedPayload = payload(rejected)
        val constraint = "reject_${rejected.toString().replace("-", "") }"
        sql("ALTER TABLE audit_entries ADD CONSTRAINT $constraint CHECK (aggregate_id <> '$rejected') NOT VALID")
        val bootstrap = config("kafka.bootstrap.servers")
        KafkaProducer<String, String>(
            mapOf(
                "bootstrap.servers" to bootstrap,
                "key.serializer" to "org.apache.kafka.common.serialization.StringSerializer",
                "value.serializer" to "org.apache.kafka.common.serialization.StringSerializer",
                "acks" to "all",
            ),
        ).use { producer ->
            KafkaConsumer<String, String>(
                mapOf(
                    "bootstrap.servers" to bootstrap,
                    "key.deserializer" to "org.apache.kafka.common.serialization.StringDeserializer",
                    "value.deserializer" to "org.apache.kafka.common.serialization.StringDeserializer",
                    "group.id" to "audit-dlq-probe-${UUID.randomUUID()}",
                    "auto.offset.reset" to "earliest",
                    "enable.auto.commit" to "false",
                ),
            ).use { dlq ->
                dlq.subscribe(listOf(DLQ_TOPIC))
                try {
                    listOf(poison, rejectedPayload, payload(accepted)).forEach {
                        producer.send(ProducerRecord(INPUT_TOPIC, "audit-probe", it)).get(15, TimeUnit.SECONDS)
                    }
                    val failed = mutableListOf<String>()
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
                    while (failed.size < 2 && System.nanoTime() < deadline) {
                        dlq.poll(Duration.ofMillis(200)).forEach { failed += it.value() }
                    }
                    assertThat(failed).containsExactlyInAnyOrder(poison, rejectedPayload)
                    awaitCount(accepted, 1)
                    assertThat(count(rejected)).isZero()
                } finally {
                    sql("ALTER TABLE audit_entries DROP CONSTRAINT $constraint")
                }
                // Replay after repairing the store failure preserves the producer's identity.
                producer.send(ProducerRecord(INPUT_TOPIC, "audit-probe", rejectedPayload)).get(15, TimeUnit.SECONDS)
                awaitCount(rejected, 1)
            }
        }
    }

    private fun payload(id: UUID) = """
        {"eventId":"$id","eventType":"AuditProbe","aggregateType":"ACCOUNT",
         "aggregateId":"$id","sourceService":"audit-service","actorId":"system:test",
         "occurredAt":"2026-01-01T00:00:00Z"}
    """.trimIndent()

    private fun awaitCount(id: UUID, expected: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (count(id) != expected && System.nanoTime() < deadline) Thread.sleep(20)
        assertThat(count(id)).isEqualTo(expected)
    }

    private fun count(id: UUID): Int = jdbc().use { connection ->
        connection.prepareStatement("SELECT count(*) FROM audit_entries WHERE entry_id = ?").use { query ->
            query.setObject(1, id)
            query.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }

    private fun config(name: String): String = ConfigProvider.getConfig().getValue(name, String::class.java)

    private fun jdbc() = DriverManager.getConnection(
        config("quarkus.datasource.jdbc.url"),
        config("quarkus.datasource.username"),
        config("quarkus.datasource.password"),
    )

    private fun sql(statement: String) {
        jdbc().use { connection -> connection.createStatement().use { it.execute(statement) } }
    }
}
