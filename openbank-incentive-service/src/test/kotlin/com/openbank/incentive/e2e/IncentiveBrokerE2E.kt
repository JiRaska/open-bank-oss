// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.incentive.e2e

import com.openbank.incentive.integration.TestJsonWebToken
import io.quarkus.security.runtime.QuarkusSecurityIdentity
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestIdentityAssociation
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.quarkus.test.security.oidc.OidcTestSecurityIdentityAugmentor
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * Real-broker E2E for the reservation-lifecycle outbox relay. The %test block disables the
 * outbox dispatcher and the other suites swap `incentive-events-out` to the in-memory
 * connector, so `openbank.incentive.events` had never carried a real record under test. The
 * suite drives the lifecycle through the production REST routes and asserts the reservation
 * row, the `incentive_outbox` row and the Kafka record as independent facts that must agree —
 * with the ADR-0050 addressing contract pinned on the real records.
 */
@QuarkusTest
@TestProfile(IncentiveBrokerE2EProfile::class)
@TestSecurity(user = "maker@openbank.test", roles = ["ROLE_OPERATOR"])
class IncentiveBrokerE2E {

    @Inject
    lateinit var dataSource: DataSource

    @Inject
    lateinit var testIdentity: TestIdentityAssociation

    // ── scenarios ────────────────────────────────────────────────────────────────

    @Test
    fun `a reservation lifecycle reaches the broker with one record per transition`() {
        val (offerId, code1, code2) = publishedOffer()
        val party = UUID.randomUUID()
        // A committed reservation can never be released (the domain refuses), so the two
        // transitions are proven on two reservations of the same published offer.
        val commitId = customerReserve(offerId, code1, party)
        val releaseId = customerReserve(offerId, code2, party)

        awaitTopicRecords(commitId, setOf(EVENT_CREATED))
        awaitTopicRecords(releaseId, setOf(EVENT_CREATED))
        commitReservation(commitId, party)
        val committedRecords = awaitTopicRecords(commitId, setOf(EVENT_COMMITTED))
        releaseReservation(releaseId, party)
        val releasedRecords = awaitTopicRecords(releaseId, setOf(EVENT_RELEASED))

        assertThat(committedRecords.count { it.eventType() == EVENT_CREATED }).isEqualTo(1)
        val committed = committedRecords.single { it.eventType() == EVENT_COMMITTED }
        val released = releasedRecords.single { it.eventType() == EVENT_RELEASED }
        assertThat(releasedRecords.count { it.eventType() == EVENT_CREATED }).isEqualTo(1)
        listOf(committed, released).forEach { record ->
            assertThat(record.value()).contains("\"reservationId\":\"")
            assertThat(record.header("ce-id")).isNotBlank()
            assertThat(record.header("ce-type")).isEqualTo(record.eventType())
        }
        assertThat(count("select count(*) from incentive_outbox where aggregate_id = '$commitId'"))
            .describedAs("outbox rows for the committed reservation").isEqualTo(2)
        assertThat(count("select count(*) from incentive_outbox where aggregate_id = '$releaseId'"))
            .describedAs("outbox rows for the released reservation").isEqualTo(2)
    }

    @Test
    fun `an idempotent replay emits no second event`() {
        val (offerId, code1, code2) = publishedOffer()
        val party = UUID.randomUUID()
        val idempotencyKey = "replay-${UUID.randomUUID()}"
        val attributionRef = UUID.randomUUID()

        val first = customerReserve(offerId, code1, party, idempotencyKey, attributionRef)
        val replayed = customerReserve(offerId, code1, party, idempotencyKey, attributionRef)
        assertThat(replayed).isEqualTo(first)

        val records = awaitTopicRecords(first, setOf(EVENT_CREATED), window = Duration.ofSeconds(10))
        assertThat(records).describedAs("exactly one created record despite the replay").hasSize(1)
        assertThat(count("select count(*) from incentive_outbox where aggregate_id = '$first'")).isEqualTo(1)
    }

