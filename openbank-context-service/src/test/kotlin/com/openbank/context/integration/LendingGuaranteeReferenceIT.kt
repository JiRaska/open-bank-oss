// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.context.integration

import com.openbank.context.infrastructure.LendingGuaranteeReferenceConsumer
import com.openbank.context.infrastructure.LendingGuaranteeReferenceDecoder
import com.openbank.context.infrastructure.LendingGuaranteeReferenceRepository
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.asUni
import io.smallrye.reactive.messaging.kafka.api.IncomingKafkaRecordMetadata
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.record.RecordBatch
import org.apache.kafka.common.record.TimestampType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.config.ConfigProvider
import org.eclipse.microprofile.reactive.messaging.Message
import org.eclipse.microprofile.reactive.messaging.Metadata
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_context_it")],
)
@QuarkusTestResource(ContextMessagingTestResource::class)
class LendingGuaranteeReferenceIT {
    @Inject lateinit var repository: LendingGuaranteeReferenceRepository

    @Inject lateinit var decoder: LendingGuaranteeReferenceDecoder

    @Inject lateinit var consumer: LendingGuaranteeReferenceConsumer

    @Test
    fun `replay is idempotent and changed identity is rejected`() {
        val guarantee = UUID.randomUUID()
        val event = UUID.randomUUID()
        val ref = decoder.decode(body(guarantee), event.toString(), LendingGuaranteeReferenceDecoder.TYPE)
        onVertx { repository.append(ref) }
        onVertx { repository.append(ref) }
        assertThat(countRows(guarantee)).isEqualTo(1)
        assertThatThrownBy { onVertx { repository.append(ref.copy(eventId = UUID.randomUUID())) } }
            .hasStackTraceContaining("conflicting Lending reference")
        assertThatThrownBy { onVertx { repository.append(ref.copy(loanId = UUID.randomUUID())) } }
            .hasStackTraceContaining("conflicting Lending reference")
        assertThat(countRows(guarantee)).isEqualTo(1)
    }

    @Test
    fun `broker contract key is accepted without treating it as the loan id`() {
        val guarantee = UUID.randomUUID()
        val delivery = message(body(guarantee), UUID.randomUUID(), UUID.randomUUID())
        onVertx { consumer.consume(delivery.value) }
        assertThat(delivery.acked.get()).isEqualTo(1)
        assertThat(delivery.nacked.get()).isZero()
        assertThat(countRows(guarantee)).isEqualTo(1)
    }

    @Test
    fun `malformed and cross-bank records are nacked`() {
        val malformed = message("{}", UUID.randomUUID(), UUID.randomUUID())
        onVertx { consumer.consume(malformed.value) }
        assertThat(malformed.nacked.get()).isEqualTo(1)
        val wrongBank =
            message(body(UUID.randomUUID()).replace("openbank-cz", "other-bank"), UUID.randomUUID(), UUID.randomUUID())
        onVertx { consumer.consume(wrongBank.value) }
        assertThat(wrongBank.nacked.get()).isEqualTo(1)
    }

    private fun body(guarantee: UUID) = """{"schemaVersion":1,"eventType":"lending.graph.guarantee.approved",
        "guaranteeId":"$guarantee","loanId":"${UUID.randomUUID()}","revision":1,
        "bankScope":"openbank-cz","occurredAt":"2026-09-25T10:00:00Z"}
    """.trimIndent()

    private fun message(payload: String, event: UUID, contract: UUID): Delivery {
        val headers = RecordHeaders()
        mapOf(
            OutboxKafkaHeaders.HEADER_EVENT_ID to event.toString(),
            OutboxKafkaHeaders.HEADER_IDEMPOTENCY_KEY to event.toString(),
            OutboxKafkaHeaders.HEADER_EVENT_TYPE to LendingGuaranteeReferenceDecoder.TYPE,
        ).forEach { (name, value) -> headers.add(name, value.toByteArray(Charsets.UTF_8)) }
        val record = ConsumerRecord(
            TOPIC, 0, 0L, RecordBatch.NO_TIMESTAMP, TimestampType.NO_TIMESTAMP_TYPE,
            ConsumerRecord.NULL_SIZE, ConsumerRecord.NULL_SIZE, contract.toString(), payload,
            headers, Optional.empty(),
        )
        val acked = AtomicInteger()
        val nacked = AtomicInteger()
        val value = Message.of(
            payload,
            Metadata.of(IncomingKafkaRecordMetadata(record, "lending-guarantee-references-in")),
        )
            .withAck {
                acked.incrementAndGet()
                CompletableFuture.completedFuture(null)
            }
            .withNack { _: Throwable ->
                nacked.incrementAndGet()
                CompletableFuture.completedFuture(null)
            }
        return Delivery(value, acked, nacked)
    }

    private fun countRows(guarantee: UUID): Int {
        val config = ConfigProvider.getConfig()
        return DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        ).use { connection ->
            connection.prepareStatement(
                "SELECT count(*) FROM context_lending_guarantee_references WHERE guarantee_id = ?",
            )
                .use { statement ->
                    statement.setObject(1, guarantee)
                    statement.executeQuery().use { rows ->
                        rows.next()
                        rows.getInt(1)
                    }
                }
        }
    }

    private fun <T> onVertx(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }

    private data class Delivery(val value: Message<String>, val acked: AtomicInteger, val nacked: AtomicInteger)

    private companion object {
        const val TOPIC = "openbank.lending.graph.references"
    }
}
