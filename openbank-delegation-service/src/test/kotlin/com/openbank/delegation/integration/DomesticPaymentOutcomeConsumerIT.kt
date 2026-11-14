// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.integration

import com.openbank.delegation.infrastructure.messaging.DomesticPaymentOutcomeConsumer
import com.openbank.delegation.it.PostgresRedpandaTestResource
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.sql.DriverManager
import java.time.Duration
import java.util.Properties
import java.util.UUID

/**
 * Real Redpanda + Postgres proof of the ADR-0249 D3 amendment (#16000): a domestic-payment
 * reservation is settled by the payment's OUTCOME as the rail publishes it.
 *
 * Every assertion that something did NOT change is followed by a later event on the same
 * partition being applied, so "unchanged" can never be explained by the consumer being slow or
 * wedged — the stale event was consumed, acked, and had no effect.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedpandaTestResource::class)
@TestProfile(DomesticPaymentOutcomeConsumerIT.ConsumerOnProfile::class)
class DomesticPaymentOutcomeConsumerIT {

    class ConsumerOnProfile : QuarkusTestProfile {
        // Literals only: a profile loads in a different classloader from the test class.
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.delegation.payment-outcome-consumer.enabled" to "true",
            "openbank.delegation.spend-reservation-state-events-enabled" to "true",
            "openbank.outbox.dispatch-enabled" to "false",
            // A map source, not YAML: the dotted key reaches the connector. The test produces
            // right after boot, before the group may have its assignment, so read from the start.
            "mp.messaging.incoming.domestic-payment-events-in.auto.offset.reset" to "earliest",
        )
    }

    @Inject
    lateinit var meters: MeterRegistry

    private val grantee = UUID.fromString("0199a222-0000-7000-8000-000000000016")

    private fun jdbc() = DriverManager.getConnection(
        ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String::class.java),
        "openbank",
        "openbank_secret",
    )

    private fun seedGrant(): UUID {
        val id = UUID.randomUUID()
        jdbc().use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO delegation_grants
                    (id, grantor_party_id, grantee_party_id, resource_type, resource_id,
                     approval_policy, daily_limit_amount, daily_limit_currency, valid_from,
                     valid_to, status, created_at, updated_at)
                VALUES (?, ?, ?, 'ACCOUNT', ?, 'SOLO', 5000.00, 'CZK', NOW() - INTERVAL '1 day',
                        NOW() + INTERVAL '30 days', 'ACTIVE', NOW(), NOW())
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, id)
                statement.setObject(2, UUID.randomUUID())
                statement.setObject(3, grantee)
                statement.setObject(4, UUID.randomUUID())
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO delegation_capabilities (grant_id, capability) VALUES (?, 'ACCOUNT_INITIATE_PAYMENT')",
            ).use { statement ->
                statement.setObject(1, id)
                statement.executeUpdate()
            }
        }
        return id
    }

    private fun reserve(grantId: UUID): UUID = UUID.fromString(
        RestAssured.given()
            .contentType(ContentType.JSON)
            .header(CUSTOMER_PARTY_HEADER, grantee.toString())
            .body(
                """{"amount":125.50,"currency":"CZK","idempotencyKey":"${UUID.randomUUID()}",""" +
                    """"operationType":"DOMESTIC_PAYMENT"}""",
            )
            .post("/api/v1/delegations/$grantId/reservations")
            .then().statusCode(HTTP_CREATED)
            .extract().path("reservationId"),
    )

    /** The pre-#16000 edge behaviour: confirm on the rail's 2xx, before any outcome exists. */
    private fun edgeConfirm(grantId: UUID, reservationId: UUID) {
        RestAssured.given()
            .contentType(ContentType.JSON)
            .header(CUSTOMER_PARTY_HEADER, grantee.toString())
            .post("/api/v1/delegations/$grantId/reservations/$reservationId/confirm")
            .then().statusCode(HTTP_OK)
    }

    private fun state(reservationId: UUID): String? = jdbc().use { connection ->
        val statement = connection.prepareStatement("SELECT state FROM delegation_spend_reservations WHERE id = ?")
        statement.setObject(1, reservationId)
        val rs = statement.executeQuery()
        if (rs.next()) rs.getString(1) else null
    }

    private fun settlementAuditRows(reservationId: UUID): Int = jdbc().use { connection ->
        connection.prepareStatement(
            """
            SELECT COUNT(*) FROM delegation_outbox
             WHERE payload::jsonb ->> 'reservationId' = ?
               AND event_type IN ('SpendConfirmed', 'SpendReleased')
            """.trimIndent(),
        ).use {
            it.setString(1, reservationId.toString())
            it.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

    private fun outcomeCount(label: String): Double =
        meters.find(DomesticPaymentOutcomeConsumer.METRIC).tag("outcome", label).counter()?.count() ?: 0.0

    private fun producer() = KafkaProducer<String, String>(
        Properties().apply {
            put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, PostgresRedpandaTestResource.bootstrapServers)
            put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
            put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
        },
    )

    /** All on one key, so one partition, so the order sent is the order consumed. */
    private fun send(ceType: String?, body: String) {
        producer().use { producer ->
            val record = ProducerRecord<String, String>(TOPIC, ORDERING_KEY, body)
            if (ceType != null) record.headers().add("ce-type", ceType.toByteArray(StandardCharsets.UTF_8))
            producer.send(record).get()
        }
    }

    private fun statusChanged(
        grantId: UUID?,
        reservationId: UUID?,
        newStatus: String,
        bodyEventType: String = "DOMESTIC_PAYMENT_STATUS_CHANGED",
    ): String {
        val delegation = grantId?.let { "\"delegationId\":\"$it\"," } ?: ""
        val reservation = reservationId?.let { "\"reservationId\":\"$it\"," } ?: ""
        return """{"paymentId":"${UUID.randomUUID()}","previousStatus":"SENT_TO_CLEARING",""" +
            """"newStatus":"$newStatus",$delegation$reservation"eventType":"$bodyEventType",""" +
            """"sourceService":"domestic-payment","occurredAt":"2026-11-14T08:00:00Z"}"""
    }

    private fun finalizedAbsent(grantId: UUID, reservationId: UUID): String =
        """{"reservationId":"$reservationId","delegationId":"$grantId","reservationState":"RESERVED",""" +
            """"reservationVersion":1,"eventType":"DELEGATED_SPEND_FINALIZED_ABSENT",""" +
            """"sourceService":"domestic-payment","finalizedAt":"2026-11-14T08:30:00Z"}"""

    private fun awaitState(reservationId: UUID, expected: String) {
        val deadline = System.nanoTime() + AWAIT.toNanos()
        while (System.nanoTime() < deadline) {
            if (state(reservationId) == expected) return
            Thread.sleep(POLL_MS)
        }
        assertThat(state(reservationId)).isEqualTo(expected)
    }

    /**
     * A sentinel reservation settled AFTER the event under test, on the same partition: once it
     * is CONFIRMED, every earlier record has been consumed and acked.
     */
    private fun drain() {
        val grantId = seedGrant()
        val sentinel = reserve(grantId)
        send(STATUS_CHANGED, statusChanged(grantId, sentinel, "SETTLED"))
        awaitState(sentinel, "CONFIRMED")
    }

    @Test
    @TestSecurity(user = "svc", roles = ["ROLE_API"])
    fun `SETTLED confirms a reserved reservation and a redelivery changes nothing`() {
        val grantId = seedGrant()
        val reservationId = reserve(grantId)
        val event = statusChanged(grantId, reservationId, "SETTLED")
        send(STATUS_CHANGED, event)
        awaitState(reservationId, "CONFIRMED")

        val alreadySettled = outcomeCount("already_settled")
        send(STATUS_CHANGED, event)
        drain()
        assertThat(state(reservationId)).isEqualTo("CONFIRMED")
        assertThat(settlementAuditRows(reservationId)).isEqualTo(1)
        assertThat(outcomeCount("already_settled")).isGreaterThan(alreadySettled)
    }

    @Test
    @TestSecurity(user = "svc", roles = ["ROLE_API"])
    fun `a bound reservation whose payment is REJECTED ends RELEASED`() {
        // The #16111 baseline flipped for the bound path: nothing confirmed on the rail's 2xx.
        val grantId = seedGrant()
        val reservationId = reserve(grantId)
        send(STATUS_CHANGED, statusChanged(grantId, reservationId, "REJECTED"))
        awaitState(reservationId, "RELEASED")
        send(STATUS_CHANGED, statusChanged(grantId, reservationId, "REJECTED"))
        drain()
        assertThat(settlementAuditRows(reservationId)).isEqualTo(1)
    }

    @Test
    @TestSecurity(user = "svc", roles = ["ROLE_API"])
    fun `CANCELLED releases`() {
        val grantId = seedGrant()
        val reservationId = reserve(grantId)
        send(STATUS_CHANGED, statusChanged(grantId, reservationId, "CANCELLED"))
        awaitState(reservationId, "RELEASED")
    }

    @Test
    @TestSecurity(user = "svc", roles = ["ROLE_API"])
    fun `finalized-absent releases`() {
        val grantId = seedGrant()
        val reservationId = reserve(grantId)
        send(FINALIZED_ABSENT, finalizedAbsent(grantId, reservationId))
        awaitState(reservationId, "RELEASED")
    }

    @Test
    @TestSecurity(user = "svc", roles = ["ROLE_API"])
    fun `RETURNED releases a reserved reservation and an already confirmed one`() {
        val grantId = seedGrant()
        val reserved = reserve(grantId)
        val confirmed = reserve(grantId)
        edgeConfirm(grantId, confirmed)
        val returnedReleased = outcomeCount("returned_released")

        send(STATUS_CHANGED, statusChanged(grantId, reserved, "RETURNED"))
        send(STATUS_CHANGED, statusChanged(grantId, confirmed, "RETURNED"))
        awaitState(reserved, "RELEASED")
        awaitState(confirmed, "RELEASED")
        assertThat(outcomeCount("returned_released")).isEqualTo(returnedReleased + 1)
    }

    @Test
    @TestSecurity(user = "svc", roles = ["ROLE_API"])
    fun `REJECTED and CANCELLED never release a confirmed reservation - only RETURNED may`() {
        val grantId = seedGrant()
        val rejected = reserve(grantId)
        val cancelled = reserve(grantId)
        val absent = reserve(grantId)
        listOf(rejected, cancelled, absent).forEach { edgeConfirm(grantId, it) }
        val conflicts = outcomeCount("conflict")

        send(STATUS_CHANGED, statusChanged(grantId, rejected, "REJECTED"))
        send(STATUS_CHANGED, statusChanged(grantId, cancelled, "CANCELLED"))
        send(FINALIZED_ABSENT, finalizedAbsent(grantId, absent))
        drain()

        assertThat(listOf(rejected, cancelled, absent).map { state(it) }).containsOnly("CONFIRMED")
        assertThat(outcomeCount("conflict")).isEqualTo(conflicts + 3)
    }

    @Test
    @TestSecurity(user = "svc", roles = ["ROLE_API"])
    fun `out of order - REJECTED then a stale SETTLED stays RELEASED and is acked, not dead-lettered`() {
        val grantId = seedGrant()
        val reservationId = reserve(grantId)
        val conflicts = outcomeCount("conflict")
        send(STATUS_CHANGED, statusChanged(grantId, reservationId, "REJECTED"))
        send(STATUS_CHANGED, statusChanged(grantId, reservationId, "SETTLED"))
        drain()
        assertThat(state(reservationId)).isEqualTo("RELEASED")
        assertThat(outcomeCount("conflict")).isEqualTo(conflicts + 1)
        assertThat(dlqKeys()).doesNotContain(ORDERING_KEY)
    }

    @Test
    @TestSecurity(user = "svc", roles = ["ROLE_API"])
    fun `events without a reservation, for an unknown reservation, or non-terminal are acked untouched`() {
        val grantId = seedGrant()
        val reservationId = reserve(grantId)
        val ignored = outcomeCount("ignored")
        val unknown = outcomeCount("unknown_reservation")

        send(STATUS_CHANGED, statusChanged(null, null, "REJECTED"))
        send(STATUS_CHANGED, statusChanged(grantId, UUID.randomUUID(), "REJECTED"))
        send(STATUS_CHANGED, statusChanged(grantId, reservationId, "SENT_TO_CLEARING"))
        drain()

        assertThat(state(reservationId)).isEqualTo("RESERVED")
        assertThat(outcomeCount("ignored")).isGreaterThanOrEqualTo(ignored + 2)
        assertThat(outcomeCount("unknown_reservation")).isEqualTo(unknown + 1)
    }

    @Test
    @TestSecurity(user = "svc", roles = ["ROLE_API"])
    fun `dispatch is on the ce-type header, never on the body's eventType`() {
        val grantId = seedGrant()
        val reservationId = reserve(grantId)
        val unrecognised = outcomeCount("unrecognised")

        // A body claiming to be a status change, under headers that are not one.
        send("domestic.payment.created", statusChanged(grantId, reservationId, "REJECTED"))
        send("domestic.payment.something-new", statusChanged(grantId, reservationId, "REJECTED"))
        send(null, statusChanged(grantId, reservationId, "REJECTED"))
        drain()
        assertThat(state(reservationId)).isEqualTo("RESERVED")
        assertThat(outcomeCount("unrecognised")).isEqualTo(unrecognised + 2)

        // And the header alone suffices: a body with an unrelated eventType still settles.
        send(STATUS_CHANGED, statusChanged(grantId, reservationId, "REJECTED", bodyEventType = "SOMETHING_ELSE"))
        awaitState(reservationId, "RELEASED")
    }

    private fun dlqKeys(): List<String> = KafkaConsumer<String, String>(
        Properties().apply {
            put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, PostgresRedpandaTestResource.bootstrapServers)
            put(ConsumerConfig.GROUP_ID_CONFIG, "dlq-probe-${UUID.randomUUID()}")
            put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
            put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
            put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
        },
    ).use { consumer ->
        consumer.subscribe(listOf(DLQ))
        buildList {
            repeat(DLQ_POLLS) { consumer.poll(Duration.ofMillis(POLL_MS * 2)).forEach { add(it.key()) } }
        }
    }

    private companion object {
        const val TOPIC = "openbank.domestic.payment.events"
        const val DLQ = "openbank.dlq.delegation.domestic-payment-events-in"
        const val STATUS_CHANGED = "domestic.payment.status-changed"
        const val FINALIZED_ABSENT = "domestic.delegated-spend.finalized-absent"
        const val ORDERING_KEY = "delegation-outcome-it"
        const val CUSTOMER_PARTY_HEADER = "X-Customer-Party-Id"
        const val HTTP_OK = 200
        const val HTTP_CREATED = 201
        const val POLL_MS = 250L
        const val DLQ_POLLS = 8
        val AWAIT: Duration = Duration.ofSeconds(60)
    }
}
