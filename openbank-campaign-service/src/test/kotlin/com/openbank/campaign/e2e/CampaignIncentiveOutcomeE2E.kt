// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.e2e

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerRecords
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
 * Real-broker E2E for the `incentive-events-in` consumer (the ADR read model behind Campaign
 * Studio's incentive funnel). Only the parse decisions had unit coverage — the projection was
 * never driven over the production wire. The suite seeds a campaign with its published incentive
 * offer plus the SENT interaction the attribution resolves through, publishes raw v2 reservation
 * evidence to the real topic and reads the derived row back over JDBC on
 * `campaign_incentive_outcome` — plus the DLQ topic, proving the configured
 * `failure-strategy: dead-letter-queue` actually parks a nacked record instead of stopping the
 * channel.
 *
 * Negative assertions wait on a sentinel published AFTER the messages under test: "not
 * projected" is observed only after the consumer provably moved past the message.
 */
@QuarkusTest
@TestProfile(CampaignIncentiveOutcomeE2EProfile::class)
class CampaignIncentiveOutcomeE2E {

    // ── scenarios ────────────────────────────────────────────────────────────────

    @Test
    fun `a committed v2 reservation projects one attributed outcome row, redelivery included`() {
        val (campaign, send) = seedCampaignWithOffer()
        val event = UUID.randomUUID()

        publishCommitted(event, send, campaign.offerId, campaign.offerName, campaign.offerVersion)
        publishCommitted(event, send, campaign.offerId, campaign.offerName, campaign.offerVersion) // redelivery
        awaitSentinel()

        assertThat(outcomeRows(event)).describedAs("exactly one row despite broker redelivery").isEqualTo(1)
        assertThat(outcomeStatus(event)).describedAs("projected status").isEqualTo("COMMITTED")
        assertThat(outcomeCampaign(event)).describedAs("attributed to the seeded campaign").isEqualTo(campaign.id)
    }

    @Test
    fun `an offerRef that mismatches the campaign's published offer is not projected`() {
        val (_, send) = seedCampaignWithOffer()
        val strangerOffer = UUID.randomUUID()
        val event = UUID.randomUUID()

        publishCommitted(event, send, strangerOffer, "Stranger Offer", 9)
        awaitSentinel()

        assertThat(outcomeRows(event)).describedAs("offer drift must never reach the funnel").isZero()
    }

    @Test
    fun `legacy and operator event types never reach the projection`() {
        val (_, send) = seedCampaignWithOffer()
        val legacy = UUID.randomUUID()

        publish(
            """{"eventType":"incentive.reservation.committed.v1","eventId":"$legacy",""" +
                """"reservationId":"${UUID.randomUUID()}","attributionRef":"$send",""" +
                """"offerRef":{"id":"${UUID.randomUUID()}","name":"Legacy","version":1},""" +
                """"status":"COMMITTED","occurredAt":"${Instant.now()}"}""",
            send.toString(),
        )
        publish(
            """{"eventType":"incentive.operator.override","eventId":"${UUID.randomUUID()}",""" +
                """"occurredAt":"${Instant.now()}"}""",
            send.toString(),
        )
        awaitSentinel()

        assertThat(outcomeRows(legacy)).describedAs("v1 evidence is intentionally out of contract").isZero()
    }

    @Test
    fun `a malformed recognized event is dead-lettered and the channel keeps flowing`() {
        val (campaign, send) = seedCampaignWithOffer()
        val marker = UUID.randomUUID().toString()

        // status contradicts the event type -> the consumer's all-or-nothing contract throws
        publish(
            """{"eventType":"incentive.reservation.committed.v2","eventId":"${UUID.randomUUID()}",""" +
                """"reservationId":"${UUID.randomUUID()}","attributionRef":"$send",""" +
                """"offerRef":{"id":"${campaign.offerId}","name":"${campaign.offerName}",""" +
                """"version":${campaign.offerVersion}},"status":"RESERVED",""" +
                """"occurredAt":"${Instant.now()}","marker":"$marker"}""",
            send.toString(),
        )
        awaitSentinel() // valid evidence after the poison still projects -> the channel is alive
        assertThat(dlqContains(marker)).describedAs("nacked record parked on the configured DLQ topic").isTrue()
    }

    // ── Kafka producer / consumer ────────────────────────────────────────────────

    private fun publishCommitted(
        event: UUID,
        attributionRef: UUID,
        offerId: UUID,
        offerName: String,
        offerVersion: Int,
    ) {
        publish(
            """{"eventType":"incentive.reservation.committed.v2","eventId":"$event",""" +
                """"reservationId":"${UUID.randomUUID()}","attributionRef":"$attributionRef",""" +
                """"offerRef":{"id":"$offerId","name":"$offerName","version":$offerVersion},""" +
                """"status":"COMMITTED","occurredAt":"${Instant.now()}"}""",
            attributionRef.toString(),
        )
    }

    private fun publish(payload: String, key: String) {
        producer().use { p ->
            p.send(ProducerRecord(TOPIC, key, payload)).get(10, TimeUnit.SECONDS)
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

    private fun dlqContains(marker: String): Boolean {
        val props = mapOf(
            "bootstrap.servers" to
                ConfigProvider.getConfig().getValue("kafka.bootstrap.servers", String::class.java),
            "group.id" to "campaign-e2e-dlq-${UUID.randomUUID()}",
            "key.deserializer" to StringDeserializer::class.qualifiedName,
            "value.deserializer" to StringDeserializer::class.qualifiedName,
            "auto.offset.reset" to "earliest",
            "enable.auto.commit" to "false",
        )
        KafkaConsumer<String, String>(props).use { consumer ->
            consumer.subscribe(listOf(DLQ_TOPIC))
            return pollForDlqRecord(consumer, marker, Instant.now().plus(Duration.ofSeconds(15)))
        }
    }

    private tailrec fun pollForDlqRecord(
        consumer: KafkaConsumer<String, String>,
        marker: String,
        deadline: Instant,
    ): Boolean {
        if (!Instant.now().isBefore(deadline)) return false
        val records: ConsumerRecords<String, String> = consumer.poll(Duration.ofMillis(500))
        if (records.any { it.value().contains(marker) }) return true
        return pollForDlqRecord(consumer, marker, deadline)
    }

    // ── JDBC seeds and read-back ─────────────────────────────────────────────────

    private data class SeededCampaign(val id: UUID, val offerId: UUID, val offerName: String, val offerVersion: Int)

    /** One ACTIVE campaign carrying a published incentive offer, plus its SENT PUSH interaction. */
    private fun seedCampaignWithOffer(): Pair<SeededCampaign, UUID> {
        val campaign = SeededCampaign(
            id = UUID.randomUUID(),
            offerId = UUID.randomUUID(),
            offerName = "E2E Spring Offer ${UUID.randomUUID().toString().take(8)}",
            offerVersion = 1,
        )
        val send = UUID.randomUUID()
        jdbc { c ->
            c.prepareStatement(
                "INSERT INTO campaigns (id, name, goal, segment_name, segment_version, steps_json, " +
                    "state, created_by, created_at, updated_at, incentive_offer_id, " +
                    "incentive_offer_name, incentive_offer_version, product_kind) " +
                    "VALUES (?, ?, ?, ?, ?, ?::jsonb, 'ACTIVE', 'e2e', ?, ?, ?, ?, ?, 'NONE')",
            ).use { ps ->
                ps.setObject(1, campaign.id)
                ps.setString(2, "e2e campaign")
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
                ps.setObject(9, campaign.offerId)
                ps.setString(10, campaign.offerName)
                ps.setInt(11, campaign.offerVersion)
                ps.executeUpdate()
            }
            c.prepareStatement(
                "INSERT INTO send_log (id, campaign_id, party_id, step_order, outcome, occurred_at, channel) " +
                    "VALUES (?, ?, ?, 0, 'SENT', ?, 'PUSH')",
            ).use { ps ->
                ps.setObject(1, send)
                ps.setObject(2, campaign.id)
                ps.setObject(3, UUID.randomUUID())
                ps.setObject(4, Timestamp.from(Instant.now()))
                ps.executeUpdate()
            }
        }
        return campaign to send
    }

    private fun <T> jdbc(block: (Connection) -> T): T = DriverManager.getConnection(
        ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String::class.java),
        ConfigProvider.getConfig().getValue("quarkus.datasource.username", String::class.java),
        ConfigProvider.getConfig().getValue("quarkus.datasource.password", String::class.java),
    ).use(block)

    private fun outcomeRows(event: UUID): Long = count(
        "SELECT count(*) FROM campaign_incentive_outcome WHERE event_id = ?",
        event,
    )

    private fun outcomeStatus(event: UUID): String = queryString(
        "SELECT status FROM campaign_incentive_outcome WHERE event_id = ?",
        event,
    )

    private fun outcomeCampaign(event: UUID): UUID = queryUuid(
        "SELECT campaign_id FROM campaign_incentive_outcome WHERE event_id = ?",
        event,
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

    private fun queryString(sql: String, vararg params: Any): String = jdbc { c ->
        c.prepareStatement(sql).use { ps ->
            params.forEachIndexed { i, p -> ps.setObject(i + 1, p) }
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getString(1)
            }
        }
    }

    private fun queryUuid(sql: String, vararg params: Any): UUID = jdbc { c ->
        c.prepareStatement(sql).use { ps ->
            params.forEachIndexed { i, p -> ps.setObject(i + 1, p) }
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getObject(1, UUID::class.java)
            }
        }
    }

    /**
     * Publishes valid committed evidence against a fresh seed; once its row exists, the consumer
     * provably moved past everything published before it.
     */
    private fun awaitSentinel() {
        val (campaign, send) = seedCampaignWithOffer()
        val event = UUID.randomUUID()
        publishCommitted(event, send, campaign.offerId, campaign.offerName, campaign.offerVersion)
        val deadline = Instant.now().plus(Duration.ofSeconds(20))
        while (outcomeRows(event) == 0L && Instant.now().isBefore(deadline)) Thread.sleep(100)
        assertThat(outcomeRows(event)).describedAs("sentinel committed event projected").isEqualTo(1)
    }

    companion object {
        private const val TOPIC = "openbank.incentive.events"
        private const val DLQ_TOPIC = "openbank.dlq.campaign.incentive-events-in"

        @BeforeAll
        @JvmStatic
        fun ensureTopics() {
            val bootstrap = ConfigProvider.getConfig().getValue("kafka.bootstrap.servers", String::class.java)
            Admin.create(mapOf("bootstrap.servers" to bootstrap)).use { admin ->
                val existing = admin.listTopics().names().get(30, TimeUnit.SECONDS)
                val wanted = listOf(TOPIC, DLQ_TOPIC).filter { it !in existing }
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
