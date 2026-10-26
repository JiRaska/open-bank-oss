// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.e2e

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
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Real-broker E2E for all five engagement consumers (ADR-0220 D3.5's targeting exclusions plus
 * the campaign-banner placement command). The pre-existing ITs prove consumer decisions either
 * in memory or not at all — no test ever published to the incoming topics, so the production
 * wire (raw producer → real partition → real consumer group → JDBC row) is what this suite pins.
 *
 * Every negative assertion waits on a sentinel published AFTER the messages under test: "nothing
 * happened" is observed only after the consumer provably moved past the message, not before it
 * ran — the same discipline as the in-memory tests, now over five real partitions.
 */
@QuarkusTest
@TestProfile(EngagementAdverseStateE2EProfile::class)
class EngagementAdverseStateE2E {

    // ── scenarios ────────────────────────────────────────────────────────────────

    @Test
    fun `PARTY_ERASED permanently excludes the party and non-erasure events are ignored`() {
        val erased = UUID.randomUUID()
        val ignored = UUID.randomUUID()
        val erased2 = UUID.randomUUID()

        publish(PARTY_TOPIC, """{"eventType":"PARTY_ERASED","partyId":"$erased"}""", erased.toString())
        publish(PARTY_TOPIC, """{"eventType":"PARTY_CREATED","partyId":"$ignored"}""", ignored.toString())
        publish(PARTY_TOPIC, "not json at all", UUID.randomUUID().toString())
        publish(PARTY_TOPIC, """{"eventType":"PARTY_ERASED","partyId":"$erased2"}""", erased2.toString())
        awaitPartySentinel()

        assertThat(adverseRows(erased, ERASURE)).describedAs("erasure exclusion materialised").isEqualTo(1)
        assertThat(adverseRows(ignored, ERASURE)).describedAs("PARTY_CREATED never erases").isZero()
    }

    @Test
    fun `fraud hold sets the exclusion and active false clears only that party`() {
        val held = UUID.randomUUID()
        val stillHeld = UUID.randomUUID()

        publish(FRAUD_TOPIC, """{"partyId":"$held","active":true}""", held.toString())
        publish(FRAUD_TOPIC, """{"partyId":"$stillHeld","active":true}""", stillHeld.toString())
        publish(FRAUD_TOPIC, """{"partyId":"$held","active":false}""", held.toString())
        awaitFraudSentinel()

        assertThat(adverseRows(held, FRAUD_HOLD)).describedAs("hold cleared by active false").isZero()
        assertThat(adverseRows(stillHeld, FRAUD_HOLD)).describedAs("sibling hold untouched").isEqualTo(1)
    }

    @Test
    fun `loan stage change sets ARREARS over zero daysPastDue and clears it later`() {
        val arrears = UUID.randomUUID()
        val wrongType = UUID.randomUUID()

        publish(LENDING_TOPIC, loanStageJson(arrears, 45), arrears.toString())
        publish(
            LENDING_TOPIC,
            """{"eventType":"loan.other","partyId":"$wrongType","daysPastDue":90}""",
            wrongType.toString(),
        )
        publish(LENDING_TOPIC, loanStageJson(arrears, 0), arrears.toString())
        awaitLendingSentinel()

        assertThat(adverseRows(arrears, ARREARS)).describedAs("dpd 0 clears arrears").isZero()
        assertThat(adverseRows(wrongType, ARREARS)).describedAs("non stage-change ignored").isZero()
    }

    @Test
    fun `dispute opened sets the exclusion and dispute resolved clears it`() {
        val disputed = UUID.randomUUID()

        publish(DISPUTE_TOPIC, """{"eventType":"dispute.opened","partyId":"$disputed"}""", disputed.toString())
        publish(DISPUTE_TOPIC, """{"eventType":"dispute.resolved","partyId":"$disputed"}""", disputed.toString())
        awaitDisputeSentinel()

        assertThat(adverseRows(disputed, DISPUTE)).describedAs("resolution lifts the exclusion").isZero()
    }

    @Test
    fun `banner placement command persists with its slot and an invalid slot is skipped`() {
        val interaction = UUID.randomUUID()
        val badSlot = UUID.randomUUID()

        publish(BANNER_TOPIC, bannerJson(interaction, UUID.randomUUID(), "HOME_CAROUSEL"), interaction.toString())
        publish(BANNER_TOPIC, bannerJson(badSlot, UUID.randomUUID(), "NOT_A_SLOT"), badSlot.toString())
        awaitBannerSentinel()

        assertThat(bannerRows(interaction)).describedAs("placement persisted").isEqualTo(1)
        assertThat(bannerRows(badSlot)).describedAs("invalid slot never persisted").isZero()
    }

    @Test
    fun `poison payloads never wedge any of the five channels`() {
        for (topic in ALL_TOPICS) publish(topic, "definitely not json", UUID.randomUUID().toString())
        awaitPartySentinel()
        awaitFraudSentinel()
        awaitLendingSentinel()
        awaitDisputeSentinel()
        awaitBannerSentinel()
    }

    // ── Kafka producer ───────────────────────────────────────────────────────────

    private fun publish(topic: String, payload: String, key: String) {
        producer().use { p ->
            p.send(ProducerRecord(topic, key, payload)).get(10, TimeUnit.SECONDS)
        }
    }

