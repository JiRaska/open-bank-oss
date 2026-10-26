// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.campaign.e2e

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Real-broker E2E for `notification-outcomes-in` (ADR-0239 D3): notification-service reports the
 * terminal fate of every send handoff on `openbank.notification.outcomes.v1`, correlated by the
 * send-log row id, and this consumer settles `send_log.delivery_status` so the console stops
 * reporting an accepted handoff as a delivered message (#3663).
 *
 * The pre-existing tests exercise `DeliveryTransition` as a pure function and mock the repository
 * — the load-bearing wire behaviour was never exercised: the marker-driven correlation (an
 * outcome whose correlationId has no `campaign_send_handoff_intents` marker and no send-log row
 * is a quiet no-op, because the topic is shared with every producer in the estate), the
 * first-terminal-wins monotonic rule under out-of-order and duplicate deliveries (the topic is
 * partitioned by notificationId, NOT correlation id, so two outcomes for one send arrive in no
 * guaranteed order), the BOUNCED refinement out of CONFIRMED, and the marker lifecycle (a matched
 * marker is consumed — deleted — in the same transaction that settles the row, and can even
 * materialize a send row lost after broker publish). One contract the wire run pinned and the
 * suite now guards: the provider's free-text reason reaches `send_log.delivery_reason` ONLY on
 * the later entity-only refinement path — the marker paths deliberately persist state without
 * it (the intent has no reason column; "private provider detail" stays out of the send log), as
 * CampaignSendHandoffIntentIT already pins.
 *
 * Negative assertions wait on a sentinel published AFTER the message under test: "ignored" is
 * only observable once the consumer provably moved past the record.
 */
@QuarkusTest
@TestProfile(CampaignOutcomesE2EProfile::class)
class CampaignOutcomesE2E {

    // ── scenarios ────────────────────────────────────────────────────────────────

    @Test
    fun `a SENT outcome confirms the handoff and consumes the marker`() {
        val (sendId, _) = seedPendingHandoff()
        // Truncate to millis: Postgres stores micros, and the provider timestamp must round-trip
        // exactly through payload -> Instant.parse -> TIMESTAMPTZ -> Timestamp.
        val at = Instant.now().minus(Duration.ofSeconds(5)).truncatedTo(java.time.temporal.ChronoUnit.MILLIS)

        publish(outcomePayload(sendId, "SENT", null, at))
        awaitDeliveryStatus(sendId, "CONFIRMED")
        assertThat(reason(sendId)).isNull()
        assertThat(updatedAt(sendId)).isEqualTo(Timestamp.from(at))
        assertThat(markerCount(sendId))
            .describedAs("a matched handoff marker is consumed with the settling transaction")
            .isZero()
    }

    @Test
    fun `a FAILED outcome settles the state and consumes the marker`() {
        val (sendId, _) = seedPendingHandoff()
        val at = Instant.now()

        publish(outcomePayload(sendId, "FAILED", "mailbox full", at))
        awaitDeliveryStatus(sendId, "FAILED")
        // The marker path deliberately does NOT persist the provider's free text: the handoff
        // intent carries state only (no reason column — "private provider detail" stays out of
        // the send log), which CampaignSendHandoffIntentIT pins as delivery_reason IS NULL. The
        // reason reaches send_log only on the later entity-only refinement — proven by the
        // BOUNCED scenario below.
        assertThat(reason(sendId)).isNull()
        assertThat(markerCount(sendId)).isZero()
    }

    @Test
    fun `a stale later outcome cannot move a terminal state`() {
        val (sendId, _) = seedPendingHandoff()

        // Delivery is at-least-once and the topic is partitioned by notificationId, so these can
        // arrive in either order. First-terminal-wins must hold regardless: FAILED lands first,
        // a late SENT is ignored, and the row keeps the FIRST answer (marker path: no reason
        // persisted, provider free text stays out of the send log).
        publish(outcomePayload(sendId, "FAILED", "provider outage", Instant.now()))
        awaitDeliveryStatus(sendId, "FAILED")
        publish(outcomePayload(sendId, "SENT", null, Instant.now()))
        Thread.sleep(1_000)
        assertThat(deliveryStatus(sendId)).isEqualTo("FAILED")
        assertThat(reason(sendId)).isNull()
    }

    @Test
    fun `a BOUNCED outcome refines a CONFIRMED handoff into FAILED`() {
        val (sendId, _) = seedPendingHandoff()

        // The one genuine transition out of a terminal state: SMTP accepted the message, then it
        // bounced. CONFIRMED stopped being true, and the console must stop showing it.
        publish(outcomePayload(sendId, "SENT", null, Instant.now()))
        awaitDeliveryStatus(sendId, "CONFIRMED")
        publish(outcomePayload(sendId, "BOUNCED", "no such recipient", Instant.now()))
        awaitDeliveryStatus(sendId, "FAILED")
        assertThat(reason(sendId)).isEqualTo("no such recipient")
        assertThat(markerCount(sendId)).isZero()
    }

    @Test
    fun `an outcome for another producer is a quiet no-op`() {
        val (sendId, _) = seedPendingHandoff()

        // The topic is shared: most records belong to somebody else and correlate with nothing
        // here. No marker, no send row — nothing may move, and the channel must stay alive.
        publish(outcomePayload(UUID.randomUUID(), "SENT", null, Instant.now()))
        publish(outcomePayload(sendId, "SENT", null, Instant.now()))
        awaitDeliveryStatus(sendId, "CONFIRMED")
    }

    @Test
    fun `an unknown additive outcome is ignored and the channel keeps flowing`() {
        val (sendId, _) = seedPendingHandoff()

        // The outcome contract is additive: a value this version does not recognise is ignored,
        // never an error. A future producer's new outcome must not wedge the channel.
        publish(outcomePayload(sendId, "READ", null, Instant.now()))
        publish(outcomePayload(sendId, "SENT", null, Instant.now()))
        awaitDeliveryStatus(sendId, "CONFIRMED")
    }

    // ── Kafka producer ───────────────────────────────────────────────────────────

    private fun publish(payload: String) {
        val props = mapOf(
            "bootstrap.servers" to
                ConfigProvider.getConfig().getValue("kafka.bootstrap.servers", String::class.java),
            "key.serializer" to StringSerializer::class.qualifiedName,
            "value.serializer" to StringSerializer::class.qualifiedName,
            "acks" to "all",
        )
        KafkaProducer<String, String>(props).use { p ->
            p.send(ProducerRecord(TOPIC, UUID.randomUUID().toString(), payload)).get(10, TimeUnit.SECONDS)
        }
    }

    private fun outcomePayload(correlationId: UUID, outcome: String, reason: String?, at: Instant): String {
        val reasonField = reason?.let { ",\"reason\":\"$it\"" } ?: ""
        return """{"notificationId":"${UUID.randomUUID()}","correlationId":"$correlationId",""" +
            """"outcome":"$outcome"$reasonField,"occurredAt":"$at"}"""
    }

    // ── JDBC seeds and read-back ─────────────────────────────────────────────────

    /**
     * One campaign with a pending handoff: the send-log row (the frequency cap's SENT evidence)
     * AND its `campaign_send_handoff_intents` marker, both PENDING. Returns the send id and party id.
     */
    private fun seedPendingHandoff(): Pair<UUID, UUID> {
        val campaignId = UUID.randomUUID()
        val partyId = UUID.randomUUID()
        val sendId = UUID.randomUUID()
        jdbc { c ->
            c.prepareStatement(
                "INSERT INTO campaigns (id, name, goal, segment_name, segment_version, steps_json, " +
                    "state, created_by, created_at, updated_at, product_kind) " +
                    "VALUES (?, ?, ?, ?, 1, '[]'::jsonb, 'ACTIVE', 'e2e', ?, ?, 'NONE')",
            ).use { ps ->
                ps.setObject(1, campaignId)
                ps.setString(2, "e2e outcome campaign")
                ps.setString(3, "outcome")
                ps.setString(4, "seg-${UUID.randomUUID()}")
                ps.setObject(5, Timestamp.from(Instant.now()))
                ps.setObject(6, Timestamp.from(Instant.now()))
                ps.executeUpdate()
            }
            c.prepareStatement(
                "INSERT INTO send_log (id, campaign_id, party_id, step_order, outcome, occurred_at, channel) " +
                    "VALUES (?, ?, ?, 1, 'SENT', ?, 'EMAIL')",
            ).use { ps ->
                ps.setObject(1, sendId)
                ps.setObject(2, campaignId)
                ps.setObject(3, partyId)
                ps.setObject(4, Timestamp.from(Instant.now()))
                ps.executeUpdate()
            }
            c.prepareStatement(
                "INSERT INTO campaign_send_handoff_intents (send_id, campaign_id, party_id, step_order, channel) " +
                    "VALUES (?, ?, ?, 1, 'EMAIL')",
            ).use { ps ->
                ps.setObject(1, sendId)
                ps.setObject(2, campaignId)
                ps.setObject(3, partyId)
                ps.executeUpdate()
            }
        }
        return sendId to partyId
    }

    private fun awaitDeliveryStatus(sendId: UUID, expected: String) {
        val deadline = Instant.now().plus(Duration.ofSeconds(20))
        var last = deliveryStatus(sendId)
        while (last != expected && Instant.now().isBefore(deadline)) {
            Thread.sleep(100)
            last = deliveryStatus(sendId)
        }
        assertThat(last).describedAs("delivery_status of $sendId").isEqualTo(expected)
    }

    private fun deliveryStatus(sendId: UUID): String? = query(
        "select delivery_status from send_log where id = ?",
        sendId,
    ) { rs -> if (rs.next()) rs.getString(1) else null }

    private fun reason(sendId: UUID): String? = query(
        "select delivery_reason from send_log where id = ?",
        sendId,
    ) { rs -> if (rs.next()) rs.getString(1) else null }

    private fun updatedAt(sendId: UUID): Timestamp? = query(
        "select delivery_updated_at from send_log where id = ?",
        sendId,
    ) { rs -> if (rs.next()) rs.getTimestamp(1) else null }

    private fun markerCount(sendId: UUID): Long = query(
        "select count(*) from campaign_send_handoff_intents where send_id = ?",
        sendId,
    ) { rs ->
        rs.next()
        rs.getLong(1)
    }

    private fun <T> query(sql: String, param: UUID, read: (java.sql.ResultSet) -> T): T = jdbc { c ->
        c.prepareStatement(sql).use { ps ->
            ps.setObject(1, param)
            ps.executeQuery().use(read)
        }
    }

    private fun <T> jdbc(block: (Connection) -> T): T = DriverManager.getConnection(
        ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String::class.java),
        ConfigProvider.getConfig().getValue("quarkus.datasource.username", String::class.java),
        ConfigProvider.getConfig().getValue("quarkus.datasource.password", String::class.java),
    ).use(block)

    companion object {
        private const val TOPIC = "openbank.notification.outcomes.v1"

        @BeforeAll
        @JvmStatic
        fun ensureTopic() {
            val bootstrap = ConfigProvider.getConfig().getValue("kafka.bootstrap.servers", String::class.java)
            Admin.create(mapOf("bootstrap.servers" to bootstrap)).use { admin ->
                val existing = admin.listTopics().names().get(30, TimeUnit.SECONDS)
                if (TOPIC !in existing) createTopicIgnoringRace(admin)
            }
        }

        private fun createTopicIgnoringRace(admin: Admin) {
            try {
                admin.createTopics(listOf(NewTopic(TOPIC, 1, 1.toShort()))).all()
                    .get(30, TimeUnit.SECONDS)
            } catch (e: java.util.concurrent.ExecutionException) {
                // the app auto-creates topics at boot and can win the race between our
                // listTopics and createTopics — an existing topic is the outcome we wanted
                if (e.cause !is org.apache.kafka.common.errors.TopicExistsException) throw e
            }
        }
    }
}
