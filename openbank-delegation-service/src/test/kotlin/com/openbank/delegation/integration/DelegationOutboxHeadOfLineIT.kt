// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.integration

import com.openbank.delegation.domain.event.DelegationSpendReservationStateChanged
import com.openbank.delegation.infrastructure.persistence.repository.DelegationOutboxRepositoryImpl
import com.openbank.delegation.it.PostgresTestResource
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.mutiny.coroutines.uni
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** Proves fail-closed per-reservation ordering in the real Postgres claim query. */
@QuarkusTest
@QuarkusTestResource(DelegationOutboxHeadOfLineIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class DelegationOutboxHeadOfLineIT {
    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> = InMemoryConnector.switchOutgoingChannelsToInMemory(
            "delegation-events-out",
            "spend-reservation-state-out",
        ) + ("openbank.outbox.dispatch-enabled" to "false")

        override fun stop() = InMemoryConnector.clear()
    }

    @Inject
    lateinit var repository: DelegationOutboxRepositoryImpl

    private var sequence = 0L

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    @BeforeEach
    fun clearOutbox() {
        sequence = 0L
        onEventLoop { Panache.withTransaction { repository.deleteAll() }.awaitSuspending() }
    }

    private fun seed(aggregateId: UUID, eventType: String, payload: String = "{}"): OutboxMessage {
        val message = OutboxMessage(
            aggregateId = aggregateId,
            eventType = eventType,
            payload = payload,
            createdAt = Instant.parse("2026-09-01T00:00:00Z").plusMillis(sequence++),
        )
        onEventLoop { Panache.withTransaction { repository.persistInTransaction(message) }.awaitSuspending() }
        return message
    }

    private fun seedStateWithDatabaseId(databaseId: Long, aggregateId: UUID, reservationVersion: Long): OutboxMessage {
        val message = OutboxMessage(
            aggregateId = aggregateId,
            eventType = DelegationSpendReservationStateChanged.EVENT_TYPE,
            payload = """{"reservationVersion":$reservationVersion}""",
            createdAt = Instant.parse("2026-09-01T00:00:00Z").plusMillis(sequence++),
        )
        val jdbcUrl = ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String::class.java)
        DriverManager.getConnection(jdbcUrl, "openbank", "openbank_secret").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO delegation_outbox
                    (id, event_id, aggregate_id, event_type, payload, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, databaseId)
                statement.setObject(2, message.eventId)
                statement.setObject(3, aggregateId)
                statement.setString(4, message.eventType)
                statement.setString(5, message.payload)
                statement.setTimestamp(6, Timestamp.from(message.createdAt))
                statement.setTimestamp(7, Timestamp.from(message.createdAt))
                statement.executeUpdate()
            }
        }
        return message
    }

    @Test
    fun `terminal snapshot waits until reserved snapshot is sent regardless of database id`() {
        val reservationId = UUID.randomUUID()
        val reserved = seedStateWithDatabaseId(-100, reservationId, 1)
        val terminal = seedStateWithDatabaseId(-200, reservationId, 2)
        // Keep the control row outside the lifecycle trigger's exact allowlist. A synthetic
        // DelegationRevoked would correctly require a matching grant row at commit time.
        val lifecycle = seed(reservationId, "DelegationSpendReserved")

        val first = onEventLoop { repository.claimProcessable(10) }
        assertThat(first.map { it.eventId }).contains(reserved.eventId, lifecycle.eventId)
        assertThat(first.map { it.eventId }).doesNotContain(terminal.eventId)

        onEventLoop { repository.markSent(reserved.eventId, Instant.now()) }
        assertThat(onEventLoop { repository.claimProcessable(10) }.map { it.eventId })
            .containsExactly(terminal.eventId)
    }

    @Test
    fun `head of line lookup uses the partial revision index`() {
        val jdbcUrl = ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String::class.java)
        DriverManager.getConnection(jdbcUrl, "openbank", "openbank_secret").use { connection ->
            connection.createStatement().use { it.execute("SET enable_seqscan = off") }
            connection.prepareStatement(
                """
                EXPLAIN SELECT 1 FROM delegation_outbox AS older
                 WHERE older.aggregate_id = ?
                   AND older.event_type = 'DelegationSpendReservationStateChanged'
                   AND (older.payload::jsonb ->> 'reservationVersion')::BIGINT < 2
                   AND older.status <> 'SENT'
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.executeQuery().use { result ->
                    val plan = buildList {
                        while (result.next()) add(result.getString(1))
                    }.joinToString("\n")
                    assertThat(plan).contains("idx_delegation_outbox_spend_hol")
                }
            }
        }
    }
}
