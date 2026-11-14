// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sepainstant.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import com.openbank.libs.synthetic.SyntheticTaint
import com.openbank.libs.testing.containers.PostgresRedpandaRedisTestResource
import com.openbank.sepainstant.application.port.out.SctInstPaymentRepository
import com.openbank.sepainstant.domain.event.SctInstPaymentSettled
import com.openbank.sepainstant.domain.model.SctInstStatus
import com.openbank.sepainstant.infrastructure.outbox.SctInstOutboxDispatcher
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.quarkus.vertx.VertxContextSupport
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/** Real HTTP, PostgreSQL and Redpanda proof of the durable SCT Inst producer boundary. */
@QuarkusTest
@TestProfile(SctInstRealKafkaOutboxIT.RealBrokerProfile::class)
class SctInstRealKafkaOutboxIT {
    @Inject lateinit var dataSource: DataSource

    @Inject lateinit var repository: SctInstPaymentRepository

    @Inject lateinit var dispatcher: SctInstOutboxDispatcher

    @Inject lateinit var objectMapper: ObjectMapper

    class RealBrokerProfile : QuarkusTestProfile {
        // SctInstBootSmokeIT declares a global in-memory Kafka resource. Exclude it here:
        // a SENT outbox row through that connector is not a broker-delivery proof.
        override fun disableGlobalTestResources() = true

