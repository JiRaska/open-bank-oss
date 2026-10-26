// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.loyalty.e2e

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
 * Real-broker E2E for the `referral-qualified-in` consumer (ADR-0310 D3). The pre-existing IT
 * proves the consumer's decisions over the InMemoryConnector; this suite proves the same
 * decisions arrive over the production wire — a raw KafkaProducer on the real topic, the real
 * StringDeserializer, the real consumer group with its offset commits, and the configured
 * dead-letter strategy — with the ledger read back over JDBC. The invite-keyed idempotency,
 * the cap refusal and the poison-pill skips must hold exactly as they do in memory.
 *
 * Every negative assertion waits on a sentinel published AFTER the messages under test: "nothing
 * happened" is observed only after the consumer provably moved past the message, not before it
 * ran — the same discipline as the in-memory IT, now over a real partition.
 */
@QuarkusTest
@TestProfile(LoyaltyBrokerE2EProfile::class)
class LoyaltyBrokerE2E {

    // ── scenarios ────────────────────────────────────────────────────────────────

    @Test
    fun `a qualified referral earns the referrer once per invite over the real broker`() {
        val referrer = UUID.randomUUID()
        val referee = UUID.randomUUID()
        val invite = UUID.randomUUID()

        publishQualified(UUID.randomUUID(), invite, referrer, referee)
        publishQualified(UUID.randomUUID(), invite, referrer, referee) // broker redelivery
        publishQualified(UUID.randomUUID(), invite, referrer, referee) // re-emission, new event id
        awaitSentinel()

        assertThat(referralEarns(referrer)).describedAs("one lot per invite").isEqualTo(1)
        assertThat(
            count(
                "SELECT count(*) FROM leaf_ledger_entry " +
                    "WHERE party_id = ? AND correlation_event_id = ? AND leaves = ?",
                referrer,
                invite,
                LOT_LEAVES,
            ),
        ).isEqualTo(1)
        assertThat(referralEarns(referee)).describedAs("the referee earns nothing").isZero()
    }

    @Test
    fun `two different invites earn the same referrer twice`() {
        val referrer = UUID.randomUUID()
        publishQualified(UUID.randomUUID(), UUID.randomUUID(), referrer, UUID.randomUUID())
        publishQualified(UUID.randomUUID(), UUID.randomUUID(), referrer, UUID.randomUUID())
        awaitSentinel()

        assertThat(referralEarns(referrer)).isEqualTo(2)
    }

    @Test
    fun `a capped referrer is refused without wedging the channel`() {
        val referrer = UUID.randomUUID()
        seedEarn(referrer, 4_900)

        publishQualified(UUID.randomUUID(), UUID.randomUUID(), referrer, UUID.randomUUID())
        awaitSentinel()

        assertThat(referralEarns(referrer)).describedAs("a lot over the annual cap is refused whole").isZero()
    }

    @Test
    fun `poison payloads are skipped and the channel keeps flowing`() {
        publish("not json at all")
        publish("""{"eventType":"Qualified","inviteId":"${UUID.randomUUID()}"}""") // no referrerPartyId
        publish("""{"eventType":"SomethingElse","inviteId":"${UUID.randomUUID()}"}""") // wrong type

        awaitSentinel() // the sentinel itself proves the consumer moved past every poison above
    }

    // ── Kafka producer ───────────────────────────────────────────────────────────

    /** Publishes one qualified-referral envelope keyed by the invite, like the production producer. */
    private fun publishQualified(eventId: UUID, invite: UUID, referrer: UUID, referee: UUID) {
        publish(qualifiedPayloadJson(eventId, invite, referrer, referee), invite)
    }

    private fun publish(payload: String, inviteId: UUID? = null) {
        producer().use { p ->
            val record = ProducerRecord<String, String>(TOPIC, inviteId?.toString(), payload)
            p.send(record).get(10, TimeUnit.SECONDS)
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

    private fun qualifiedPayloadJson(eventId: UUID, invite: UUID, referrer: UUID, referee: UUID): String =
        """{"eventId":"$eventId","occurredAt":"${Instant.now()}","programId":"${UUID.randomUUID()}",""" +
            """"inviteId":"$invite","referrerPartyId":"$referrer","refereePartyId":"$referee",""" +
            """"qualificationEventId":"${UUID.randomUUID()}","eventType":"Qualified"}"""

    // ── JDBC read-back ───────────────────────────────────────────────────────────

    private fun <T> jdbc(block: (Connection) -> T): T = DriverManager.getConnection(
        ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String::class.java),
        ConfigProvider.getConfig().getValue("quarkus.datasource.username", String::class.java),
        ConfigProvider.getConfig().getValue("quarkus.datasource.password", String::class.java),
    ).use(block)

    private fun referralEarns(party: UUID): Long = count(
        "SELECT count(*) FROM leaf_ledger_entry WHERE party_id = ? AND earn_source_id = 'QUALIFIED_REFERRAL'",
        party,
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

    private fun seedEarn(party: UUID, leaves: Int) {
        jdbc { c ->
            c.prepareStatement(
                "INSERT INTO leaf_ledger_entry (id, party_id, entry_type, leaves, remaining_leaves, earn_source_id, " +
                    "rule_version, correlation_event_id, occurred_at, expires_at) " +
                    "VALUES (?, ?, 'EARN', ?, ?, 'SAVINGS_GOAL_REACHED', 'v1', ?, ?, ?)",
            ).use { ps ->
                ps.setObject(1, UUID.randomUUID())
                ps.setObject(2, party)
                ps.setInt(3, leaves)
                ps.setInt(4, leaves)
                ps.setObject(5, UUID.randomUUID())
                ps.setObject(6, java.sql.Timestamp.from(Instant.now()))
                ps.setObject(7, java.sql.Timestamp.from(Instant.now().plus(Duration.ofDays(700))))
                ps.executeUpdate()
            }
            if (!c.autoCommit) c.commit()
        }
    }

    /** Publishes a sentinel for a fresh party; once its lot exists, everything before it was consumed. */
    private fun awaitSentinel() {
        val sentinel = UUID.randomUUID()
        val invite = UUID.randomUUID()
        publish(qualifiedPayloadJson(UUID.randomUUID(), invite, sentinel, UUID.randomUUID()), invite)
        val deadline = Instant.now().plus(Duration.ofSeconds(20))
        while (referralEarns(sentinel) == 0L && Instant.now().isBefore(deadline)) Thread.sleep(100)
        assertThat(referralEarns(sentinel)).describedAs("sentinel consumed").isEqualTo(1)
    }

    companion object {
        private const val TOPIC = "openbank.referral.qualified.v1"
        private const val DLQ_TOPIC = "openbank.dlq.loyalty.referral-qualified-in"
        private const val LOT_LEAVES = 250

        @BeforeAll
        @JvmStatic
        fun ensureTopics() {
            val bootstrap = ConfigProvider.getConfig().getValue("kafka.bootstrap.servers", String::class.java)
            Admin.create(mapOf("bootstrap.servers" to bootstrap)).use { admin ->
                val existing = admin.listTopics().names().get(30, TimeUnit.SECONDS)
                val wanted = listOf(TOPIC, DLQ_TOPIC).filter { it !in existing }
                if (wanted.isNotEmpty()) {
                    admin.createTopics(wanted.map { NewTopic(it, 1, 1.toShort()) }).all()
                        .get(30, TimeUnit.SECONDS)
                }
            }
        }
    }
}