    @Test
    fun `the row, the outbox payload and the broker record agree on the addressing contract`() {
        val (offerId, code1, code2) = publishedOffer()
        val party = UUID.randomUUID()
        val attributionRef = UUID.randomUUID()
        val reservationId = customerReserve(offerId, code1, party, attributionRef = attributionRef)

        val records = awaitTopicRecords(reservationId, setOf(EVENT_CREATED))
        val record = records.single()

        // ADR-0050: ce-id == idempotency-key, ce-type names the event, aggregate id is the key.
        assertThat(record.header("ce-id")).isNotBlank()
        assertThat(record.key()).isEqualTo(reservationId)
        assertThat(record.header("ce-type")).isEqualTo(EVENT_CREATED)
        assertThat(record.value()).contains("\"offerRef\":{\"id\":\"$offerId\"")

        assertThat(
            count(
                """select count(*) from incentive_outbox where aggregate_id = '$reservationId'
                    and event_type = '$EVENT_CREATED'
                    and payload::jsonb ->> 'attributionRef' = '$attributionRef'
                    and payload::jsonb -> 'offerRef' ->> 'id' = '$offerId'
                    and not (payload::jsonb ?| array['partyRef','code','codeDigest'])
                """.trimIndent(),
            ),
        ).isEqualTo(1)
        assertThat(string("select party_ref from promo_reservation where id = '$reservationId'"))
            .isEqualTo(party.toString())
    }

    // ── REST lifecycle helpers (production routes) ───────────────────────────────

    private fun publishedOffer(): Triple<String, String, String> {
        useStaffIdentity("maker@openbank.test")
        val effectiveFrom = Instant.now().minusSeconds(60)
        val expiresAt = Instant.now().plusSeconds(86_400)
        val code1 = "BROKER-${UUID.randomUUID().toString().take(6).uppercase()}-1"
        val code2 = "BROKER-${UUID.randomUUID().toString().take(6).uppercase()}-2"
        val offerId = Given {
            contentType("application/json")
            body(
                """{
                    "name":"broker-e2e-${UUID.randomUUID().toString().take(8)}",
                    "version":1,
                    "productScope":["current-account"],
                    "effectiveFrom":"$effectiveFrom",
                    "expiresAt":"$expiresAt",
                    "totalLimit":10,
                    "perPartyLimit":2,
                    "stackingPolicy":"EXCLUSIVE"
                }
                """.trimIndent(),
            )
        } When { post("/api/v1/incentives/offers") } Then {
            statusCode(201)
        } Extract { path<String>("ref.id") }

        Given {
            contentType("application/json")
            body("""{"codes":["$code1","$code2"]}""")
        } When { post("/api/v1/incentives/offers/$offerId/codes") } Then { statusCode(201) }
        Given { contentType("application/json") }
            .When { post("/api/v1/incentives/offers/$offerId/submit") }
            .Then { statusCode(200) }
        useStaffIdentity("checker@openbank.test")
        Given { contentType("application/json") }
            .When { post("/api/v1/incentives/offers/$offerId/publish") }
            .Then { statusCode(200) }
        return Triple(offerId, code1, code2)
    }

    private fun customerReserve(
        offerId: String,
        code: String,
        party: UUID,
        idempotencyKey: String = "customer-${UUID.randomUUID()}",
        attributionRef: UUID = UUID.randomUUID(),
    ): String {
        useEdgeIdentity()
        return Given {
            contentType("application/json")
            header("X-Customer-Party-Id", party.toString())
            header("Idempotency-Key", idempotencyKey)
            body(
                """{"code":"$code","productRef":"current-account","attributionRef":"$attributionRef"}""",
            )
        } When { post("/api/v1/customer-incentives/offers/$offerId/reservations") } Then {
            statusCode(201)
        } Extract { path<String>("id") }
    }

    private fun commitReservation(reservationId: String, party: UUID) {
        useEdgeIdentity()
        Given {
            contentType("application/json")
            header("X-Customer-Party-Id", party.toString())
            body("""{"productRef":"current-account","qualifiedAt":"${Instant.now()}"}""")
        } When { post("/api/v1/customer-incentives/reservations/$reservationId/commit") } Then {
            statusCode(200)
        }
    }

    private fun releaseReservation(reservationId: String, party: UUID) {
        useEdgeIdentity()
        Given {
            contentType("application/json")
            header("X-Customer-Party-Id", party.toString())
            body("""{"productRef":"current-account"}""")
        } When { post("/api/v1/customer-incentives/reservations/$reservationId/release") } Then {
            statusCode(200)
        }
    }

