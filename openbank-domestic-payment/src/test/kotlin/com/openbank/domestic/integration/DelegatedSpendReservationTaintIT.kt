// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.integration

import com.openbank.domestic.application.port.out.DelegatedSpendBindingRepository
import com.openbank.domestic.infrastructure.kafka.DelegatedSpendReservationStateConsumer
import com.openbank.domestic.infrastructure.persistence.repository.DelegatedSpendBindingRepositoryImpl
import com.openbank.domestic.it.PostgresRedisTestResource
import com.openbank.libs.messaging.SyntheticTaintKafkaRail
import com.openbank.libs.synthetic.SyntheticTaint
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.mutiny.coroutines.uni
import io.smallrye.reactive.messaging.kafka.api.IncomingKafkaRecordMetadata
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.enterprise.inject.Any
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.record.RecordBatch
import org.apache.kafka.common.record.TimestampType
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.reactive.messaging.Message
import org.eclipse.microprofile.reactive.messaging.Metadata
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Optional
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * ADR-0252 phase 1 (#8630): the synthetic taint crossing a Kafka hop, proved by effect.
 *
 * ## What has to be real here, and why a cheaper test cannot say it
 *
 * The claim is "a record carrying `x-openbank-synthetic` results in a row that says synthetic, and
 * one without it results in a row that says real". Three things have to be genuine for that to
 * mean anything:
 *
 *  - a real Kafka `IncomingKafkaRecordMetadata`, because the header is the only carrier and a
 *    handler declared over `payload: String` cannot see one at all;
 *  - a real Vert.x context and a real `Panache.withTransaction` chain, because
 *    [SyntheticTaintKafkaRail] establishes ambient rails in the coroutine and the write happens
 *    several reactive hops later — a mocked repository commits nothing and a bare `@QuarkusTest`
 *    thread cannot call a reactive Panache repo at all (`No current Vertx context found`);
 *  - a real Postgres row read back over plain JDBC, because the assertion has to be about what
 *    committed, not about what an in-memory object was set to.
 *
 * ## Which rail actually carries it
 *
 * `carries the taint into the transaction chain on the OTel BAGGAGE rail` is the measurement, not
 * a restatement of the design: it reads each rail separately from INSIDE the transaction chain. It
 * is the assertion that fails if someone later simplifies the rail down to MDC alone.
 *
 * ## Both polarities, or it cannot fail in the direction that matters
 *
 * Every case here has an untainted twin. A test that only ever asserts `true` passes just as
 * happily against a rail hard-wired to `true`, which is the shape that would silently drop real
 * customer activity out of a regulatory aggregate — the unbounded failure direction
 * [SyntheticTaint] argues from.
 */
@QuarkusTest
@QuarkusTestResource(DelegatedSpendReservationTaintIT.TaintChannelResource::class)
@QuarkusTestResource(PostgresRedisTestResource::class)
class DelegatedSpendReservationTaintIT {

    /**
     * Switches the reservation-state channel to the in-memory connector AND enables it.
     *
     * `enabled` matters twice: the channel ships off (`DOMESTIC_DELEGATED_SPEND_CONSUMER_ENABLED`
     * defaults to false, expand-first), and a disabled channel is never wired — so booting this
     * test with the channel live is itself the check that SmallRye accepts the handler's
     * `Message<String>` signature. A wrong signature fails the boot, not one assertion.
     */
    class TaintChannelResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> = InMemoryConnector.switchOutgoingChannelsToInMemory("events-out") +
            InMemoryConnector.switchIncomingChannelsToInMemory(CHANNEL) +
            mapOf("mp.messaging.incoming.$CHANNEL.enabled" to "true")

        override fun stop() = InMemoryConnector.clear()
    }

    @Inject
    lateinit var consumer: DelegatedSpendReservationStateConsumer

    @Inject
    lateinit var bindingRepository: DelegatedSpendBindingRepository

    @Inject
    lateinit var dataSource: DataSource

    @Inject
    @Any
    lateinit var connector: InMemoryConnector

    @BeforeEach
    fun clearBefore() = clearOwnRows()

    @AfterEach
    fun clearAfter() = clearOwnRows()

    @Test
    fun `a record carrying the synthetic header commits a synthetic projection row`() {
        val reservationId = UUID.randomUUID()

        onEventLoop { consumer.consume(record(reservationId, tainted = true)) }

        assertThat(bindingSynthetic(reservationId)).isTrue()
    }

    @Test
    fun `a record without the header commits a real projection row`() {
        val reservationId = UUID.randomUUID()

        onEventLoop { consumer.consume(record(reservationId, tainted = false)) }

        assertThat(bindingSynthetic(reservationId)).isFalse()
    }

    @Test
    fun `only an exact true taints - a permissive value is real`() {
        val reservationId = UUID.randomUUID()

        onEventLoop { consumer.consume(record(reservationId, tainted = true, headerValue = "1")) }

        assertThat(bindingSynthetic(reservationId)).isFalse()
    }

    @Test
    fun `header casing does not decide the taint`() {
        val reservationId = UUID.randomUUID()

        onEventLoop {
            consumer.consume(record(reservationId, tainted = true, headerName = "X-OpenBank-Synthetic"))
        }

        assertThat(bindingSynthetic(reservationId)).isTrue()
    }

    @Test
    fun `the finalizer carries the persisted taint onto the outbox row it emits`() {
        val tainted = UUID.randomUUID()
        val real = UUID.randomUUID()

        onEventLoop { consumer.consume(record(tainted, tainted = true)) }
        onEventLoop { consumer.consume(record(real, tainted = false)) }
        val finalized = onEventLoop {
            bindingRepository.finalizeAbsentBefore(Instant.now().plusSeconds(FINALIZE_CUTOFF_SECONDS), 2)
        }

        assertThat(finalized).isEqualTo(2)
        assertThat(outboxSynthetic(tainted)).isTrue()
        assertThat(outboxSynthetic(real)).isFalse()
    }

    @Test
    fun `the live channel delivers a tainted record to the handler and it commits`() {
        val reservationId = UUID.randomUUID()

        // runOnVertxContext, or the connector emits on the calling thread and the reactive Panache
        // repo the handler reaches fails with `No current Vertx context found` — the same trap that
        // makes a bare @QuarkusTest thread unable to drive this flow at all.
        connector.source<Message<String>>(CHANNEL)
            .runOnVertxContext(true)
            .send(record(reservationId, tainted = true))

        awaitBindingRow(reservationId)
        assertThat(bindingSynthetic(reservationId)).isTrue()
    }

    /**
     * THE measurement this whole slice turns on: which rail survives into the write.
     *
     * `SyntheticTaintKafkaRail` sets MDC and OpenTelemetry baggage in the coroutine; the row is
     * written inside `Panache.withTransaction { ... }`, several reactive hops away. This reads the
     * two rails separately from inside exactly that chain. Measured on Quarkus 3.38 / Vert.x
     * duplicated contexts: BAGGAGE survives, and MDC survives too — but only baggage is asserted,
     * because MDC's survival is a property of the Quarkus MDC provider rather than of anything in
     * this repository, and an assertion that pins it would go red on a platform bump while telling
     * nobody anything useful. Baggage is the rail the production read is entitled to rely on, and
     * this assertion is what fails if the rail is ever simplified down to MDC alone.
     */
    @Test
    fun `carries the taint into the transaction chain on the OTel BAGGAGE rail`() {
        val insideTransaction = onEventLoop {
            SyntheticTaintKafkaRail.withTaintFrom(mapOf(SyntheticTaint.KAFKA_HEADER to "true")) {
                Panache.withTransaction {
                    Panache.getSession().map {
                        RailReading(
                            baggage = SyntheticTaintKafkaRail.baggageTainted(),
                            combined = SyntheticTaintKafkaRail.currentlyTainted(),
                        )
                    }
                }.awaitSuspending()
            }
        }

        assertThat(insideTransaction.baggage)
            .describedAs("OTel baggage must survive the reactive chain that writes the row")
            .isTrue()
        assertThat(insideTransaction.combined).isTrue()
    }

    private data class RailReading(val baggage: Boolean, val combined: Boolean)

    private fun record(
        reservationId: UUID,
        tainted: Boolean,
        headerName: String = SyntheticTaint.KAFKA_HEADER,
        headerValue: String = SyntheticTaint.headerValue(),
    ): Message<String> {
        val payload = payload(reservationId)
        val headers = RecordHeaders()
        if (tainted) {
            headers.add(RecordHeader(headerName, headerValue.toByteArray(StandardCharsets.UTF_8)))
        }
        val consumerRecord = ConsumerRecord(
            "openbank.delegation.spend-reservation-state",
            0,
            0L,
            RecordBatch.NO_TIMESTAMP,
            TimestampType.NO_TIMESTAMP_TYPE,
            ConsumerRecord.NULL_SIZE,
            ConsumerRecord.NULL_SIZE,
            reservationId.toString(),
            payload,
            headers,
            Optional.empty(),
        )
        return Message.of(payload, Metadata.of(IncomingKafkaRecordMetadata(consumerRecord, CHANNEL)))
    }

    private fun payload(reservationId: UUID): String =
        """
        {
          "eventId":"${UUID.randomUUID()}",
          "aggregateId":"$reservationId",
          "aggregateType":"DelegationSpendReservation",
          "eventType":"DelegationSpendReservationStateChanged",
          "version":1,
          "occurredAt":"2026-09-01T12:00:00Z",
          "sourceService":"delegation-service",
          "reservationId":"$reservationId",
          "delegationId":"${UUID.randomUUID()}",
          "grantorPartyId":"$TEST_GRANTOR_ID",
          "granteePartyId":"$TEST_GRANTEE_ID",
          "resourceType":"ACCOUNT",
          "resourceId":"${UUID.randomUUID()}",
          "amount":1500.00,
          "currency":"CZK",
          "idempotencyKeyHash":"${"c".repeat(64)}",
          "operationType":"DOMESTIC_PAYMENT",
          "state":"RESERVED",
          "reservationVersion":1,
          "createdAt":"2026-09-01T12:00:00Z",
          "settledAt":null
        }
        """.trimIndent()

    private fun awaitBindingRow(reservationId: UUID) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DELIVERY_TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline) {
            if (bindingRowCount(reservationId) > 0) return
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        throw AssertionError("The in-memory channel never delivered reservation $reservationId to the handler")
    }

    private fun bindingRowCount(reservationId: UUID): Int = queryOne(
        "SELECT COUNT(*) FROM domestic_delegated_spend_bindings WHERE reservation_id = ?",
        reservationId,
    ) { it.getInt(1) }

    private fun bindingSynthetic(reservationId: UUID): Boolean = queryOne(
        "SELECT synthetic FROM domestic_delegated_spend_bindings WHERE reservation_id = ?",
        reservationId,
    ) { it.getBoolean(1) }

    private fun outboxSynthetic(reservationId: UUID): Boolean = queryOne(
        "SELECT synthetic FROM domestic_payment_outbox WHERE aggregate_id = ? AND event_type = " +
            "'${DelegatedSpendBindingRepositoryImpl.FINALIZED_ABSENT_OUTBOX_EVENT}'",
        reservationId,
    ) { it.getBoolean(1) }

    private fun <T> queryOne(sql: String, id: UUID, mapper: (java.sql.ResultSet) -> T): T =
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, id)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "no row for $id" }
                    mapper(rows)
                }
            }
        }

    private fun clearOwnRows() {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            connection.prepareStatement(
                "DELETE FROM domestic_payment_outbox WHERE aggregate_id IN " +
                    "(SELECT reservation_id FROM domestic_delegated_spend_bindings WHERE grantor_party_id = ?)",
            ).use { statement ->
                statement.setObject(1, TEST_GRANTOR_ID)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "DELETE FROM domestic_delegated_spend_bindings WHERE grantor_party_id = ?",
            ).use { statement ->
                statement.setObject(1, TEST_GRANTOR_ID)
                statement.executeUpdate()
            }
            connection.commit()
        }
    }

    private fun <T> onEventLoop(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        uni(CoroutineScope(Dispatchers.Unconfined)) { block() }
    }

    private companion object {
        const val CHANNEL = DelegatedSpendReservationStateConsumer.CHANNEL
        const val FINALIZE_CUTOFF_SECONDS = 60L
        const val DELIVERY_TIMEOUT_SECONDS = 30L
        const val POLL_INTERVAL_MILLIS = 25L
        val TEST_GRANTOR_ID: UUID = UUID.fromString("60000000-0000-4000-8000-000000000011")
        val TEST_GRANTEE_ID: UUID = UUID.fromString("60000000-0000-4000-8000-000000000012")
    }
}
