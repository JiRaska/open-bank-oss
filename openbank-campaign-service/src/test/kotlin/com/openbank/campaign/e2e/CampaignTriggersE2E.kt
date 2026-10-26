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
 * Real-broker E2E for the two trigger consumers (`account-triggers-in`, `card-triggers-in`) — the
 * ADR push path where a product event enrols a party into a waiting campaign within seconds.
 * EnrolmentTriggerConsumer only had mocked unit coverage; the load-bearing wire behaviour was
 * never exercised against a real broker:
 *
 * - the ce-type-header-first event-type matching (CardIssued carries no payload eventType, so
 *   without the header a card-issuance campaign would never fire — and without the check, a limit
 *   change on the shared topic would enrol the party),
 * - the segment gate on the enrolment path (a trigger decides WHEN, the approved segment decides
 *   WHO — the consumer must ack a party it cannot enrol, not nack it forever),
 * - the ack/nack contract (poison pills are acked and named; dependency failures nack onto the
 *   configured DLQ strategy), and
 * - the durable journey-start intent: persisted BEFORE the journey start, deleted only after the
 *   ACTIVE enrolment commits — after a successful trigger the intents table is empty by design.
 *
 * Segment membership is answered by a fake ClickHouse (see CampaignTriggersE2EProfile), so both
 * the positive and the negative run against the same generated SQL and the real evaluator wire.
 * Negative assertions wait on a sentinel published AFTER the message under test, on the same
 * partition: "not enrolled" is only observable once the consumer provably moved past the record.
 */
@QuarkusTest
@TestProfile(CampaignTriggersE2EProfile::class)
class CampaignTriggersE2E {

    @Inject
    lateinit var signaller: RecordingJourneySignaller

    // ── scenarios ────────────────────────────────────────────────────────────────

    @Test
    fun `an AccountCreated enrols the party into a waiting campaign and starts its journey`() {
        val (campaignId, partyId) = seedWaitingCampaign("ACCOUNT_OPENED")
        FakeSilverLayer.members.add(partyId)

        publish(ACCOUNT_TOPIC, null, accountPayload(UUID.randomUUID().toString(), partyId))
        awaitEnrolment(campaignId, partyId)
        assertThat(signaller.signals("start-linear", campaignId, partyId))
            .describedAs("a triggered enrolment must start the party's journey")
            .hasSize(1)
        assertThat(intentCount(campaignId, partyId))
            .describedAs("the durable intent is deleted only after the enrolment commits")
            .isZero()
    }

    @Test
    fun `a CardIssued carrying only the ce-type header enrols into a card campaign`() {
        val (campaignId, partyId) = seedWaitingCampaign("CARD_ISSUED")
        FakeSilverLayer.members.add(partyId)

        // CardIssued has NO eventType field of its own: the type travels only as the outbox
        // ce-type header. This record is byte-for-byte what card-service's outbox emits; without
        // the header-first match a card-issuance campaign would never fire at all.
        publish(
            CARD_TOPIC,
            mapOf("ce-type" to "CardIssued"),
            """{"partyId":"$partyId","aggregateId":"${UUID.randomUUID()}","occurredAt":"${Instant.now()}"}""",
        )
        awaitEnrolment(campaignId, partyId)
        assertThat(signaller.signals("start-linear", campaignId, partyId))
            .describedAs("a triggered enrolment must start the party's journey")
            .hasSize(1)
    }

    @Test
    fun `a party outside the segment is acked without an enrolment`() {
        val (campaignId, outsider) = seedWaitingCampaign("ACCOUNT_OPENED")
        val (sentinelCampaign, sentinelParty) = seedWaitingCampaign("ACCOUNT_OPENED")
        FakeSilverLayer.members.add(sentinelParty)

        publish(ACCOUNT_TOPIC, null, accountPayload(UUID.randomUUID().toString(), outsider))
        publish(ACCOUNT_TOPIC, null, accountPayload(UUID.randomUUID().toString(), sentinelParty))
        awaitEnrolment(sentinelCampaign, sentinelParty)
        assertThat(enrolmentCount(campaignId, outsider))
            .describedAs("the segment decides WHO — an out-of-segment party must never enrol")
            .isZero()
    }

    @Test
    fun `a redelivered AccountCreated enrols exactly once and starts one journey`() {
        val (campaignId, partyId) = seedWaitingCampaign("ACCOUNT_OPENED")
        FakeSilverLayer.members.add(partyId)
        val eventId = UUID.randomUUID().toString()

        // The broker redelivers; the second delivery must answer ALREADY_ENROLLED, not start a
        // second journey and a second set of sends.
        publish(ACCOUNT_TOPIC, null, accountPayload(eventId, partyId))
        publish(ACCOUNT_TOPIC, null, accountPayload(eventId, partyId))
        awaitEnrolment(campaignId, partyId)
        assertThat(enrolmentCount(campaignId, partyId)).isEqualTo(1)
        assertThat(signaller.signals("start-linear", campaignId, partyId)).hasSize(1)
    }

