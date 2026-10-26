// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.wealth.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.BeforeAll
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Shared helpers for the wealth E2E suite: durable facts over plain JDBC on the real Postgres and
 * wire facts drained from the real Redpanda topic. The three must agree — the holding row, the
 * `wealth_outbox` row and the Kafka record are independent observations of the same lifecycle
 * event, and the ADR-0050 contract (aggregate id as partition key, `ce-id` == `idempotency-key`,
 * `ce-type` as the event type) is asserted on the records themselves.
 *
 * Concrete classes carry `@QuarkusTest` + `@TestProfile(WealthE2EProfile::class)` — the
 * annotations do not inherit, and every E2E class must name the same profile so Quarkus reuses
 * one test JVM (and one container set) across the whole suite.
 */
abstract class WealthE2EBase {

    private val mapper = ObjectMapper()

    // --------------------------------------------------------------------------------- JDBC

    /** Runs [block] on a JDBC connection against the suite's real Postgres. */
    protected fun <T> jdbc(block: (Connection) -> T): T = DriverManager.getConnection(
        ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String::class.java),
        ConfigProvider.getConfig().getValue("quarkus.datasource.username", String::class.java),
        ConfigProvider.getConfig().getValue("quarkus.datasource.password", String::class.java),
    ).use(block)

    /** Polls until the outbox row for [aggregateId] reaches SENT (or the deadline passes). */
    protected fun awaitOutboxSent(aggregateId: UUID): String {
        val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        while (System.nanoTime() < deadline) {
            val status = jdbc { c ->
                c.prepareStatement(
                    "SELECT status FROM wealth_outbox WHERE aggregate_id = ? ORDER BY id DESC LIMIT 1",
                ).use { ps ->
                    ps.setObject(1, aggregateId)
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
                }
            }
            if (status == "SENT") return status
            Thread.sleep(250)
        }
        error("outbox row for $aggregateId did not reach SENT within 30s")
    }

    protected fun outboxRowsFor(aggregateId: UUID): List<Pair<String, String>> = jdbc { c ->
        c.prepareStatement(
            "SELECT event_type, status FROM wealth_outbox WHERE aggregate_id = ? ORDER BY id",
        ).use { ps ->
            ps.setObject(1, aggregateId)
            ps.executeQuery().use { rs ->
                generateSequence { if (rs.next()) rs.getString(1) to rs.getString(2) else null }.toList()
            }
        }
    }

    protected fun holdingStatus(holdingId: UUID): String? = jdbc { c ->
        c.prepareStatement("SELECT status FROM declared_holdings WHERE holding_id = ?").use { ps ->
            ps.setObject(1, holdingId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    /** Seeds the pledge the lending side would write (wealth exposes no pledge endpoint). */
    protected fun setPledged(holdingId: UUID, loanId: UUID?) {
        jdbc { c ->
            c.prepareStatement(
                "UPDATE declared_holdings SET status = ?, pledged_to_loan_id = ? WHERE holding_id = ?",
            ).use { ps ->
                ps.setString(1, if (loanId != null) "PLEDGED" else "ACTIVE")
                ps.setObject(2, loanId)
                ps.setObject(3, holdingId)
                ps.executeUpdate()
            }
        }
    }

    // --------------------------------------------------------------------------------- Kafka

    /** Drains the topic until a record for [key] with [ceType] appears; asserts the N3 header contract. */
    protected fun awaitEvent(key: UUID, ceType: String, timeout: Duration = Duration.ofSeconds(30)): JsonNode {
        val record = drainTopic(
            group = "wealth-e2e-await-${UUID.randomUUID()}",
            timeout = timeout,
        ) { r ->
            if (r.key() == key.toString() &&
                r.headers().lastHeader("ce-type")?.let {
                    String(it.value(), Charsets.UTF_8)
                } == ceType
            ) {
                r
            } else {
                null
            }
        } ?: error("no $ceType for $key within ${timeout.seconds}s")
        // ADR-0050 N3: the consumer-visible idempotency key IS the event id, so an at-least-once
        // consumer can dedupe. Both headers pinned on the real record.
        assertThat(record.headers().lastHeader("ce-id")).isNotNull()
        assertThat(String(record.headers().lastHeader("idempotency-key").value(), Charsets.UTF_8))
            .isEqualTo(String(record.headers().lastHeader("ce-id").value(), Charsets.UTF_8))
        return mapper.readTree(record.value())
    }

    /** Drains every record for [key] until [atLeast] records arrive or the deadline passes. */
    protected fun awaitEventsForKey(
        key: UUID,
        atLeast: Int,
        timeout: Duration = Duration.ofSeconds(30),
    ): List<ConsumerRecord<String, String>> {
        val found = mutableListOf<ConsumerRecord<String, String>>()
        consumer("wealth-e2e-order-${UUID.randomUUID()}").use { consumer ->
            consumer.subscribe(listOf(EVENTS_TOPIC))
            val deadline = System.nanoTime() + timeout.toNanos()
            while (System.nanoTime() < deadline && found.size < atLeast) {
                collectForKey(found, consumer.poll(Duration.ofMillis(500)), key)
            }
        }
        return found
    }

    private fun collectForKey(
        found: MutableList<ConsumerRecord<String, String>>,
        batch: ConsumerRecords<String, String>,
        key: UUID,
    ) {
        for (record in batch) {
            if (record.key() == key.toString()) found.add(record)
        }
    }

    private fun <T> drainTopic(group: String, timeout: Duration, matcher: (ConsumerRecord<String, String>) -> T?): T? {
        consumer(group).use { kafka ->
            kafka.subscribe(listOf(EVENTS_TOPIC))
            val deadline = System.nanoTime() + timeout.toNanos()
            while (System.nanoTime() < deadline) {
                val matched = scan(kafka.poll(Duration.ofMillis(500)), matcher)
                if (matched != null) return matched
            }
        }
        return null
    }

    private fun <T> scan(batch: ConsumerRecords<String, String>, matcher: (ConsumerRecord<String, String>) -> T?): T? {
        for (record in batch) {
            val matched = matcher(record)
            if (matched != null) return matched
        }
        return null
    }

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
        const val EVENTS_TOPIC = "openbank.wealth.events"

        @JvmStatic
        @Synchronized
        @BeforeAll
        protected fun ensureTopicsOnce() {
            if (topicsEnsured) return
            val bootstrap = ConfigProvider.getConfig().getValue("kafka.bootstrap.servers", String::class.java)
            Admin.create(mapOf("bootstrap.servers" to bootstrap)).use { admin ->
                val existing = admin.listTopics().names().get(20, TimeUnit.SECONDS)
                if (EVENTS_TOPIC !in existing) {
                    admin.createTopics(listOf(NewTopic(EVENTS_TOPIC, 1, 1))).all().get(20, TimeUnit.SECONDS)
                }
            }
            topicsEnsured = true
        }

        private var topicsEnsured = false
    }
}
