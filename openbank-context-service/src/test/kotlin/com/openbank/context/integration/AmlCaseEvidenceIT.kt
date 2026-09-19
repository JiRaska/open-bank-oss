// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.context.infrastructure.AmlCaseEventDecoder
import com.openbank.context.infrastructure.AmlCaseEvidenceConsumer
import com.openbank.context.infrastructure.AmlCaseHistoryRepository
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
import org.hibernate.reactive.mutiny.Mutiny
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
class AmlCaseEvidenceIT {
    @Inject lateinit var repository: AmlCaseHistoryRepository

    @Inject lateinit var consumer: AmlCaseEvidenceConsumer

    @Inject lateinit var decoder: AmlCaseEventDecoder

    @Inject lateinit var mapper: ObjectMapper

    @Inject lateinit var sessions: Mutiny.SessionFactory

    @Test
    fun `identical event replay is idempotent while changed content preserves original`() {
        val id = UUID.randomUUID()
        val event = decoder.decode(payload(id), UUID.randomUUID().toString(), CREATED)
        onVertx { repository.append(event) }
        onVertx { repository.append(event.copy()) }
        assertThatThrownBy { onVertx { repository.append(event.copy(status = "CLEARED")) } }
            .hasStackTraceContaining("conflicting AML event identifier")
        assertThat(history(id).observations.map { it.evidence }).containsExactly(event)
    }

    @Test
    fun `late source observation is absent from earlier known time snapshot`() {
        val id = UUID.randomUUID()
        val first = decoder.decode(payload(id), UUID.randomUUID().toString(), CREATED)
        onVertx { repository.append(first) }
        val recordedAt = history(id).observations.single().recordedAt
        val late = decoder.decode(payload(id, changed = true), UUID.randomUUID().toString(), CHANGED)
        onVertx { repository.append(late) }
        assertThat(history(id).observations.map { it.evidence }).containsExactly(late, first)
        val earlier = onVertx { repository.history(id, Instant.now(), recordedAt) }
        assertThat(earlier.observations.map { it.evidence }).containsExactly(first)
    }

    @Test
    fun `bounded related history discloses truncation instead of returning every observation`() {
        val id = UUID.randomUUID()
        val first = decoder.decode(payload(id), UUID.randomUUID().toString(), CREATED)
        val second = decoder.decode(payload(id, changed = true), UUID.randomUUID().toString(), CHANGED)
        onVertx { repository.append(first) }
        onVertx { repository.append(second) }
        val bound = Instant.now().plusSeconds(60)
        val limited = onVertx { repository.history(id, bound, bound, 1) }
        assertThat(limited.observations.map { it.evidence }).containsExactly(second)
        assertThat(limited.truncated).isTrue()
        assertThat(history(id).truncated).isFalse()
    }

    @Test
    fun `broker shaped created and changed messages are acknowledged and store minimized evidence`() {
        val id = UUID.randomUUID()
        val created = message(payload(id), UUID.randomUUID().toString(), CREATED)
        val changed = message(payload(id, changed = true), UUID.randomUUID().toString(), CHANGED)
        onVertx { consumer.consume(created.message) }
        onVertx { consumer.consume(changed.message) }
        assertThat(created.acknowledged.get()).isEqualTo(1)
        assertThat(changed.acknowledged.get()).isEqualTo(1)
        assertThat(created.rejected.get() + changed.rejected.get()).isZero()
        val events = history(id).observations.map { it.evidence }
        assertThat(events.map { it.status }).containsExactly("UNDER_REVIEW", "OPEN")
        assertThat(events.first().accountId).isNull()
        assertThat(events.first().transactionId).isNull()
        assertThat(events.first().riskLevel).isNull()
        assertThat(events.first().screeningType).isNull()
        assertThat(events.last().accountId).isNull()
        assertThat(mapper.writeValueAsString(events))
            .doesNotContain("private-", "Synthetic Person", "matchedEntity", "alertCode", "assignedAnalyst")
    }