        override fun testResources() = listOf(
            QuarkusTestProfile.TestResourceEntry(
                PostgresRedpandaRedisTestResource::class.java,
                mapOf("db" to "openbank_sepa_instant_real_kafka_it"),
            ),
        )

        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.sct-inst.scheme-submission.enabled" to "false",
            "openbank.outbox.dispatch-enabled" to "true",
            "openbank.synthetic.trusted-principals" to SYNTHETIC_ACTOR,
            // The test drives the real dispatcher; the scheduler must not claim first.
            "openbank.outbox.initial-delay" to "1h",
        )
    }

    private data class OutboxRow(
        val eventId: UUID,
        val eventType: String,
        val status: String,
        val payload: String,
        val synthetic: Boolean,
    )

    @Suppress("LongMethod")
    @Test
    @TestSecurity(user = "operator-real-kafka", roles = ["ROLE_OPERATOR"])
    fun `committed payment events reach Redpanda in order and redelivery keeps the same event identity`() {
        assertThat(
            ConfigProvider.getConfig().getValue(
                "mp.messaging.outgoing.sct-inst-events-out.connector",
                String::class.java,
            ),
        ).isEqualTo("smallrye-kafka")
        val paymentId = submitClaimingSyntheticSctInstPayment()
        assertThat(paymentOrigin(paymentId)).isFalse()
        markLegacyOriginUnknown(paymentId)
        settle(paymentId, suppliedSynthetic = true)
        assertThat(paymentOrigin(paymentId)).isNull()

        val pending = rows(paymentId)
        assertThat(pending.map { it.eventType }).containsExactly("SctInstPaymentSubmitted", "SctInstPaymentSettled")
        assertThat(pending.map { it.status }).containsOnly("PENDING")
        assertThat(pending.map { it.synthetic }).containsOnly(false)

        consumer().use { consumer ->
            consumer.subscribe(listOf(TOPIC))
            tick()
            val delivered = awaitRecords(consumer, paymentId, 2)
            assertThat(delivered.map { objectMapper.readTree(it.value()).path("type").asText() })
                .containsExactly("SctInstPaymentSubmitted", "SctInstPaymentSettled")
            assertThat(delivered.map { it.key() }).containsOnly(paymentId.toString())
            assertThat(delivered.map { it.partition() }.distinct()).hasSize(1)
            assertThat(delivered[1].offset()).isGreaterThan(delivered[0].offset())
            delivered.zip(pending).forEach { (record, row) ->
                assertThat(record.value()).isEqualTo(row.payload)
                assertThat(record.headers().lastHeader(OutboxKafkaHeaders.HEADER_SYNTHETIC)).isNull()
                assertThat(record.headers().lastHeader("ce-id").value().toString(Charsets.UTF_8))
                    .isEqualTo(row.eventId.toString())
            }
            awaitSent(paymentId)

            // Model a crash after broker acknowledgement but before markSent by restoring
            // the original row's stale claim. This is real broker redelivery, not a broker outage.
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "UPDATE sct_inst_outbox SET status = 'DISPATCHING', " +
                        "claimed_at = now() - interval '1 hour' WHERE event_id = ?",
                ).use { statement ->
                    statement.setObject(1, pending[0].eventId)
                    assertThat(statement.executeUpdate()).isEqualTo(1)
                }
            }
            tick()
            val replay = awaitRecords(consumer, paymentId, 1).single()
            assertThat(replay.value()).isEqualTo(delivered[0].value())
            assertThat(replay.headers().lastHeader(OutboxKafkaHeaders.HEADER_SYNTHETIC)).isNull()
            assertThat(replay.key()).isEqualTo(delivered[0].key())
            assertThat(replay.headers().lastHeader("ce-id").value().toString(Charsets.UTF_8))
                .isEqualTo(pending[0].eventId.toString())
            awaitSent(paymentId)
        }
    }

    @Test
    @TestSecurity(user = SYNTHETIC_ACTOR, roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-sepa-instant-fixture"),
            Claim(key = "preferred_username", value = SYNTHETIC_ACTOR),
            Claim(key = "sub", value = "isolated-sepa-instant-fixture-subject"),
        ],
    )
    fun `trusted synthetic origin survives later unmarked transitions through Kafka`() {
        val paymentId = submitClaimingSyntheticSctInstPayment()
        assertThat(paymentOrigin(paymentId)).isTrue()
        assertThat(rows(paymentId).single().synthetic).isTrue()
        settle(paymentId, suppliedSynthetic = false)

        given().contentType(ContentType.JSON)
            .body("""{"reason":"Isolated fixture recall"}""")
            .post("/api/v1/sepa-instant/$paymentId/recall")
            .then().statusCode(200).body("status", equalTo("RECALLED"))

        assertThat(paymentOrigin(paymentId)).isTrue()
        val pending = rows(paymentId)
        assertThat(pending.map { it.eventType })
            .containsExactly("SctInstPaymentSubmitted", "SctInstPaymentSettled", "SctInstPaymentRecalled")
        assertThat(pending.map { it.synthetic }).containsOnly(true)
        assertThat(pending.map { it.status }).containsOnly("PENDING")

        consumer().use { consumer ->
            consumer.subscribe(listOf(TOPIC))
            tick()
            awaitRecords(consumer, paymentId, pending.size).zip(pending).forEach { (record, row) ->
                assertThat(record.headers().lastHeader(OutboxKafkaHeaders.HEADER_EVENT_ID)?.value()?.decodeToString())
                    .isEqualTo(row.eventId.toString())
                assertThat(record.headers().lastHeader(OutboxKafkaHeaders.HEADER_SYNTHETIC)?.value()?.decodeToString())
                    .isEqualTo(SyntheticTaint.TRUE_VALUE)
            }
        }
        awaitSent(paymentId)
    }

    private fun settle(paymentId: UUID, suppliedSynthetic: Boolean) {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        onEventLoop {
            val processing = requireNotNull(repository.findByPaymentId(paymentId).awaitSuspending())
            assertThat(processing.status).isEqualTo(SctInstStatus.PROCESSING)
            repository.updateWithEvent(
                processing.copy(status = SctInstStatus.SETTLED, settledAt = now, synthetic = suppliedSynthetic),
                SctInstStatus.PROCESSING,
                SctInstPaymentSettled(paymentId = paymentId, settledAt = now, occurredAt = now),
            ).awaitSuspending()
        }
    }

    private fun paymentOrigin(paymentId: UUID): Boolean? = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT synthetic FROM sct_inst_payments WHERE payment_id = ?").use { query ->
            query.setObject(1, paymentId)
            query.executeQuery().use { rows ->
                check(rows.next())
                rows.getObject(1, Boolean::class.javaObjectType)
            }
        }
    }

    private fun markLegacyOriginUnknown(paymentId: UUID) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("UPDATE sct_inst_payments SET synthetic = NULL WHERE payment_id = ?").use {
                it.setObject(1, paymentId)
                assertThat(it.executeUpdate()).isEqualTo(1)
            }
        }
    }

    private fun rows(paymentId: UUID): List<OutboxRow> = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT event_id, event_type, status, payload, synthetic FROM sct_inst_outbox " +
                "WHERE aggregate_id = ? ORDER BY created_at, id",
        ).use { query ->
            query.setObject(1, paymentId)
            query.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        add(
                            OutboxRow(
                                result.getObject(1, UUID::class.java),
                                result.getString(2),
                                result.getString(3),
                                result.getString(4),
                                result.getBoolean(5),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun awaitSent(paymentId: UUID) {
        val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        while (System.nanoTime() < deadline) {
            if (rows(paymentId).all { it.status == "SENT" }) return
            Thread.sleep(50)
        }
        error("SCT Inst outbox rows were not marked SENT")
    }

    private fun tick() = VertxContextSupport.subscribeAndAwait {
        uni(CoroutineScope(Dispatchers.Unconfined)) { dispatcher.dispatch() }
    }

    private fun consumer() = KafkaConsumer<String, String>(
        mapOf(
            "bootstrap.servers" to ConfigProvider.getConfig().getValue("kafka.bootstrap.servers", String::class.java),
            "group.id" to "sct-inst-real-broker-${UUID.randomUUID()}",
            "auto.offset.reset" to "earliest",
            "enable.auto.commit" to false,
            "key.deserializer" to "org.apache.kafka.common.serialization.StringDeserializer",
            "value.deserializer" to "org.apache.kafka.common.serialization.StringDeserializer",
        ),
    )

    private fun awaitRecords(
        consumer: KafkaConsumer<String, String>,
        paymentId: UUID,
        count: Int,
    ): List<ConsumerRecord<String, String>> {
        val found = mutableListOf<ConsumerRecord<String, String>>()
        val observed = mutableListOf<String?>()
        val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        while (found.size < count && System.nanoTime() < deadline) {
            consumer.poll(Duration.ofMillis(250)).forEach { record ->
                observed += record.key()
                if (record.key() == paymentId.toString()) found += record
            }
        }
        assertThat(found).withFailMessage(
            "Expected %s broker records, observed keys=%s, outbox statuses=%s",
            count,
            observed,
            rows(paymentId).map { it.status },
        ).hasSize(count)
        return found
    }

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    private companion object {
        const val SYNTHETIC_ACTOR = "service-account-openbank-sepa-instant-fixture"
        const val TOPIC = "openbank.sepa.instant.events"
    }
}

internal fun submitClaimingSyntheticSctInstPayment(): UUID {
    val key = UUID.randomUUID().toString()
    return UUID.fromString(
        given().contentType(ContentType.JSON)
            .header("Idempotency-Key", key)
            .header(SyntheticTaint.KAFKA_HEADER, SyntheticTaint.TRUE_VALUE)
            .body(
                """
                {
                  "idempotencyKey": "$key",
                  "debtorAccountId": "${UUID.randomUUID()}",
                  "debtorIban": "CZ6508000000192000145399",
                  "debtorName": "Test Debtor",
                  "creditorIban": "DE89370400440532013000",
                  "creditorName": "Test Creditor",
                  "creditorBic": "COBADEFFXXX",
                  "amount": 99.99,
                  "currency": "EUR",
                  "endToEndId": "E2E-$key"
                }
                """.trimIndent(),
            )
            .`when`().post("/api/v1/sepa-instant")
            .then().statusCode(201).extract().path<String>("paymentId"),
    )
}