    // ── identity switching (the customer-edge caller guard keys off azp + principal) ──

    private fun useStaffIdentity(username: String) = useIdentity("openbank-admin-ui", username, "ROLE_OPERATOR")

    private fun useEdgeIdentity() = useIdentity("openbank-edge", "service-account-openbank-edge", "ROLE_API")

    private fun useIdentity(clientId: String, username: String, role: String) {
        val base = QuarkusSecurityIdentity.builder().setPrincipal { username }.addRole(role).build()
        val annotation = OidcSecurity(
            claims = arrayOf(
                Claim(key = "azp", value = clientId),
                Claim(key = "sub", value = username),
                Claim(key = "preferred_username", value = username),
            ),
        )
        val identity = OidcTestSecurityIdentityAugmentor(Optional.empty()).augment(base, arrayOf(annotation))
        TestJsonWebToken.actor = username
        testIdentity.setTestIdentity(identity)
    }

    // ── Kafka consumer ───────────────────────────────────────────────────────────

    /** Reads the topic until a record for [reservationId] of every type in [types] appears. */
    private fun awaitTopicRecords(
        reservationId: String,
        types: Set<String>,
        window: Duration = DISPATCH_WINDOW,
    ): List<ConsumerRecord<String, String>> {
        val props = mapOf(
            "bootstrap.servers" to
                ConfigProvider.getConfig().getValue("kafka.bootstrap.servers", String::class.java),
            "group.id" to "incentive-e2e-${UUID.randomUUID()}",
            "key.deserializer" to StringDeserializer::class.qualifiedName,
            "value.deserializer" to StringDeserializer::class.qualifiedName,
            "auto.offset.reset" to "earliest",
            "enable.auto.commit" to "false",
        )
        KafkaConsumer<String, String>(props).use { consumer ->
            consumer.subscribe(listOf(TOPIC))
            return pollForRecords(consumer, reservationId, types, Instant.now().plus(window), emptyList())
        }
    }

    private tailrec fun pollForRecords(
        consumer: KafkaConsumer<String, String>,
        reservationId: String,
        types: Set<String>,
        deadline: Instant,
        acc: List<ConsumerRecord<String, String>>,
    ): List<ConsumerRecord<String, String>> {
        val matched = acc.filter { it.value().contains("\"reservationId\":\"$reservationId\"") }
        if (matched.map { it.eventType() }.toSet().containsAll(types)) return matched
        if (!Instant.now().isBefore(deadline)) {
            assertThat(matched.map { it.eventType() }.toSet())
                .describedAs("records for $reservationId on $TOPIC")
                .containsAll(types)
            return matched
        }
        val records: ConsumerRecords<String, String> = consumer.poll(Duration.ofMillis(500))
        return pollForRecords(consumer, reservationId, types, deadline, acc + records)
    }

    private fun ConsumerRecord<String, String>.eventType(): String =
        requireNotNull(header("ce-type")) { "ce-type header must be present on every outbox record" }

    private fun ConsumerRecord<String, String>.header(name: String): String? =
        headers().lastHeader(name)?.let { String(it.value()) }

    // ── JDBC read-back ───────────────────────────────────────────────────────────

    // createStatement, not prepareStatement: the contract SQL uses the Postgres `?|` jsonb
    // operator, whose `?` the JDBC driver would parse as a bind parameter (the other incentive
    // ITs use the same statement-based helpers for exactly this reason).
    private fun count(sql: String): Long = dataSource.connection.use { c ->
        c.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }
    }

    private fun string(sql: String): String = dataSource.connection.use { c ->
        c.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                rs.next()
                rs.getString(1)
            }
        }
    }

    companion object {
        private const val TOPIC = "openbank.incentive.events"
        private const val EVENT_CREATED = "incentive.reservation.created.v2"
        private const val EVENT_COMMITTED = "incentive.reservation.committed.v2"
        private const val EVENT_RELEASED = "incentive.reservation.released.v2"

        // outbox poll-interval is 5s; allow several poll rounds before giving up
        private val DISPATCH_WINDOW: Duration = Duration.ofSeconds(25)

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