    @Test
    fun `missing or mismatched identity and type headers are nacked without persistence`() {
        val id = UUID.randomUUID()
        val eventId = UUID.randomUUID().toString()
        val candidates = listOf(
            message(payload(id), null, CREATED),
            message(payload(id), eventId, CREATED, UUID.randomUUID().toString()),
            message(payload(id), eventId, null),
            message(payload(id), eventId, CREATED, metadataPresent = false),
            message(payload(id), eventId, CREATED, brokerCaseId = UUID.randomUUID().toString()),
        )
        candidates.forEach {
            onVertx { consumer.consume(it.message) }
            assertThat(it.acknowledged.get()).isZero()
            assertThat(it.rejected.get()).isEqualTo(1)
        }
        assertThat(history(id).observations).isEmpty()
    }

    @Test
    fun `another bank cannot retrieve or conflict with local case evidence`() {
        val id = UUID.randomUUID()
        val event = decoder.decode(payload(id), UUID.randomUUID().toString(), CREATED)
        val other = AmlCaseHistoryRepository(sessions, mapper, "synthetic-aml-${UUID.randomUUID()}", 5000)
        onVertx { other.append(event) }
        assertThat(history(id).observations).isEmpty()
        onVertx { repository.append(event.copy(status = "ESCALATED")) }
        assertThat(history(id).observations.single().evidence.status).isEqualTo("ESCALATED")
        assertThat(onVertx { other.history(id, Instant.now(), Instant.now()) }.observations.single().evidence)
            .isEqualTo(event)
    }

