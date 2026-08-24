// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.audit.integration

import com.openbank.audit.application.TopicAttribution
import com.openbank.audit.it.PostgresTestResource
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.smallrye.reactive.messaging.kafka.api.IncomingKafkaRecordMetadata
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import io.smallrye.reactive.messaging.memory.InMemorySource
import jakarta.enterprise.inject.Any
import jakarta.inject.Inject
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.record.RecordBatch
import org.apache.kafka.common.record.TimestampType
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.eclipse.microprofile.reactive.messaging.Message
import org.eclipse.microprofile.reactive.messaging.Metadata
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.sql.DriverManager
import java.time.Instant
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.function.Supplier

/**
 * Issues #6035 / #5859: the runtime proof that audit-service's subscription surface is WIRED —
 * every topic it declares it consumes really reaches `audit_entries`, attributed to a named
 * producing service rather than to the `"unknown"` sentinel.
 *
 * WHY A DIRECT CALL TO THE CONSUMER CANNOT PROVE THIS
 * ---------------------------------------------------
 * The module's existing attribution tests ([com.openbank.audit.application.AuditAttributionTest])
 * mock [com.openbank.audit.infrastructure.persistence.AuditRepository], and even
 * [ScaEnrollmentAuditIT] — which uses a real database — reaches the consumer by injecting the bean
 * and calling `consume(message)` on it. A direct call supplies the wiring it is meant to be
 * testing: it proves the METHOD BODY works, and is structurally incapable of distinguishing a
 * REGISTERED messaging channel from an unregistered one. That is the `@Path`-bound-to-the-wrong-
 * declaration shape (#3371), where every `POST /mcp` answered 404 on a running pod while a unit
 * test calling the resource class stayed green.
 *
 * This test instead pushes messages **into the channel**, through the messaging runtime:
 * [InMemoryConnector.switchIncomingChannelsToInMemory] can only produce a source for a channel
 * SmallRye has actually registered, and the registration comes from `@Incoming("audit-events-in")`
 * being bound to a valid consumer method on a live CDI bean. Rename the channel in
 * `application.yaml`, rename it on [com.openbank.audit.application.AuditConsumer], or make the
 * method one SmallRye refuses to bind, and this test fails at boot or at `connector.source(...)` —
 * not silently.
 *
 * `runOnVertxContext(true)` is not cosmetic: `consume` is a `suspend fun` writing through reactive
 * Panache, which throws `HR000068` off a Vert.x context. The row assertion is therefore made with
 * a plain JDBC read, never by calling the reactive repository from the test thread.
 *
 * WHAT THIS PROVES
 * ----------------
 *  * `audit-events-in` is a registered channel bound to a real consumer bean (not merely a method
 *    that can be called);
 *  * every topic in the configured `topics:` list produces a persisted `audit_entries` row when a
 *    record bearing that topic's [io.smallrye.reactive.messaging.kafka.api.IncomingKafkaRecordMetadata]
 *    is delivered through it;
 *  * every one of those rows is attributed to a named producing service — never `"unknown"` — and
 *    the name agrees with [TopicAttribution]. That matters here more than anywhere: `audit_entries`
 *    is append-only at the DB (`no_update_audit`/`no_delete_audit` are `DO INSTEAD NOTHING`, so an
 *    UPDATE affects zero rows and reports success) and `source_service` is chain-hashed into
 *    `record_hash`, so an attribution has exactly one chance to be right.
 *
 * WHAT THIS DOES NOT PROVE
 * ------------------------
 *  * **Not a real broker.** There is no Kafka Testcontainers or Kafka Dev Services usage anywhere
 *    in this repo — every messaging IT in the fleet uses the in-memory connector — so no test here
 *    exercises a Kafka client, a broker, or an ACL. The in-memory connector REPLACES the Kafka
 *    connector for this channel, which means the `topics:` value is read here as configuration and
 *    is not the thing the connector subscribes with.
 *  * Consequently it cannot see the two failures that need the broker: a topic absent from the
 *    `topics:` list at deploy time, and a missing Read ACL on the `audit-service` KafkaUser. Those
 *    are what `.github/scripts/check-audit-money-path-subscription.py` exists to prevent, and the
 *    two halves are deliberately complementary — the gate covers what no test in this repo can
 *    reach, and this test covers what the gate cannot: that the wiring actually runs.
 *  * It does not assert the produced set. Whether a money-path producer's topic is IN the list is
 *    the gate's question; this test asserts that whatever is in the list works.
 *
 * The topic list is read from configuration at runtime, never restated here, so a topic added by
 * any other change (PR #5857's `openbank.delegation.events`, for one) is covered the moment it
 * lands, with no edit to this file.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(AuditSubscriptionSurfaceIT.InMemoryAuditChannel::class)
class AuditSubscriptionSurfaceIT {

    /**
     * Literals only. A [QuarkusTestResourceLifecycleManager] is loaded in a DIFFERENT classloader
     * from the test class, so a companion object initialises twice — a randomised value computed
     * here would not be the value the test class sees.
     */
    class InMemoryAuditChannel : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> = InMemoryConnector.switchIncomingChannelsToInMemory(CHANNEL)

        override fun stop() = InMemoryConnector.clear()
    }

    @Any
    @Inject
    lateinit var connector: InMemoryConnector

    private fun subscribedTopics(): List<String> = ConfigProvider.getConfig()
        .getValue("mp.messaging.incoming.$CHANNEL.topics", String::class.java)
        .split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    @Test
    fun `every subscribed topic delivered through the real channel lands an attributed audit row`() {
        val topics = subscribedTopics()
        // A corpus assertion first: an empty or one-element list would make everything below pass
        // vacuously, which is the failure this repo names most often.
        assertThat(topics)
            .`as`("audit-events-in must subscribe to the fleet's domain topics")
            .hasSizeGreaterThan(15)

        val source: InMemorySource<Message<String>> = connector.source(CHANNEL)
        source.runOnVertxContext(true)

        val marker: Map<String, String> = topics.associateWith { UUID.randomUUID().toString() }
        topics.forEach { topic -> source.send(recordOn(topic, marker.getValue(topic))) }

        // ONE deadline for the whole surface, not one per topic: a per-topic wait multiplies the
        // failure path by the number of topics, and a 10-minute red run dies of a socket timeout
        // before it can report which topic was missing. Measured — that is exactly what the first
        // version of this test did.
        val rows = awaitRows(marker.values.toSet())

        val failures = mutableListOf<String>()
        topics.forEach { topic ->
            val row = rows[marker.getValue(topic)]
            if (row == null) {
                failures += "$topic: no audit_entries row — the channel took the record and " +
                    "nothing was persisted"
                return@forEach
            }
            val (eventType, sourceService) = row
            if (eventType != EVENT_TYPE) {
                failures += "$topic: event_type=$eventType, expected $EVENT_TYPE"
            }
            if (sourceService == UNKNOWN) {
                failures += "$topic: source_service=\"$UNKNOWN\" — the row landed unattributed, and " +
                    "source_service is chain-hashed into record_hash so it can never be corrected " +
                    "(add the topic to TopicAttribution)"
            }
            val expected = TopicAttribution.sourceService(topic)
            if (expected != null && sourceService != expected) {
                failures += "$topic: source_service=$sourceService, TopicAttribution says $expected"
            }
        }

        assertThat(failures).`as`("subscription surface").isEmpty()
    }

    /**
     * Issue #6035: the four money-path topics wired by this change, named as LITERALS.
     *
     * The test above is deliberately corpus-driven and therefore cannot see a REMOVAL: delete a
     * topic from `application.yaml` and the loop simply iterates one fewer topic and stays green.
     * That is the "a gate whose scope is the thing it checks reads as passing when the list is
     * short" shape. These four are the production wiring this change adds, so they are asserted by
     * name — revert any one of the three artefacts and this goes red naming the topic.
     *
     * It asserts two of the three places. The third, the `audit-service` KafkaUser's Read ACL, is
     * unreachable from any test in this repo (there is no Kafka broker in any test here, only the
     * in-memory connector) and is the gate's job:
     * `.github/scripts/check-audit-money-path-subscription.py --enforce`.
     */
    @Test
    fun `the money-path topics wired by issue 6035 are subscribed and attributed`() {
        val subscribed = subscribedTopics()
        val failures = WIRED_BY_6035.mapNotNull { (topic, producer) ->
            when {
                topic !in subscribed ->
                    "$topic: absent from mp.messaging.incoming.$CHANNEL.topics — nothing is read, " +
                        "and no error is raised anywhere"
                TopicAttribution.sourceService(topic) == null ->
                    "$topic: no TopicAttribution entry — every row would land on the \"$UNKNOWN\" " +
                        "sentinel, and source_service is chain-hashed into record_hash so it can " +
                        "never be corrected"
                TopicAttribution.sourceService(topic) != producer ->
                    "$topic: TopicAttribution says ${TopicAttribution.sourceService(topic)}, " +
                        "the module declaring the outgoing channel is $producer"
                else -> null
            }
        }
        assertThat(failures).`as`("issue #6035 wiring").isEmpty()
    }

    /**
     * A record shaped exactly as SmallRye Kafka delivers one: the topic on
     * [io.smallrye.reactive.messaging.kafka.api.IncomingKafkaRecordMetadata], the outbox event type
     * on the `ce-type` header. `accountId` carries the per-topic marker so the row is findable by a
     * value the consumer itself derives (`inferAggregateId`), rather than one the test writes to a
     * column directly.
     */
    private fun recordOn(topic: String, marker: String): Message<String> {
        val payload = """{"eventType":"$EVENT_TYPE","accountId":"$marker","occurredAt":"${Instant.now()}"}"""
        val headers = RecordHeaders()
        headers.add(
            RecordHeader(
                OutboxKafkaHeaders.HEADER_EVENT_TYPE,
                EVENT_TYPE.toByteArray(StandardCharsets.UTF_8),
            ),
        )
        val record = ConsumerRecord(
            topic,
            0,
            0L,
            RecordBatch.NO_TIMESTAMP,
            TimestampType.NO_TIMESTAMP_TYPE,
            ConsumerRecord.NULL_SIZE,
            ConsumerRecord.NULL_SIZE,
            null as String?,
            payload,
            headers,
            Optional.empty(),
        )
        return Message.of(
            payload,
            Metadata.of(IncomingKafkaRecordMetadata(record, CHANNEL)),
            Supplier<CompletionStage<Void>> { CompletableFuture.completedFuture(null) },
        )
    }

    /**
     * Plain JDBC — the reactive repository cannot be called from a bare `@QuarkusTest` thread.
     *
     * One connection, one query for the whole marker set, one deadline. Returns whatever landed
     * before the deadline; the caller decides what is missing, so a failure names every absent
     * topic at once instead of dying on the first.
     */
    private fun awaitRows(markers: Set<String>): Map<String, Pair<String, String>> {
        val found = mutableMapOf<String, Pair<String, String>>()
        val deadline = System.nanoTime() + AWAIT_NANOS
        DriverManager.getConnection(jdbcUrl(), jdbcUser(), jdbcPassword()).use { connection ->
            val sql = "SELECT aggregate_id, event_type, source_service FROM audit_entries " +
                "WHERE aggregate_id = ANY (?)"
            val array = connection.createArrayOf("varchar", markers.toTypedArray())
            while (found.size < markers.size && System.nanoTime() < deadline) {
                collectInto(found, connection.prepareStatement(sql), array)
                if (found.size < markers.size) Thread.sleep(POLL_MILLIS)
            }
        }
        return found
    }

    private fun collectInto(
        found: MutableMap<String, Pair<String, String>>,
        statement: java.sql.PreparedStatement,
        markers: java.sql.Array,
    ) {
        statement.use {
            it.setArray(1, markers)
            it.executeQuery().use { rs ->
                while (rs.next()) found[rs.getString(1)] = rs.getString(2) to rs.getString(3)
            }
        }
    }

    private fun jdbcUrl(): String =
        ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String::class.java)

    private fun jdbcUser(): String =
        ConfigProvider.getConfig().getValue("quarkus.datasource.username", String::class.java)

    private fun jdbcPassword(): String =
        ConfigProvider.getConfig().getValue("quarkus.datasource.password", String::class.java)

    private companion object {
        const val CHANNEL = "audit-events-in"
        const val EVENT_TYPE = "SUBSCRIPTION_SURFACE_PROBE"
        const val UNKNOWN = "unknown"
        const val POLL_MILLIS = 100L
        const val AWAIT_NANOS = 30_000_000_000L

        /**
         * topic -> the module that DECLARES the outgoing channel producing it, read off that
         * module's own `mp.messaging.outgoing.<channel>.topic` rather than derived from the
         * topic's domain segment (a derivation is wrong for eight of the subscribed topics).
         */
        val WIRED_BY_6035 = mapOf(
            "openbank.ledger.journal.posted" to "ledger-service",
            "openbank.sdd.event" to "sdd-service",
            "openbank.interest.accrual.event" to "interest-service",
            "openbank.fraud.hold.changed" to "fraud-service",
        )
    }
}
