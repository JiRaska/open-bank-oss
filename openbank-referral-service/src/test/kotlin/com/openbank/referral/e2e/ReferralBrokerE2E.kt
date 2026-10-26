// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.referral.e2e

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * Real-broker E2E for the `account-created-in` consumer (ADR-0310 D1). The pre-existing
 * ReferralQualificationIT drives the channel through the InMemoryConnector, so the real
 * deserializer, consumer group, offset commits and the connector's nack handling were never
 * exercised. The suite publishes raw AccountCreated records to the real topic, drives
 * attribution through the production REST routes, reads every effect back over plain JDBC —
 * and, because the outbox dispatcher runs against the real broker here, proves the Qualified
 * event lands on `openbank.referral.qualified.v1` as a genuine wire fact.
 *
 * Negative assertions wait on a sentinel published AFTER the messages under test: "not
 * recorded" is observed only after the consumer provably moved past the message.
 */
@QuarkusTest
@TestProfile(ReferralBrokerE2EProfile::class)
@TestSecurity(user = "checker@openbank.test", roles = ["ROLE_OPERATOR"])
class ReferralBrokerE2E {

    @Inject
    lateinit var dataSource: DataSource

    private val programs = mutableListOf<UUID>()

    // ── scenarios ────────────────────────────────────────────────────────────────

    @Test
    fun `an account opened during onboarding qualifies when the referee redeems the code later`() {
        val programId = publishedProgram()
        val referee = UUID.randomUUID()
        val token = issueInvite(programId, UUID.randomUUID())
        val eventId = UUID.randomUUID().toString()

        publishAccountCreated(eventId, referee, Instant.now())
        awaitCount("select count(*) from referral_qualifying_fact where event_id = ?", eventId, expected = 1)
        assertThat(rewardsForReferee(referee)).describedAs("nothing to qualify before attribution").isZero()

        attribute(token, referee)

        assertThat(
            count(
                "select count(*) from referral_reward where referee_party_id = ? and qualification_event_id = ?",
                referee,
                eventId,
            ),
        )
            .describedAs("attribution must qualify on the fact recorded over the real wire")
            .isEqualTo(1)
    }

    @Test
    fun `a redelivered AccountCreated does not duplicate the fact or the reward`() {
        val programId = publishedProgram()
        val referee = UUID.randomUUID()
        val token = issueInvite(programId, UUID.randomUUID())
        val eventId = UUID.randomUUID().toString()
        attribute(token, referee)

        publishAccountCreated(eventId, referee, Instant.now())
        publishAccountCreated(eventId, referee, Instant.now())
        // A sentinel on the same partition proves both deliveries have been consumed.
        val sentinel = UUID.randomUUID().toString()
        publishAccountCreated(sentinel, UUID.randomUUID(), Instant.now())
        awaitCount("select count(*) from referral_qualifying_fact where event_id = ?", sentinel, expected = 1)

        assertThat(count("select count(*) from referral_qualifying_fact where event_id = ?", eventId))
            .describedAs("event-id idempotence holds across broker redelivery").isEqualTo(1)
        assertThat(rewardsForReferee(referee))
            .describedAs("one reward, not one per delivery").isEqualTo(1)
    }

    @Test
    fun `an unrelated eventType on the shared topic is ignored`() {
        val other = UUID.randomUUID().toString()
        publishRaw(
            """{"eventId":"$other","aggregateId":"${UUID.randomUUID()}","aggregateType":"Account",""" +
                """"eventType":"AccountClosed","version":0,"partyId":"${UUID.randomUUID()}",""" +
                """"occurredAt":"${Instant.now()}","sourceService":"account-service"}""",
        )
        val sentinel = UUID.randomUUID().toString()
        publishAccountCreated(sentinel, UUID.randomUUID(), Instant.now())
        awaitCount("select count(*) from referral_qualifying_fact where event_id = ?", sentinel, expected = 1)

        assertThat(count("select count(*) from referral_qualifying_fact where event_id = ?", other))
            .describedAs("non-qualifying eventType must never reach the fact table").isZero()
    }

    @Test
    fun `poison payloads are skipped without wedging the channel`() {
        val marker = UUID.randomUUID().toString()
        publishRaw("this is not json at all")
        publishRaw(
            """{"eventType":"AccountCreated","partyId":"${UUID.randomUUID()}","occurredAt":"${Instant.now()}"}""",
        )

        publishAccountCreated(marker, UUID.randomUUID(), Instant.now())
        awaitCount("select count(*) from referral_qualifying_fact where event_id = ?", marker, expected = 1)
    }

    @Test
    fun `a qualified referral reaches the real broker on referral-qualified-out`() {
        val programId = publishedProgram()
        val referrer = UUID.randomUUID()
        val referee = UUID.randomUUID()
        val token = issueInvite(programId, referrer)
        val eventId = UUID.randomUUID().toString()

        publishAccountCreated(eventId, referee, Instant.now())
        awaitCount("select count(*) from referral_qualifying_fact where event_id = ?", eventId, expected = 1)
        attribute(token, referee)
        assertThat(rewardsForReferee(referee)).isEqualTo(1)

        assertThat(topicContains(QUALIFIED_TOPIC, eventId))
            .describedAs("outbox-dispatched Qualified event present on the real topic")
            .isTrue()
    }

    // ── Kafka producer / consumer ────────────────────────────────────────────────