    @Test
    fun `source account and transaction references remain typed graph leads`() {
        val id = UUID.randomUUID()
        val account = UUID.randomUUID()
        val transaction = UUID.randomUUID()
        val event = decoder.decode(
            payload(id, accountId = account, transactionId = transaction),
            UUID.randomUUID().toString(),
            CREATED,
        )
        onVertx { repository.append(event) }
        assertThat(history(id).observations.single().evidence.accountId).isEqualTo(account)
        assertThat(history(id).observations.single().evidence.transactionId).isEqualTo(transaction)
        val config = ConfigProvider.getConfig()
        DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        ).use { connection ->
            connection.autoCommit = false
            connection.createStatement().use {
                it.execute("SELECT set_config('openbank.bank_scope', 'openbank-cz', true)")
            }
            connection.prepareStatement(
                "SELECT account_id, transaction_id FROM context_aml_case_evidence WHERE event_id = ?",
            ).use { statement ->
                statement.setObject(1, event.eventId)
                statement.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getObject(1, UUID::class.java)).isEqualTo(account)
                    assertThat(rows.getObject(2, UUID::class.java)).isEqualTo(transaction)
                }
            }
            connection.rollback()
        }
    }

    @Test
    @Suppress("NestedBlockDepth") // JDBC role and transaction scopes must remain paired with their cleanup.
    fun `nonowner database role cannot read AML evidence without the transaction bank scope`() {
        val id = UUID.randomUUID()
        val event = decoder.decode(payload(id), UUID.randomUUID().toString(), CREATED)
        onVertx { repository.append(event) }
        val config = ConfigProvider.getConfig()
        val role = "aml_rls_${UUID.randomUUID().toString().replace("-", "")}" // Generated SQL identifier only.
        DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        ).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE ROLE $role NOLOGIN NOSUPERUSER NOBYPASSRLS")
                try {
                    statement.execute("GRANT USAGE ON SCHEMA public TO $role")
                    statement.execute("GRANT SELECT ON context_aml_case_evidence TO $role")
                    statement.execute("SET ROLE $role")
                    connection.autoCommit = false
                    assertThat(visibleCases(connection, id)).isZero()
                    statement.execute("SELECT set_config('openbank.bank_scope', 'openbank-cz', true)")
                    assertThat(visibleCases(connection, id)).isEqualTo(1)
                    connection.rollback()
                    assertThat(visibleCases(connection, id)).isZero()
                } finally {
                    if (!connection.autoCommit) connection.rollback()
                    connection.autoCommit = true
                    statement.execute("RESET ROLE")
                    statement.execute("REVOKE SELECT ON context_aml_case_evidence FROM $role")
                    statement.execute("REVOKE USAGE ON SCHEMA public FROM $role")
                    statement.execute("DROP ROLE $role")
                }
            }
        }
    }

    private fun visibleCases(connection: java.sql.Connection, id: UUID): Int = connection.prepareStatement(
        "SELECT count(*) FROM context_aml_case_evidence WHERE case_id = ?",
    ).use { statement ->
        statement.setObject(1, id)
        statement.executeQuery().use { rows ->
            rows.next()
            rows.getInt(1)
        }
    }

    private fun history(id: UUID) = onVertx {
        // Direct repository tests use a future bound to tolerate host/PostgreSQL clock skew.
        // Public HTTP reads still reject caller-supplied future snapshots.
        val bound = Instant.now().plusSeconds(60)
        repository.history(id, bound, bound)
    }

    private fun payload(
        id: UUID,
        changed: Boolean = false,
        accountId: UUID? = null,
        transactionId: UUID? = null,
    ): String {
        val fields = mutableMapOf<String, Any?>(
            "caseId" to id,
            "partyId" to PARTY,
            "occurredAt" to "2026-09-01T00:00:00Z",
        )
        if (changed) {
            fields.putAll(
                mapOf(
                    "previousStatus" to "OPEN",
                    "newStatus" to "UNDER_REVIEW",
                    "occurredAt" to "2026-09-02T00:00:00Z",
                    "decisionReason" to "private-reason",
                    "assignedAnalyst" to "private-analyst",
                    "decidedBy" to "private-decider",
                ),
            )
        } else {
            fields.putAll(
                mapOf(
                    "accountId" to accountId, "transactionId" to transactionId, "status" to "OPEN",
                    "riskLevel" to "LOW", "screeningType" to "TRANSACTION_MONITORING",
                    "customerReference" to "private-reference", "matchedEntity" to "Synthetic Person",
                    "alertCode" to "private-alert", "idempotencyKey" to "private-key",
                ),
            )
        }
        return mapper.writeValueAsString(fields)
    }

    private fun message(
        payload: String,
        eventId: String?,
        type: String?,
        key: String? = eventId,
        metadataPresent: Boolean = true,
        brokerCaseId: String? = mapper.readTree(payload).path("caseId").asText(),
    ): Delivery {
        val headers = RecordHeaders()
        mapOf(
            OutboxKafkaHeaders.HEADER_EVENT_ID to eventId,
            OutboxKafkaHeaders.HEADER_IDEMPOTENCY_KEY to key,
            OutboxKafkaHeaders.HEADER_EVENT_TYPE to type,
        ).forEach { (name, value) -> if (value != null) headers.add(name, value.toByteArray(Charsets.UTF_8)) }
        val record = ConsumerRecord(
            "openbank.aml.events", 0, 0L, RecordBatch.NO_TIMESTAMP, TimestampType.NO_TIMESTAMP_TYPE,
            ConsumerRecord.NULL_SIZE, ConsumerRecord.NULL_SIZE, brokerCaseId, payload, headers, Optional.empty(),
        )
        val metadata = if (metadataPresent) {
            Metadata.of(IncomingKafkaRecordMetadata(record, "aml-case-evidence-in"))
        } else {
            Metadata.empty()
        }
        val ack = AtomicInteger()
        val nack = AtomicInteger()
        val message = Message.of(payload, metadata)
            .withAck {
                ack.incrementAndGet()
                CompletableFuture.completedFuture(null)
            }
            .withNack { _: Throwable ->
                nack.incrementAndGet()
                CompletableFuture.completedFuture(null)
            }
        return Delivery(message, ack, nack)
    }

    private data class Delivery(
        val message: Message<String>,
        val acknowledged: AtomicInteger,
        val rejected: AtomicInteger,
    )

    private fun <T> onVertx(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }

    private companion object {
        const val PARTY = "00000000-0000-4000-8000-000000000002"
        const val CREATED = "aml.case.created.v1"
        const val CHANGED = "aml.case.status_changed.v1"
    }
}