    private fun producer() = KafkaProducer<String, String>(
        mapOf(
            "bootstrap.servers" to
                ConfigProvider.getConfig().getValue("kafka.bootstrap.servers", String::class.java),
            "key.serializer" to StringSerializer::class.qualifiedName,
            "value.serializer" to StringSerializer::class.qualifiedName,
            "enable.idempotence" to "true",
            "acks" to "all",
        ),
    )

    private fun loanStageJson(party: UUID, daysPastDue: Int): String =
        """{"eventType":"loan.stage_changed","partyId":"$party","daysPastDue":$daysPastDue}"""

    private fun bannerJson(interaction: UUID, party: UUID, slot: String): String {
        // template must be the one the slot renders (CampaignBannerPlacement init validates the
        // pair); variables are the declared PRODUCT_OFFER set.
        val template = if (slot == "HOME_CAROUSEL") {
            "MARKETING_PRODUCT_OFFER_CAROUSEL"
        } else {
            "MARKETING_PRODUCT_OFFER_BANNER"
        }
        return """{"interactionRef":"$interaction","partyId":"$party","campaignId":"${UUID.randomUUID()}",""" +
            """"stepOrder":2,"template":"$template",""" +
            """"variables":{"offerTitle":"2.9 % savings","ctaText":"Open"},""" +
            """"deepLink":"openbank://savings","inAppSurface":"$slot"}"""
    }

    // ── JDBC read-back ───────────────────────────────────────────────────────────

    private fun <T> jdbc(block: (Connection) -> T): T = DriverManager.getConnection(
        ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String::class.java),
        ConfigProvider.getConfig().getValue("quarkus.datasource.username", String::class.java),
        ConfigProvider.getConfig().getValue("quarkus.datasource.password", String::class.java),
    ).use(block)

    private fun adverseRows(party: UUID, state: String): Long = count(
        "SELECT count(*) FROM party_adverse_state WHERE party_id = ? AND state = ?",
        party,
        state,
    )

    private fun bannerRows(interaction: UUID): Long = count(
        "SELECT count(*) FROM campaign_banner_placement WHERE interaction_ref = ?",
        interaction,
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

    // ── sentinels: a valid message after the batch under test; its row proves liveness ──

    private fun awaitPartySentinel() {
        val sentinel = UUID.randomUUID()
        publish(PARTY_TOPIC, """{"eventType":"PARTY_ERASED","partyId":"$sentinel"}""", sentinel.toString())
        awaitRow({ adverseRows(sentinel, ERASURE) }, "party-events-in sentinel")
    }

    private fun awaitFraudSentinel() {
        val sentinel = UUID.randomUUID()
        publish(FRAUD_TOPIC, """{"partyId":"$sentinel","active":true}""", sentinel.toString())
        awaitRow({ adverseRows(sentinel, FRAUD_HOLD) }, "fraud-hold-events-in sentinel")
    }

    private fun awaitLendingSentinel() {
        val sentinel = UUID.randomUUID()
        publish(LENDING_TOPIC, loanStageJson(sentinel, 7), sentinel.toString())
        awaitRow({ adverseRows(sentinel, ARREARS) }, "lending-events-in sentinel")
    }

    private fun awaitDisputeSentinel() {
        val sentinel = UUID.randomUUID()
        publish(DISPUTE_TOPIC, """{"eventType":"dispute.opened","partyId":"$sentinel"}""", sentinel.toString())
        awaitRow({ adverseRows(sentinel, DISPUTE) }, "dispute-events-in sentinel")
    }

    private fun awaitBannerSentinel() {
        val interaction = UUID.randomUUID()
        publish(BANNER_TOPIC, bannerJson(interaction, UUID.randomUUID(), "HOME_BANNER"), interaction.toString())
        awaitRow({ bannerRows(interaction) }, "campaign-banner-placements-in sentinel")
    }

    private fun awaitRow(current: () -> Long, description: String) {
        val deadline = Instant.now().plus(Duration.ofSeconds(20))
        while (current() == 0L && Instant.now().isBefore(deadline)) Thread.sleep(100)
        assertThat(current()).describedAs(description).isEqualTo(1)
    }

    companion object {
        private const val PARTY_TOPIC = "openbank.party.events"
        private const val FRAUD_TOPIC = "openbank.fraud.hold.changed"
        private const val LENDING_TOPIC = "openbank.lending.events"
        private const val DISPUTE_TOPIC = "openbank.dispute.events"
        private const val BANNER_TOPIC = "openbank.campaign.banner.placements"

        private const val ERASURE = "ERASURE_REQUESTED"
        private const val FRAUD_HOLD = "FRAUD_HOLD"
        private const val ARREARS = "ARREARS"
        private const val DISPUTE = "DISPUTE_OPENED"

        private val ALL_TOPICS = listOf(PARTY_TOPIC, FRAUD_TOPIC, LENDING_TOPIC, DISPUTE_TOPIC, BANNER_TOPIC)

        @BeforeAll
        @JvmStatic
        fun ensureTopics() {
            val bootstrap = ConfigProvider.getConfig().getValue("kafka.bootstrap.servers", String::class.java)
            Admin.create(mapOf("bootstrap.servers" to bootstrap)).use { admin ->
                val existing = admin.listTopics().names().get(30, TimeUnit.SECONDS)
                val wanted = ALL_TOPICS.filter { it !in existing }
                if (wanted.isNotEmpty()) {
                    admin.createTopics(wanted.map { NewTopic(it, 1, 1.toShort()) }).all()
                        .get(30, TimeUnit.SECONDS)
                }
            }
        }
    }
}
