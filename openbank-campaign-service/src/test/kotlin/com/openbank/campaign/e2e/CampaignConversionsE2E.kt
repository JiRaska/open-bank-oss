// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.campaign.e2e

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
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
 * Real-broker E2E for the two ADR-0245 conversion consumers (`account-conversions-in`,
 * `card-conversions-in`). ConversionConsumer only had mocked unit coverage; the load-bearing
 * wire behaviour — the producers disagree on WHERE the event type lives (AccountCreatedEvent
 * serialises `eventType` into the payload, CardIssued carries none and the type travels only as
 * the outbox `ce-type` header) — was never exercised against a real broker with both producers'
 * shapes. The suite seeds an ACTIVE campaign carrying the conversion rule plus a TREATMENT
 * enrolment with a real SENT interaction (attribution starts at first send), publishes raw
 * product events to the real topics, and reads the CONVERTED outcome back over JDBC.
 *
 * Negative assertions wait on a sentinel published AFTER the messages under test: "not
 * converted" is observed only after the consumer provably moved past the message.
 */
@QuarkusTest
@TestProfile(CampaignConversionsE2EProfile::class)
class CampaignConversionsE2E {

    @Inject
    lateinit var signaller: RecordingJourneySignaller

    // ── scenarios ────────────────────────────────────────────────────────────────

    @Test
    fun `an AccountCreated on the account topic attributes a conversion to the sent enrolment`() {
        val (campaignId, partyId) = seedCampaignWithSentEnrolment("ACCOUNT_OPENED")

        publish(ACCOUNT_TOPIC, null, accountPayload(UUID.randomUUID().toString(), partyId))
        awaitConverted(campaignId, partyId)
        assertThat(sentCount(campaignId, partyId)).isEqualTo(1)
        assertThat(signaller.signals("goal-reached", campaignId, partyId))
            .describedAs("the TREATMENT cohort must be signalled so the journey stops persuading")
            .hasSize(1)
    }

    @Test
    fun `a CardIssued carrying no payload eventType converts via the ce-type header alone`() {
        val (campaignId, partyId) = seedCampaignWithSentEnrolment("CARD_ISSUED")

        // CardIssued has NO eventType field of its own: the type travels only as the outbox
        // ce-type header. This record is byte-for-byte what card-service's outbox emits.
        publish(
            CARD_TOPIC,
            mapOf("ce-type" to "CardIssued"),
            """{"partyId":"$partyId","aggregateId":"${UUID.randomUUID()}","occurredAt":"${Instant.now()}"}""",
        )
        awaitConverted(campaignId, partyId)
        assertThat(signaller.signals("goal-reached", campaignId, partyId))
            .describedAs("the TREATMENT cohort must be signalled so the journey stops persuading")
            .hasSize(1)
    }

    @Test
    fun `an unrelated eventType on the shared card topic is not a conversion`() {
        val (campaignId, partyId) = seedCampaignWithSentEnrolment("CARD_ISSUED")
        // The sentinel party carries its own sent enrolment: "not converted" is only observable
        // once the consumer provably moved PAST the CardLimitChanged record, which a converting
        // sentinel proves.
        val (sentinelCampaign, sentinelParty) = seedCampaignWithSentEnrolment("CARD_ISSUED")

        publish(
            CARD_TOPIC,
            mapOf("ce-type" to "CardLimitChanged"),
            """{"partyId":"$partyId","occurredAt":"${Instant.now()}"}""",
        )
        publish(
            CARD_TOPIC,
            mapOf("ce-type" to "CardIssued"),
            """{"partyId":"$sentinelParty","occurredAt":"${Instant.now()}"}""",
        )
        awaitConverted(sentinelCampaign, sentinelParty)
        assertThat(signaller.signals("goal-reached", sentinelCampaign, sentinelParty))
            .describedAs("the converting sentinel must signal its own journey")
            .hasSize(1)
        assertThat(convertedCount(campaignId, partyId))
            .describedAs("a limit change on a shared topic must never count as a conversion")
            .isZero()
    }

    // ── Kafka producer ───────────────────────────────────────────────────────────

    private fun publish(topic: String, headers: Map<String, String>?, payload: String) {
        val props = mapOf(
            "bootstrap.servers" to
                ConfigProvider.getConfig().getValue("kafka.bootstrap.servers", String::class.java),
            "key.serializer" to StringSerializer::class.qualifiedName,
            "value.serializer" to StringSerializer::class.qualifiedName,
            "acks" to "all",
        )
        KafkaProducer<String, String>(props).use { p ->
            val record = ProducerRecord<String, String>(topic, UUID.randomUUID().toString(), payload)
            headers?.forEach { (k, v) -> record.headers().add(k, v.toByteArray()) }
            p.send(record).get(10, TimeUnit.SECONDS)
        }
    }

