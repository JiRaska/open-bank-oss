// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.eclipse.microprofile.config.ConfigProvider
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Shared helpers for the pid E2E suite: durable facts over plain JDBC on the real Postgres and
 * wire facts drained from the real Redpanda topics. The offer row, the exchange row, the
 * status-list bit and the Kafka record are independent observations of the same lifecycle that
 * must agree — this base is what makes them comparable.
 *
 * Concrete classes carry `@QuarkusTest` + `@TestProfile(PidE2EProfile::class)` — the
 * annotations do not inherit, and every E2E class must name the same profile so Quarkus reuses
 * one test JVM (and one container set) across the whole suite.
 */
abstract class PidE2EBase {

    private val mapper = ObjectMapper()

    // ── JDBC ─────────────────────────────────────────────────────────────────────

    /** Runs [block] on a JDBC connection against the suite's real Postgres. */
    protected fun <T> jdbc(block: (Connection) -> T): T = DriverManager.getConnection(
        ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String::class.java),
        ConfigProvider.getConfig().getValue("quarkus.datasource.username", String::class.java),
        ConfigProvider.getConfig().getValue("quarkus.datasource.password", String::class.java),
    ).use(block)

    protected fun offerStatus(preAuthCode: String): String? = jdbc { c ->
        c.prepareStatement("SELECT status FROM eudi_credential_offer WHERE pre_auth_code = ?").use { ps ->
            ps.setString(1, preAuthCode)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    protected fun statusListEntry(idx: Long): Pair<Boolean, Boolean>? = jdbc { c ->
        c.prepareStatement("SELECT revoked FROM eudi_status_list_entry WHERE idx = ?").use { ps ->
            ps.setLong(1, idx)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getBoolean(1) to true else null }
        }
    }

    protected fun exchangeRow(transactionId: String): Triple<String, String?, String?>? = jdbc { c ->
        c.prepareStatement(
            "SELECT status, nonce, audience FROM eudi_presentation_exchange WHERE transaction_id = ?",
        ).use { ps ->
            ps.setString(1, transactionId)
            ps.executeQuery().use { rs ->
                if (rs.next()) {
                    Triple(rs.getString(1), rs.getString(2), rs.getString(3))
                } else {
                    null
                }
            }
        }
    }

    // ── Kafka ────────────────────────────────────────────────────────────────────

    /** Drains the topic until a record for [key] with the given `eventType` appears. */
    protected fun awaitEvent(topic: String, key: String, eventType: String, timeout: Duration = TIMEOUT): JsonNode {
        consumer("pid-e2e-await-${UUID.randomUUID()}").use { kafka ->
            kafka.subscribe(listOf(topic))
            val deadline = System.nanoTime() + timeout.toNanos()
            while (System.nanoTime() < deadline) {
                val hit = kafka.poll(Duration.ofMillis(500)).firstOrNull { matches(it, key, eventType) }
                if (hit != null) return parseEnvelope(hit)
            }
        }
        error("no $eventType for key $key on $topic within ${timeout.seconds}s")
    }

    private fun matches(record: ConsumerRecord<String, String>, key: String, eventType: String): Boolean =
        record.key() == key && parseEnvelope(record)["eventType"]?.asText() == eventType

    private fun parseEnvelope(record: ConsumerRecord<String, String>): JsonNode = mapper.readTree(record.value())

    private fun consumer(group: String) = KafkaConsumer<String, String>(
        mapOf(
            "bootstrap.servers" to
                ConfigProvider.getConfig().getValue("kafka.bootstrap.servers", String::class.java),
            "group.id" to group,
            "auto.offset.reset" to "earliest",
            "key.deserializer" to StringDeserializer::class.qualifiedName,
            "value.deserializer" to StringDeserializer::class.qualifiedName,
            "enable.auto.commit" to "false",
        ),
    )

    protected companion object {
        const val PARTY_EVENTS_TOPIC = "party.events"
        val TIMEOUT: Duration = Duration.ofSeconds(30)

        @JvmStatic
        @Synchronized
        protected fun ensureTopicOnce(topic: String) {
            if (topic in ensuredTopics) return
            val bootstrap = ConfigProvider.getConfig().getValue("kafka.bootstrap.servers", String::class.java)
            Admin.create(mapOf("bootstrap.servers" to bootstrap)).use { admin ->
                val existing = admin.listTopics().names().get(20, TimeUnit.SECONDS)
                if (topic !in existing) {
                    admin.createTopics(listOf(NewTopic(topic, 1, 1))).all().get(20, TimeUnit.SECONDS)
                }
            }
            ensuredTopics += topic
        }

        private val ensuredTopics = mutableSetOf<String>()
    }
}
