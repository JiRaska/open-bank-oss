// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tppregistry.e2e

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
 * Shared helpers for the tpp-registry E2E suite: durable facts over plain JDBC on the real
 * Postgres and wire facts drained from the real Redpanda topic `openbank.tpp.registry.event`.
 * The registry row, the outbox row and the Kafka record are three independent observations of the
 * same lifecycle that must agree — this base is what makes them comparable.
 *
 * Concrete classes carry `@QuarkusTest` + `@TestProfile(TppRegistryE2EProfile::class)` — the
 * annotations do not inherit, and every E2E class must name the same profile so Quarkus reuses
 * one test JVM (and one container set) across the whole suite.
 */
abstract class TppRegistryE2EBase {

    private val mapper = ObjectMapper()

    // ── JDBC ─────────────────────────────────────────────────────────────────────

    /** Runs [block] on a JDBC connection against the suite's real Postgres. */
    protected fun <T> jdbc(block: (Connection) -> T): T = DriverManager.getConnection(
        ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String::class.java),
        ConfigProvider.getConfig().getValue("quarkus.datasource.username", String::class.java),
        ConfigProvider.getConfig().getValue("quarkus.datasource.password", String::class.java),
    ).use(block)

    protected data class EntryRow(
        val entryUuid: String,
        val status: String,
        val roles: String,
        val blacklistReason: String?,
        val blacklistedAtPresent: Boolean,
    )

    protected fun entryRow(tppId: String): EntryRow? = jdbc { c ->
        c.prepareStatement(
            "SELECT entry_uuid, status, roles, blacklist_reason, blacklisted_at IS NOT NULL " +
                "FROM tpp_entries WHERE tpp_id = ?",
        ).use { ps ->
            ps.setString(1, tppId)
            ps.executeQuery().use { rs ->
                if (rs.next()) {
                    EntryRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5))
                } else {
                    null
                }
            }
        }
    }

    protected data class OutboxRow(val eventType: String, val status: String, val sent: Boolean)

    /** Outbox rows for an aggregate, oldest first — the dispatcher's raw material. */
    protected fun outboxRows(aggregateId: String): List<OutboxRow> = jdbc { c ->
        c.prepareStatement(
            "SELECT event_type, status, sent_at IS NOT NULL FROM tpp_outbox WHERE aggregate_id = ? ORDER BY id",
        ).use { ps ->
            ps.setObject(1, UUID.fromString(aggregateId))
            ps.executeQuery().use { rs ->
                val rows = mutableListOf<OutboxRow>()
                while (rs.next()) rows.add(OutboxRow(rs.getString(1), rs.getString(2), rs.getBoolean(3)))
                rows
            }
        }
    }

    protected fun syncStateRow(): Triple<String?, Int, Boolean>? = jdbc { c ->
        c.prepareStatement(
            "SELECT error_message, total_entries, last_sync_at IS NOT NULL FROM eba_sync_state ORDER BY id DESC LIMIT 1",
        ).use { ps ->
            ps.executeQuery().use { rs ->
                if (rs.next()) Triple(rs.getString(1), rs.getInt(2), rs.getBoolean(3)) else null
            }
        }
    }

    // ── Kafka ────────────────────────────────────────────────────────────────────

    /** Drains the topic until a record for aggregate [key] with the given `eventType` appears. */
    protected fun awaitEvent(key: String, eventType: String, timeout: Duration = TIMEOUT): JsonNode {
        consumer("tpp-e2e-await-${UUID.randomUUID()}").use { kafka ->
            kafka.subscribe(listOf(TOPIC))
            val deadline = System.nanoTime() + timeout.toNanos()
            while (System.nanoTime() < deadline) {
                val hit = kafka.poll(Duration.ofMillis(500)).firstOrNull { matches(it, key, eventType) }
                if (hit != null) return envelope(hit)
            }
        }
        error("no $eventType for aggregate $key on $TOPIC within ${timeout.seconds}s")
    }

    /**
     * Counts the records already on the topic for aggregate [key] after a settle window — the
     * replay proofs assert EXACTLY ONE event exists where a duplicated emission would add a second.
     * Polls a fixed window instead of breaking on the first empty batch: a fresh consumer's join
     * round can return an empty batch while the assignment is still completing.
     */
    protected fun countEvents(key: String, settle: Duration = Duration.ofSeconds(3)): Int {
        Thread.sleep(settle.toMillis())
        consumer("tpp-e2e-count-${UUID.randomUUID()}").use { kafka ->
            kafka.subscribe(listOf(TOPIC))
            val deadline = System.nanoTime() + Duration.ofSeconds(6).toNanos()
            var count = 0
            while (System.nanoTime() < deadline) {
                count += kafka.poll(Duration.ofMillis(500)).count { it.key() == key }
            }
            return count
        }
    }

    private fun matches(record: ConsumerRecord<String, String>, key: String, eventType: String): Boolean =
        record.key() == key && record.headers().lastHeader("ce-type")?.let { String(it.value()) } == eventType

    /** The parsed envelope the consumer would see — record VALUE is the flat JSON envelope. */
    private fun envelope(record: ConsumerRecord<String, String>): JsonNode = mapper.readTree(record.value())

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
        const val TOPIC = "openbank.tpp.registry.event"
        val TIMEOUT: Duration = Duration.ofSeconds(30)

        @JvmStatic
        @Synchronized
        protected fun ensureTopicOnce() {
            if (ensured) return
            val bootstrap = ConfigProvider.getConfig().getValue("kafka.bootstrap.servers", String::class.java)
            Admin.create(mapOf("bootstrap.servers" to bootstrap)).use { admin ->
                admin.createTopics(listOf(NewTopic(TOPIC, 1, 1.toShort()))).all()
                    .get(30, TimeUnit.SECONDS)
            }
            ensured = true
        }

        private var ensured = false
    }
}