    private fun accountPayload(eventId: String, partyId: UUID) =
        """{"eventId":"$eventId","aggregateId":"${UUID.randomUUID()}","aggregateType":"Account",""" +
            """"eventType":"AccountCreated","version":0,"accountNumber":"123","accountType":"CURRENT",""" +
            """"partyId":"$partyId","productId":"${UUID.randomUUID()}","currency":"CZK",""" +
            """"occurredAt":"${Instant.now()}","sourceService":"account-service"}"""

    // ── JDBC seeds and read-back ─────────────────────────────────────────────────

    /**
     * One ACTIVE campaign carrying [conversionRule], plus the party's TREATMENT enrolment and
     * the SENT interaction that starts the attribution window. Returns campaign id and party id.
     */
    private fun seedCampaignWithSentEnrolment(conversionRule: String): Pair<UUID, UUID> {
        val campaignId = UUID.randomUUID()
        val partyId = UUID.randomUUID()
        jdbc { c ->
            c.prepareStatement(
                "INSERT INTO campaigns (id, name, goal, segment_name, segment_version, steps_json, " +
                    "state, created_by, created_at, updated_at, conversion_rule, product_kind) " +
                    "VALUES (?, ?, ?, ?, ?, ?::jsonb, 'ACTIVE', 'e2e', ?, ?, ?, 'NONE')",
            ).use { ps ->
                ps.setObject(1, campaignId)
                ps.setString(2, "e2e conversion campaign")
                ps.setString(3, "conversion")
                ps.setString(4, "everyone")
                ps.setInt(5, 1)
                ps.setString(
                    6,
                    """[{"order":1,"template":"MARKETING_PRODUCT_OFFER","channel":"EMAIL",""" +
                        """"variables":{"offerTitle":"T","offerText":"X","ctaText":"Go"},"delaySeconds":0}]""",
                )
                ps.setObject(7, Timestamp.from(Instant.now()))
                ps.setObject(8, Timestamp.from(Instant.now()))
                ps.setString(9, conversionRule)
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
            c.prepareStatement(
                "INSERT INTO send_log (id, campaign_id, party_id, step_order, outcome, occurred_at, channel) " +
                    "VALUES (?, ?, ?, 1, 'SENT', ?, 'EMAIL')",
            ).use { ps ->
                ps.setObject(1, UUID.randomUUID())
                ps.setObject(2, campaignId)
                ps.setObject(3, partyId)
                ps.setObject(4, Timestamp.from(Instant.now()))
                ps.executeUpdate()
            }
        }
        return campaignId to partyId
    }

    private fun awaitConverted(campaignId: UUID, partyId: UUID) {
        val deadline = Instant.now().plus(Duration.ofSeconds(20))
        var last = convertedCount(campaignId, partyId)
        while (last == 0L && Instant.now().isBefore(deadline)) {
            Thread.sleep(100)
            last = convertedCount(campaignId, partyId)
        }
        assertThat(last).describedAs("CONVERTED outcome for $partyId in $campaignId").isEqualTo(1)
    }

    private fun convertedCount(campaignId: UUID, partyId: UUID): Long = count(
        "select count(*) from send_log where campaign_id = ? and party_id = ? and outcome = 'CONVERTED'",
        campaignId,
        partyId,
    )

    private fun sentCount(campaignId: UUID, partyId: UUID): Long = count(
        "select count(*) from send_log where campaign_id = ? and party_id = ? and outcome = 'SENT'",
        campaignId,
        partyId,
    )

    private fun count(sql: String, vararg params: Any): Long = jdbc { c ->
        c.prepareStatement(sql).use { ps ->
            params.forEachIndexed { i, p -> ps.setObject(i + 1, p) }
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }
    }

    private fun <T> jdbc(block: (Connection) -> T): T = DriverManager.getConnection(
        ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String::class.java),
        ConfigProvider.getConfig().getValue("quarkus.datasource.username", String::class.java),
        ConfigProvider.getConfig().getValue("quarkus.datasource.password", String::class.java),
    ).use(block)

    companion object {
        private const val ACCOUNT_TOPIC = "openbank.accounts.account.created"
        private const val CARD_TOPIC = "openbank.cards.events"

        @BeforeAll
        @JvmStatic
        fun ensureTopics() {
            val bootstrap = ConfigProvider.getConfig().getValue("kafka.bootstrap.servers", String::class.java)
            Admin.create(mapOf("bootstrap.servers" to bootstrap)).use { admin ->
                val existing = admin.listTopics().names().get(30, TimeUnit.SECONDS)
                val wanted = listOf(ACCOUNT_TOPIC, CARD_TOPIC).filter { it !in existing }
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
