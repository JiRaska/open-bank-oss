// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.campaign.e2e

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringDeserializer
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
 * Real-broker E2E for the last two campaign consumer channels:
 *
 * - `engagement-events-in` builds the campaign-local, no-PII projection of attributable
 *   engagement events (#4480). CampaignEngagementConsumerTest covers the parse decisions against
 *   mocks; the projection's real wire (INSERT ... ON CONFLICT (event_id) DO NOTHING under broker
 *   redelivery, the DB CHECK constraints on the channel/surface/type vocabulary, and the
 *   unattributed-organic drop) never ran against a real broker.
 * - `consent-events-in` is the ADR-0200 D2 push mechanism (the #2749 path): a marketing-scope
 *   ConsentRevoked/ConsentExpired must signal every live journey of the party mid-flight. Its
 *   failure modes were all discovered in production (#2749: a plain non-suspend consumer nacked
 *   fail-stop and the readiness check went DOWN) — the channel's real contract, including what a
 *   poison record does on a channel whose consumer does NOT catch parse errors (it is nacked onto
 *   the configured DLQ, and the channel keeps flowing), was never asserted on the wire.
 *
 * Negative assertions wait on a sentinel published AFTER the message under test: the negative is
 * only observable once the consumer provably moved past the record.
 */
@QuarkusTest
@TestProfile(CampaignEngagementConsentE2EProfile::class)
class CampaignEngagementConsentE2E {

    @Inject
    lateinit var signaller: RecordingJourneySignaller

    // ── engagement-events-in ─────────────────────────────────────────────────────

    @Test
    fun `an attributable engagement event projects one no-PII row`() {
        val campaignId = UUID.randomUUID()
        val eventId = UUID.randomUUID()
        val at = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS)

        publish(
            ENGAGEMENT_TOPIC,
            """{"eventId":"$eventId","campaignId":"$campaignId","stepOrder":2,"channel":"PUSH",""" +
                """"slot":"HOME_BANNER","type":"CLICK","occurredAt":"$at"}""",
        )
        awaitEngagementRow(eventId)
        val row = engagementRow(eventId)
        assertThat(row).isNotNull
        assertThat(row!!.second).isEqualTo(campaignId)
        assertThat(row.third).isEqualTo(2)
        assertThat(row.fourth).isEqualTo("PUSH")
        assertThat(row.fifth).isEqualTo("HOME_BANNER")
        assertThat(row.sixth).isEqualTo("CLICK")
        assertThat(row.seventh).isEqualTo(Timestamp.from(at))
    }

    @Test
    fun `a redelivered engagement event projects exactly one row`() {
        val eventId = UUID.randomUUID()
        val payload =
            """{"eventId":"$eventId","campaignId":"${UUID.randomUUID()}","stepOrder":0,"channel":"BANNER",""" +
                """"slot":"REWARDS_HUB","type":"IMPRESSION","occurredAt":"${Instant.now()}"}"""

        publish(ENGAGEMENT_TOPIC, payload)
        publish(ENGAGEMENT_TOPIC, payload)
        awaitEngagementRow(eventId)
        assertThat(engagementCount(eventId))
            .describedAs("event_id is the projection's idempotence key under at-least-once delivery")
            .isEqualTo(1)
    }

    @Test
    fun `an organic event without a campaign tuple is ignored`() {
        val sentinel = UUID.randomUUID()

        // The source topic also carries organic content events; a missing campaign tuple means
        // "unattributed", not zero engagement — the record must be dropped, not joined to a
        // recent campaign.
        publish(
            ENGAGEMENT_TOPIC,
            """{"eventId":"${UUID.randomUUID()}","type":"CLICK","occurredAt":"${Instant.now()}"}""",
        )
        publish(
            ENGAGEMENT_TOPIC,
            """{"eventId":"$sentinel","campaignId":"${UUID.randomUUID()}","stepOrder":1,"channel":"PUSH",""" +
                """"slot":"PRODUCT_FEED","type":"DISMISS","occurredAt":"${Instant.now()}"}""",
        )
        awaitEngagementRow(sentinel)
    }

    // ── consent-events-in ────────────────────────────────────────────────────────

    @Test
    fun `a marketing consent revocation signals every live journey of the party`() {
        val partyId = UUID.randomUUID()
        val (campaignA, campaignB) = seedTwoActiveEnrolments(partyId)

        publish(
            CONSENT_TOPIC,
            """{"type":"ConsentRevoked","partyId":"$partyId","scopes":["MARKETING_COMMS_EMAIL"]}""",
        )
        awaitConsentSignal(campaignA, partyId)
        awaitConsentSignal(campaignB, partyId)
        assertThat(signaller.signals("consent-revoked", campaignA, partyId)).hasSize(1)
        assertThat(signaller.signals("consent-revoked", campaignB, partyId)).hasSize(1)
    }

    @Test
    fun `a revocation outside marketing scopes signals nothing`() {
        val partyId = UUID.randomUUID()
        val (campaignId, _) = seedTwoActiveEnrolments(partyId)

        publish(
            CONSENT_TOPIC,
            """{"type":"ConsentRevoked","partyId":"$partyId","scopes":["EMAIL_TRANSACTIONS"]}""",
        )
        publish(
            CONSENT_TOPIC,
            """{"type":"ConsentRevoked","partyId":"$partyId","scopes":["MARKETING_COMMS_PUSH"]}""",
        )
        awaitConsentSignal(campaignId, partyId)
        assertThat(signaller.signals("consent-revoked", campaignId, partyId))
            .describedAs("only the marketing-scope sentinel may signal; the transactional revocation must not")
            .hasSize(1)
    }

    @Test
    fun `an expired marketing consent terminates journeys too`() {
        val partyId = UUID.randomUUID()
        val (campaignId, _) = seedTwoActiveEnrolments(partyId)

        // ConsentExpired is the second accepted type, and the wire spells the type as
        // `eventType` (the consumer reads `type` first, falling back to `eventType`).
        publish(
            CONSENT_TOPIC,
            """{"eventType":"ConsentExpired","partyId":"$partyId","scopes":["MARKETING_COMMS"]}""",
        )
        awaitConsentSignal(campaignId, partyId)
    }

    @Test
    fun `a poison consent record is parked on the DLQ and the channel keeps flowing`() {
        val partyId = UUID.randomUUID()
        val (campaignId, _) = seedTwoActiveEnrolments(partyId)

        // ConsentEventConsumer deliberately does NOT catch parse errors: the record is nacked
        // and the channel's dead-letter-queue strategy parks it — measured here by reading the
        // DLQ topic with a fresh consumer group while the live channel keeps flowing.
        publish(CONSENT_TOPIC, "{not-json")
        val parked = awaitDlqRecord()
        assertThat(parked).contains("{not-json")
        publish(
            CONSENT_TOPIC,
            """{"type":"ConsentRevoked","partyId":"$partyId","scopes":["MARKETING_COMMS_EMAIL"]}""",
        )
        awaitConsentSignal(campaignId, partyId)
    }

    // ── Kafka producer / consumer ────────────────────────────────────────────────

    private fun publish(topic: String, payload: String) {
        val props = mapOf(
            "bootstrap.servers" to
                ConfigProvider.getConfig().getValue("kafka.bootstrap.servers", String::class.java),
            "key.serializer" to StringSerializer::class.qualifiedName,
            "value.serializer" to StringSerializer::class.qualifiedName,
            "acks" to "all",
        )
        KafkaProducer<String, String>(props).use { p ->
            p.send(ProducerRecord(topic, UUID.randomUUID().toString(), payload)).get(10, TimeUnit.SECONDS)
        }
    }

    private fun awaitDlqRecord(): String {
        val props = mapOf(
            "bootstrap.servers" to
                ConfigProvider.getConfig().getValue("kafka.bootstrap.servers", String::class.java),
            "group.id" to "e2e-consent-dlq-read-${UUID.randomUUID()}",
            "key.deserializer" to StringDeserializer::class.qualifiedName,
            "value.deserializer" to StringDeserializer::class.qualifiedName,
            "auto.offset.reset" to "earliest",
            "enable.auto.commit" to "false",
        )
        val deadline = Instant.now().plus(Duration.ofSeconds(15))
        KafkaConsumer<String, String>(props).use { c ->
            c.subscribe(listOf(CONSENT_DLQ_TOPIC))
            while (Instant.now().isBefore(deadline)) {
                for (record in c.poll(Duration.ofSeconds(1))) {
                    return record.value()
                }
            }
        }
        throw AssertionError("no record reached $CONSENT_DLQ_TOPIC within 15s — the nack was not parked")
    }

    // ── JDBC seeds and read-back ─────────────────────────────────────────────────

    /** Two campaigns, each with one ACTIVE enrolment for [partyId]. Returns both campaign ids. */
    private fun seedTwoActiveEnrolments(partyId: UUID): Pair<UUID, UUID> {
        val campaignA = UUID.randomUUID()
        val campaignB = UUID.randomUUID()
        jdbc { c ->
            listOf(campaignA, campaignB).forEach { campaignId ->
                c.prepareStatement(
                    "INSERT INTO campaigns (id, name, goal, segment_name, segment_version, steps_json, " +
                        "state, created_by, created_at, updated_at, product_kind) " +
                        "VALUES (?, ?, ?, ?, 1, '[]'::jsonb, 'ACTIVE', 'e2e', ?, ?, 'NONE')",
                ).use { ps ->
                    ps.setObject(1, campaignId)
                    ps.setString(2, "e2e consent campaign")
                    ps.setString(3, "consent")
                    ps.setString(4, "seg-${UUID.randomUUID()}")
                    ps.setObject(5, Timestamp.from(Instant.now()))
                    ps.setObject(6, Timestamp.from(Instant.now()))
                    ps.executeUpdate()
                }
                c.prepareStatement(
                    "INSERT INTO enrolments (id, campaign_id, party_id, state, current_step, started_at, " +
                        "experiment_cohort) VALUES (?, ?, ?, 'ACTIVE', 1, ?, 'TREATMENT')",
                ).use { ps ->
                    ps.setObject(1, UUID.randomUUID())
                    ps.setObject(2, campaignId)
                    ps.setObject(3, partyId)
                    ps.setObject(4, Timestamp.from(Instant.now()))
                    ps.executeUpdate()
                }
            }
        }
        return campaignA to campaignB
    }

    private fun awaitEngagementRow(eventId: UUID) {
        val deadline = Instant.now().plus(Duration.ofSeconds(20))
        var last = engagementCount(eventId)
        while (last == 0L && Instant.now().isBefore(deadline)) {
            Thread.sleep(100)
            last = engagementCount(eventId)
        }
        assertThat(last).describedAs("projection row for $eventId").isEqualTo(1)
    }

    private fun awaitConsentSignal(campaignId: UUID, partyId: UUID) {
        val deadline = Instant.now().plus(Duration.ofSeconds(20))
        while (Instant.now().isBefore(deadline)) {
            if (signaller.signals("consent-revoked", campaignId, partyId).isNotEmpty()) return
            Thread.sleep(100)
        }
        throw AssertionError("no consent-revoked signal for $partyId in $campaignId within 20s")
    }

    private fun engagementCount(eventId: UUID): Long = query(
        "select count(*) from campaign_engagement_event where event_id = ?",
        eventId,
    ) { rs ->
        rs.next()
        rs.getLong(1)
    }

    private fun engagementRow(eventId: UUID): Septet? = query(
        "select event_id, campaign_id, step_order, channel, surface, type, occurred_at " +
            "from campaign_engagement_event where event_id = ?",
        eventId,
    ) { rs ->
        if (!rs.next()) {
            null
        } else {
            Septet(
                rs.getObject(1, UUID::class.java),
                rs.getObject(2, UUID::class.java),
                rs.getInt(3),
                rs.getString(4),
                rs.getString(5),
                rs.getString(6),
                rs.getTimestamp(7),
            )
        }
    }

    private data class Septet(
        val first: UUID,
        val second: UUID,
        val third: Int,
        val fourth: String,
        val fifth: String,
        val sixth: String,
        val seventh: Timestamp?,
    )

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
        private const val ENGAGEMENT_TOPIC = "openbank.engagement.events"
        private const val CONSENT_TOPIC = "openbank.consent.events"
        private const val CONSENT_DLQ_TOPIC = "openbank.dlq.campaign.consent-events-in"

        @BeforeAll
        @JvmStatic
        fun ensureTopics() {
            val bootstrap = ConfigProvider.getConfig().getValue("kafka.bootstrap.servers", String::class.java)
            Admin.create(mapOf("bootstrap.servers" to bootstrap)).use { admin ->
                val existing = admin.listTopics().names().get(30, TimeUnit.SECONDS)
                val wanted = listOf(ENGAGEMENT_TOPIC, CONSENT_TOPIC, CONSENT_DLQ_TOPIC).filter { it !in existing }
                if (wanted.isNotEmpty()) createTopicsIgnoringRace(admin, wanted)
            }
        }

        private fun createTopicsIgnoringRace(admin: Admin, wanted: List<String>) {
            try {
                admin.createTopics(wanted.map { NewTopic(it, 1, 1.toShort()) }).all()
                    .get(30, TimeUnit.SECONDS)
            } catch (e: java.util.concurrent.ExecutionException) {
                // the app auto-creates topics at boot and can win the race between our
                // listTopics and createTopics — an existing topic is the outcome we wanted
                if (e.cause !is org.apache.kafka.common.errors.TopicExistsException) throw e
            }
        }
    }
}
