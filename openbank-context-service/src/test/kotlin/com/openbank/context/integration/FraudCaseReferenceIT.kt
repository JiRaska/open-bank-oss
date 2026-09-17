// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.context.integration

import com.openbank.context.infrastructure.FraudCaseReferenceConsumer
import com.openbank.context.infrastructure.FraudCaseReferenceDecoder
import com.openbank.context.infrastructure.FraudCaseReferenceRepository
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
import java.time.Instant
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
class FraudCaseReferenceIT {
    @Inject lateinit var repository: FraudCaseReferenceRepository

    @Inject lateinit var consumer: FraudCaseReferenceConsumer

    @Inject lateinit var decoder: FraudCaseReferenceDecoder

    @Test
    fun `reference replay is idempotent and conflicting revision is rejected`() {
        val caseId = UUID.randomUUID()
        val eventId = UUID.randomUUID()
        val reference = decoder.decode(body(caseId), eventId.toString(), OPENED)
        onVertx { repository.append(reference) }
        onVertx { repository.append(reference) }
        assertThat(countRows(caseId, scoped = true)).isEqualTo(1)
        assertThat(countRows(caseId, scoped = false)).isZero()
        assertThatThrownBy {
            onVertx { repository.append(reference.copy(eventId = UUID.randomUUID())) }
        }.hasStackTraceContaining("conflicting Fraud case reference")
    }

    @Test
    fun `broker record is acknowledged only for exact topic key headers and payload`() {
        val caseId = UUID.randomUUID()
        val accepted = message(body(caseId), UUID.randomUUID(), caseId)
        onVertx { consumer.consume(accepted.value) }
        assertThat(accepted.acked.get()).isEqualTo(1)
        assertThat(accepted.nacked.get()).isZero()
        assertThat(countRows(caseId, scoped = true)).isEqualTo(1)

        val wrongKey = message(body(UUID.randomUUID()), UUID.randomUUID(), caseId)
        onVertx { consumer.consume(wrongKey.value) }
        assertThat(wrongKey.acked.get()).isZero()
        assertThat(wrongKey.nacked.get()).isEqualTo(1)
    }

    @Test
    fun `candidate discovery returns only exact active root assignments`() {
        val root = UUID.randomUUID()
        val assigned = UUID.randomUUID()
        val wrongRoot = UUID.randomUUID()
        val expired = UUID.randomUUID()
        val now = Instant.now()
        for (id in listOf(root, assigned, wrongRoot, expired)) {
            onVertx { repository.append(decoder.decode(body(id), UUID.randomUUID().toString(), OPENED)) }
        }
        seedAssignment(assigned, "fraud-case:$assigned", "investigator-1", now.plusSeconds(3600))
        seedAssignment(wrongRoot, "fraud-case:${UUID.randomUUID()}", "investigator-1", now.plusSeconds(3600))
        seedAssignment(expired, "fraud-case:$expired", "investigator-1", now.minusSeconds(1))

        val candidates = onVertx { repository.assignedCandidates(root, "investigator-1", now) }
        assertThat(candidates.ids).containsExactly(assigned)
        assertThat(candidates.truncated).isFalse()
    }

    private fun seedAssignment(caseId: UUID, rootRef: String, principal: String, validTo: Instant) {
        val config = ConfigProvider.getConfig()
        DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        ).use { connection ->
            connection.prepareStatement(
                """INSERT INTO context_case_assignments
                   (assignment_id, bank_scope, principal_id, case_id, purpose, root_ref, valid_from, valid_to, created_at)
                   VALUES (?, 'openbank-cz', ?, ?, 'FRAUD_INVESTIGATION', ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setString(2, principal)
                statement.setString(3, caseId.toString())
                statement.setString(4, rootRef)
                statement.setTimestamp(5, java.sql.Timestamp.from(Instant.now().minusSeconds(3600)))
                statement.setTimestamp(6, java.sql.Timestamp.from(validTo))
                statement.setTimestamp(7, java.sql.Timestamp.from(Instant.now()))
                statement.executeUpdate()
            }
        }
    }

    private fun body(caseId: UUID) =
        """{"eventType":"fraud.case_opened","caseId":"$caseId","revision":1,"occurredAt":"2026-09-17T00:00:00Z"}"""

    private fun message(payload: String, eventId: UUID, brokerCaseId: UUID): Delivery {
        val headers = RecordHeaders()
        mapOf(
            OutboxKafkaHeaders.HEADER_EVENT_ID to eventId.toString(),
            OutboxKafkaHeaders.HEADER_IDEMPOTENCY_KEY to eventId.toString(),
            OutboxKafkaHeaders.HEADER_EVENT_TYPE to OPENED,
        ).forEach { (name, value) -> headers.add(name, value.toByteArray(Charsets.UTF_8)) }
        val record = ConsumerRecord(
            TOPIC, 0, 0L, RecordBatch.NO_TIMESTAMP, TimestampType.NO_TIMESTAMP_TYPE,
            ConsumerRecord.NULL_SIZE, ConsumerRecord.NULL_SIZE, brokerCaseId.toString(), payload,
            headers, Optional.empty(),
        )
        val acked = AtomicInteger()
        val nacked = AtomicInteger()
        val value = Message.of(
            payload,
            Metadata.of(IncomingKafkaRecordMetadata(record, "fraud-case-references-in")),
        ).withAck {
            acked.incrementAndGet()
            CompletableFuture.completedFuture(null)
        }.withNack { _: Throwable ->
            nacked.incrementAndGet()
            CompletableFuture.completedFuture(null)
        }
        return Delivery(value, acked, nacked)
    }

    @Suppress("NestedBlockDepth") // JDBC role, transaction and row scopes close before dropping the role.
    private fun countRows(caseId: UUID, scoped: Boolean): Int {
        val config = ConfigProvider.getConfig()
        val role = "fraud_ref_rls_${UUID.randomUUID().toString().replace("-", "")}"
        return DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        ).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE ROLE $role NOLOGIN NOSUPERUSER NOBYPASSRLS")
                try {
                    statement.execute("GRANT USAGE ON SCHEMA public TO $role")
                    statement.execute("GRANT SELECT ON context_fraud_case_references TO $role")
                    statement.execute("SET ROLE $role")
                    connection.autoCommit = false
                    if (scoped) statement.execute("SELECT set_config('openbank.bank_scope', 'openbank-cz', true)")
                    connection.prepareStatement(
                        "SELECT count(*) FROM context_fraud_case_references WHERE case_id = ?",
                    ).use { query ->
                        query.setObject(1, caseId)
                        query.executeQuery().use { rows ->
                            rows.next()
                            rows.getInt(1)
                        }
                    }
                } finally {
                    if (!connection.autoCommit) connection.rollback()
                    connection.autoCommit = true
                    statement.execute("RESET ROLE")
                    statement.execute("REVOKE SELECT ON context_fraud_case_references FROM $role")
                    statement.execute("REVOKE USAGE ON SCHEMA public FROM $role")
                    statement.execute("DROP ROLE $role")
                }
            }
        }
    }

    private fun <T> onVertx(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }

    private data class Delivery(val value: Message<String>, val acked: AtomicInteger, val nacked: AtomicInteger)

    private companion object {
        const val TOPIC = "openbank.fraud.investigation.case.references"
        const val OPENED = "fraud.case_opened"
    }
}
