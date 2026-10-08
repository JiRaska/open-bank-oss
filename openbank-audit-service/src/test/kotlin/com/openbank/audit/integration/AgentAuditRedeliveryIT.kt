// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.integration

import com.openbank.audit.application.AgentAuditConsumer
import com.openbank.audit.domain.model.AuditEntry
import com.openbank.audit.domain.model.OccurredAtSource
import com.openbank.audit.infrastructure.persistence.AuditRepository
import com.openbank.audit.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.config.ConfigProvider
import org.eclipse.microprofile.reactive.messaging.Message
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier

/** A post-commit Kafka redelivery must not add a second hash-chain entry. */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class AgentAuditRedeliveryIT {
    @Inject lateinit var repository: AuditRepository

    @Inject lateinit var consumer: AgentAuditConsumer

    @Test
    fun `consumer acknowledges redelivery after one durable hash-chain append`() {
        val eventId = UUID.randomUUID()
        val acknowledgements = AtomicInteger()
        val payload = """
            {
              "eventId":"$eventId",
              "eventType":"agent.run",
              "aggregateType":"AI_AGENT",
              "aggregateId":"agent:redelivery-$eventId",
              "actorId":"agent:rca",
              "actorType":"AI_AGENT",
              "sourceService":"agent-service",
              "correlationId":"redelivery-$eventId",
              "occurredAt":"2026-08-21T12:00:00Z"
            }
        """.trimIndent()
        fun message() = Message.of(
            payload,
            Supplier {
                acknowledgements.incrementAndGet()
                CompletableFuture.completedFuture<Void>(null)
            },
        )

        onEventLoop {
            consumer.consume(message())
            consumer.consume(message())
        }

        assertThat(acknowledgements.get()).isEqualTo(2)
        DriverManager.getConnection(
            ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String::class.java),
            "openbank",
            "openbank_secret",
        ).use { connection ->
            connection.prepareStatement(
                "SELECT entry_id, prev_hash, record_hash FROM audit_entries WHERE entry_id = ?",
            ).use { statement ->
                statement.setObject(1, eventId)
                statement.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getObject("entry_id", UUID::class.java)).isEqualTo(eventId)
                    assertThat(rows.getString("prev_hash")).hasSize(64)
                    assertThat(rows.getString("record_hash")).hasSize(64)
                    assertThat(rows.next()).isFalse()
                }
            }
        }
        assertThat(onEventLoop { repository.verifyChain().intact }).isTrue()
    }

    @Test
    fun `same producer event id is stored once across redelivery`() {
        val eventId = UUID.randomUUID()
        val entry = AuditEntry(
            id = eventId,
            eventType = "agent.run",
            aggregateType = "AI_AGENT",
            aggregateId = "agent:rca",
            actorId = "agent:rca",
            actorType = "AI_AGENT",
            payload = "{\"eventId\":\"$eventId\"}",
            sourceService = "agent-service",
            correlationId = "redelivery-$eventId",
            occurredAt = Instant.parse("2026-08-21T12:00:00Z"),
            recordedAt = Instant.parse("2026-08-21T12:00:00Z"),
            occurredAtSource = OccurredAtSource.EVENT,
        )

        onEventLoop {
            repository.save(entry)
            repository.save(entry)
        }

        assertThat(onEventLoop { repository.findByAggregateId("agent:rca") }.map { it.id })
            .containsExactly(eventId)
        assertThatThrownBy { onEventLoop { repository.save(entry.copy(payload = "{\"conflict\":true}")) } }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { onEventLoop { repository.save(entry.copy(sourceService = "other-service")) } }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(onEventLoop { repository.findByAggregateId("agent:rca") }.map { it.id })
            .containsExactly(eventId)
    }

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }
}