    @Test
    fun `an unrelated eventType on the shared card topic enrols no one`() {
        val (campaignId, partyId) = seedWaitingCampaign("CARD_ISSUED")
        val (sentinelCampaign, sentinelParty) = seedWaitingCampaign("CARD_ISSUED")
        FakeSilverLayer.members.add(partyId)
        FakeSilverLayer.members.add(sentinelParty)

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
        awaitEnrolment(sentinelCampaign, sentinelParty)
        assertThat(enrolmentCount(campaignId, partyId))
            .describedAs("a limit change on a shared topic must never trigger an enrolment")
            .isZero()
    }

    @Test
    fun `a poison payload is dropped and the channel keeps flowing`() {
        val (campaignId, partyId) = seedWaitingCampaign("ACCOUNT_OPENED")
        FakeSilverLayer.members.add(partyId)

        // Unparseable JSON: replaying it fails identically forever, so the consumer acks it and
        // drops it — the valid sentinel afterwards proves the channel survived.
        publish(ACCOUNT_TOPIC, null, "{not-json")
        publish(ACCOUNT_TOPIC, null, accountPayload(UUID.randomUUID().toString(), partyId))
        awaitEnrolment(campaignId, partyId)
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
     * One ACTIVE campaign waiting on [trigger], with its own versioned segment (a PARTY_STATUS_IS
     * rule — the fake silver layer answers membership, the rule must only render valid SQL).
     * Returns campaign id and party id.
     */
    private fun seedWaitingCampaign(trigger: String): Pair<UUID, UUID> {
        val campaignId = UUID.randomUUID()
        val partyId = UUID.randomUUID()
        // Segment names must be kebab-case; a lowercase UUID is hex+dash, which matches.
        val segmentName = "seg-${UUID.randomUUID()}"
        jdbc { c ->
            c.prepareStatement(
                "INSERT INTO segments (id, name, version, rules_json, created_at) " +
                    "VALUES (?, ?, 1, '[{\"type\":\"PartyStatusIs\",\"status\":\"ACTIVE\"}]'::jsonb, ?)",
            ).use { ps ->
                ps.setObject(1, UUID.randomUUID())
                ps.setString(2, segmentName)
                ps.setObject(3, Timestamp.from(Instant.now()))
                ps.executeUpdate()
            }
            c.prepareStatement(
                "INSERT INTO campaigns (id, name, goal, segment_name, segment_version, steps_json, " +
                    "state, created_by, created_at, updated_at, product_kind, trigger_event) " +
                    "VALUES (?, ?, ?, ?, 1, ?::jsonb, 'ACTIVE', 'e2e', ?, ?, 'NONE', ?)",
            ).use { ps ->
                ps.setObject(1, campaignId)
                ps.setString(2, "e2e trigger campaign")
                ps.setString(3, "trigger")
                ps.setString(4, segmentName)
                ps.setString(
                    5,
                    """[{"order":1,"template":"MARKETING_PRODUCT_OFFER","channel":"EMAIL",""" +
                        """"variables":{"offerTitle":"T","offerText":"X","ctaText":"Go"},"delaySeconds":0}]""",
                )
                ps.setObject(6, Timestamp.from(Instant.now()))
                ps.setObject(7, Timestamp.from(Instant.now()))
                ps.setString(8, trigger)
                ps.executeUpdate()
            }
        }
        return campaignId to partyId
    }

    private fun awaitEnrolment(campaignId: UUID, partyId: UUID) {
        val deadline = Instant.now().plus(Duration.ofSeconds(20))
        var last = enrolmentCount(campaignId, partyId)
        while (last == 0L && Instant.now().isBefore(deadline)) {
            Thread.sleep(100)
            last = enrolmentCount(campaignId, partyId)
        }
        assertThat(last).describedAs("enrolment for $partyId in $campaignId").isEqualTo(1)
    }

    private fun enrolmentCount(campaignId: UUID, partyId: UUID): Long = count(
        "select count(*) from enrolments where campaign_id = ? and party_id = ?",
        campaignId,
        partyId,
    )

    private fun intentCount(campaignId: UUID, partyId: UUID): Long = count(
        "select count(*) from campaign_journey_start_intents where campaign_id = ? and party_id = ?",
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