    private fun publishAccountCreated(eventId: String, partyId: UUID, occurredAt: Instant) = publishRaw(
        """{"eventId":"$eventId","aggregateId":"${UUID.randomUUID()}","aggregateType":"Account",""" +
            """"eventType":"AccountCreated","version":0,"accountNumber":"123","accountType":"CURRENT",""" +
            """"partyId":"$partyId","productId":"${UUID.randomUUID()}","currency":"CZK",""" +
            """"occurredAt":"$occurredAt","sourceService":"account-service"}""",
    )

    private fun publishRaw(payload: String) {
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

    private fun topicContains(topic: String, marker: String): Boolean {
        val props = mapOf(
            "bootstrap.servers" to
                ConfigProvider.getConfig().getValue("kafka.bootstrap.servers", String::class.java),
            "group.id" to "referral-e2e-$topic-${UUID.randomUUID()}",
            "key.deserializer" to StringDeserializer::class.qualifiedName,
            "value.deserializer" to StringDeserializer::class.qualifiedName,
            "auto.offset.reset" to "earliest",
            "enable.auto.commit" to "false",
        )
        KafkaConsumer<String, String>(props).use { consumer ->
            consumer.subscribe(listOf(topic))
            return pollForRecord(consumer, marker, Instant.now().plus(Duration.ofSeconds(20)))
        }
    }

    private tailrec fun pollForRecord(
        consumer: KafkaConsumer<String, String>,
        marker: String,
        deadline: Instant,
    ): Boolean {
        if (!Instant.now().isBefore(deadline)) return false
        val records: ConsumerRecords<String, String> = consumer.poll(Duration.ofMillis(500))
        if (records.any { it.value().contains(marker) }) return true
        return pollForRecord(consumer, marker, deadline)
    }

    // ── JDBC seeds and read-back ─────────────────────────────────────────────────

    private fun publishedProgram(): UUID {
        val id = UUID.randomUUID()
        execute(
            """insert into referral_program
                (id,name,version,reward_amount,currency,qualifying_event,attribution_window_ends_at,status,maker,created_at)
                values (?,?,?,?,?,?,?,?,?,?)""",
            id,
            "broker-e2e-$id",
            1,
            BigDecimal.TEN,
            "CZK",
            "account.opened",
            Timestamp.from(Instant.now().plus(Duration.ofDays(1))),
            "DRAFT",
            "maker@openbank.test",
            Timestamp.from(Instant.now()),
        )
        programs += id
        Given { contentType("application/json") }
            .When { post("/api/v1/referrals/programs/$id/publish") }
            .Then { statusCode(200) }
        return id
    }

    private fun issueInvite(programId: UUID, referrer: UUID): String = Given {
        contentType("application/json")
        header("Idempotency-Key", "invite-${UUID.randomUUID()}")
        body("""{"referrerPartyId":"$referrer"}""")
    } When { post("/api/v1/referrals/programs/$programId/invites") } Then {
        statusCode(201)
    } Extract { path("token") }

    private fun attribute(token: String, referee: UUID) {
        Given {
            contentType("application/json")
            header("Idempotency-Key", "attribute-${UUID.randomUUID()}")
            body("""{"refereePartyId":"$referee"}""")
        } When { post("/api/v1/referrals/invites/$token/attribute") } Then { statusCode(200) }
    }

    private fun rewardsForReferee(referee: UUID): Long =
        count("select count(*) from referral_reward where referee_party_id = ?", referee)

    private fun awaitCount(sql: String, param: Any, expected: Long) {
        val deadline = Instant.now().plus(AWAIT)
        var last = count(sql, param)
        while (last != expected && Instant.now().isBefore(deadline)) {
            Thread.sleep(POLL_MS)
            last = count(sql, param)
        }
        assertThat(last).describedAs("awaited `%s` for %s", sql, param).isEqualTo(expected)
    }

    private fun count(sql: String, vararg params: Any): Long = dataSource.connection.use { c ->
        c.prepareStatement(sql).use { ps ->
            params.forEachIndexed { i, p -> ps.setObject(i + 1, p) }
            val rs = ps.executeQuery()
            rs.next()
            rs.getLong(1)
        }
    }

    private fun execute(sql: String, vararg params: Any) {
        dataSource.connection.use { c ->
            c.prepareStatement(sql).use { ps ->
                params.forEachIndexed { i, p -> ps.setObject(i + 1, p) }
                ps.executeUpdate()
            }
            if (!c.autoCommit) c.commit()
        }
    }

    @AfterEach
    fun retirePrograms() {
        programs.forEach { execute("update referral_program set status = 'EXPIRED' where id = ?", it) }
        programs.clear()
    }

    companion object {
        private const val TOPIC = "openbank.accounts.account.created"
        private const val QUALIFIED_TOPIC = "openbank.referral.qualified.v1"

        private val AWAIT: Duration = Duration.ofSeconds(15)
        private const val POLL_MS = 100L

        @BeforeAll
        @JvmStatic
        fun ensureTopics() {
            val bootstrap = ConfigProvider.getConfig().getValue("kafka.bootstrap.servers", String::class.java)
            Admin.create(mapOf("bootstrap.servers" to bootstrap)).use { admin ->
                val existing = admin.listTopics().names().get(30, TimeUnit.SECONDS)
                val wanted = listOf(TOPIC).filter { it !in existing }
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
