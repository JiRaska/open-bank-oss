// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.context.infrastructure.ComplaintProjectionConsumer
import com.openbank.context.infrastructure.ContextEdgeEntity
import com.openbank.context.infrastructure.DomesticPaymentProjectionConsumer
import com.openbank.context.infrastructure.IncidentProjectionConsumer
import com.openbank.context.infrastructure.PaymentBookingProjectionConsumer
import com.openbank.context.infrastructure.PaymentRailProjectionConsumer
import com.openbank.libs.testing.containers.PostgresTestResource
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.asUni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.config.ConfigProvider
import org.hibernate.reactive.mutiny.Mutiny
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.util.UUID

@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_context_complaint_revisions_it")],
)
@QuarkusTestResource(ContextMessagingTestResource::class)
class ProjectionGenerationReplayIT {
    @Inject
    lateinit var sessions: Mutiny.SessionFactory

    @Inject
    lateinit var mapper: ObjectMapper

    @Inject
    lateinit var clock: Clock

    @Inject
    lateinit var meters: MeterRegistry

    // Keep the complete five-source replay scenario together so both generations use identical evidence.
    @Suppress("LongMethod")
    @Test
    fun `all source projections replay independently and retain legacy ledger evidence`() {
        val complaint = UUID.randomUUID()
        val reference = "CMP-REPLAY-${UUID.randomUUID()}"
        val account = UUID.randomUUID()
        val payment = UUID.randomUUID()
        val booking = UUID.randomUUID()
        val item = UUID.randomUUID()
        val incident = UUID.randomUUID()
        val complaintKey = "complaint:$complaint:1"
        val complaintPayload = """{"schemaVersion":1,"sourceVersion":1,"aggregateRevision":1,""" +
            """"eventType":"complaint.received","sourceService":"dispute-service",""" +
            """"complaintId":"$complaint","reference":"$reference","status":"RECEIVED",""" +
            """"occurredAt":"$TIME","accountId":"$account","transactionId":"$booking"}"""
        val paymentPayload = """{"eventType":"DOMESTIC_PAYMENT_CREATED","sourceService":"domestic-payment",""" +
            """"paymentId":"$payment","aggregateRevision":1,"status":"RECEIVED",""" +
            """"occurredAt":"$TIME"}"""
        val bookingPayload = """{"eventType":"TransactionInitiated","sourceService":"transaction-service",""" +
            """"aggregateId":"$booking","version":0,"originatingPaymentId":"$payment",""" +
            """"occurredAt":"$TIME"}"""
        val railPayload = """{"eventType":"openbank.clearing.item.cleared","sourceService":"clearing-service",""" +
            """"itemId":"$item","batchId":"${UUID.randomUUID()}","paymentId":"$payment","version":2,""" +
            """"status":"SETTLED","occurredAt":"$TIME"}"""
        val incidentPayload = """{"schemaVersion":1,"sourceVersion":1,"aggregateRevision":1,""" +
            """"eventType":"ICT_INCIDENT_STATUS_CHANGED","sourceService":"security-scanner",""" +
            """"occurredAt":"$TIME","incident":{"id":"$incident","severity":"P1_CRITICAL",""" +
            """"status":"OPEN","affectedServices":["payment-service"],"detectedAt":"$TIME",""" +
            """"updatedAt":"$TIME"}}"""
        connection { db ->
            db.prepareStatement(
                "INSERT INTO context_projection_events " +
                    "(bank_scope, event_key, source_system, aggregate_ref, source_version, " +
                    "occurred_at, processed_at) " +
                    "VALUES ('openbank-cz', ?, 'dispute-service', ?, 1, ?::timestamptz, ?::timestamptz)",
            ).use { statement ->
                statement.setString(1, complaintKey)
                statement.setString(2, "complaint:$reference")
                statement.setString(3, TIME.toString())
                statement.setString(4, TIME.toString())
                assertThat(statement.executeUpdate()).isEqualTo(1)
            }
            db.commit()
        }
        val expectedNodes = setOf(
            "complaint:$reference", "account:$account", "transaction:$payment",
            "payment-stage:domestic:$payment:1", "booking-transaction:$booking",
            "clearing-item:$item", "clearing-evidence:$item:2", "incident:$incident", "service:payment-service",
        )
        val expectedEdges = setOf(
            "complaint:$reference|account:$account|CONCERNS_ACCOUNT",
            "complaint:$reference|booking-transaction:$booking|CONCERNS_TRANSACTION",
            "transaction:$payment|payment-stage:domestic:$payment:1|CREATED",
            "transaction:$payment|booking-transaction:$booking|BOOKING_REQUESTED",
            "transaction:$payment|clearing-item:$item|SUBMITTED_TO",
            "clearing-item:$item|clearing-evidence:$item:2|SETTLED",
            "incident:$incident|service:payment-service|AFFECTS_SERVICE",
        )
        for (generation in listOf(7L, 8L)) {
            val complaints = ComplaintProjectionConsumer(sessions, mapper, clock, meters, BANK, generation, 5000, true)
            val payments =
                DomesticPaymentProjectionConsumer(sessions, mapper, clock, meters, BANK, generation, 5000, true)
            val bookings =
                PaymentBookingProjectionConsumer(sessions, mapper, clock, meters, BANK, generation, 5000, true)
            val rails = PaymentRailProjectionConsumer(sessions, mapper, clock, meters, BANK, generation, 5000, true)
            val incidents = IncidentProjectionConsumer(sessions, mapper, clock, meters, BANK, generation, 5000, true)
            repeat(2) {
                onVertx {
                    complaints.consume(complaintPayload)
                    payments.consume(paymentPayload)
                    bookings.consumeTransaction(bookingPayload)
                    rails.consumeClearing(railPayload)
                    incidents.consume(incidentPayload)
                }
            }
            assertThat(values("context_nodes", "node_key", "node_key", expectedNodes, generation))
                .containsExactlyInAnyOrderElementsOf(expectedNodes)
            assertThat(
                values(
                    "context_edges",
                    "from_key || '|' || to_key || '|' || relation_type",
                    "from_key",
                    expectedNodes,
                    generation,
                ),
            ).containsExactlyInAnyOrderElementsOf(expectedEdges)
            assertThatThrownBy {
                onVertx {
                    incidents.consume(incidentPayload.replace("ICT_INCIDENT_STATUS_CHANGED", "ICT_INCIDENT_REPORTED"))
                }
            }.hasStackTraceContaining("idx_context_incident_revision_digest")
            val eventKeys = setOf(
                complaintKey,
                "domestic-payment:$payment:1",
                "transaction:$booking:0",
                "clearing:$item:2",
                "incident:$incident:ICT_INCIDENT_STATUS_CHANGED:1",
            )
            assertThat(values("context_projection_events", "event_key", "event_key", eventKeys, generation))
                .containsExactlyInAnyOrderElementsOf(eventKeys)
        }
        assertOrmEdges(payment, expectedEdges)
        connection { db ->
            db.prepareStatement(
                "SELECT event_key FROM context_projection_events WHERE bank_scope = ? " +
                    "AND event_key = ? AND projection_generation IS NULL",
            ).use { statement ->
                statement.setString(1, BANK)
                statement.setString(2, complaintKey)
                statement.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getString(1)).isEqualTo(complaintKey)
                    assertThat(rows.next()).isFalse()
                }
            }
        }
    }

    @Test
    fun `legacy incident digest permits matching replay and rejects changed evidence`() {
        val incident = UUID.randomUUID()
        val root = "incident:$incident"
        val payload = """{"schemaVersion":1,"sourceVersion":1,"aggregateRevision":1,""" +
            """"eventType":"ICT_INCIDENT_STATUS_CHANGED","sourceService":"security-scanner",""" +
            """"occurredAt":"$TIME","incident":{"id":"$incident","severity":"P1_CRITICAL",""" +
            """"status":"OPEN","affectedServices":["payment-service"],"detectedAt":"$TIME",""" +
            """"updatedAt":"$TIME"}}"""
        val original = IncidentProjectionConsumer(sessions, mapper, clock, meters, BANK, 17, 5000, true)
        onVertx { original.consume(payload) }
        connection { db ->
            db.prepareStatement(
                "INSERT INTO context_projection_events " +
                    "(bank_scope, event_key, source_system, aggregate_ref, source_version, occurred_at, " +
                    "processed_at, content_digest) " +
                    "SELECT bank_scope, ?, source_system, aggregate_ref, source_version, occurred_at, " +
                    "processed_at, content_digest FROM context_projection_events " +
                    "WHERE bank_scope = ? AND projection_generation = 17 AND aggregate_ref = ?",
            ).use { statement ->
                statement.setString(1, "legacy:$incident")
                statement.setString(2, BANK)
                statement.setString(3, root)
                assertThat(statement.executeUpdate()).isEqualTo(1)
            }
            db.commit()
        }
        val matching = IncidentProjectionConsumer(sessions, mapper, clock, meters, BANK, 18, 5000, true)
        onVertx { matching.consume(payload) }
        assertThat(values("context_nodes", "node_key", "node_key", setOf(root), 18)).containsExactly(root)
        assertThat(values("context_edges", "to_key", "from_key", setOf(root), 18))
            .containsExactly("service:payment-service")
        val conflicting = IncidentProjectionConsumer(sessions, mapper, clock, meters, BANK, 19, 5000, true)
        assertThatThrownBy { onVertx { conflicting.consume(payload.replace("OPEN", "CLOSED")) } }
            .hasStackTraceContaining("conflicting legacy ICT incident revision replay")
        assertThat(values("context_projection_events", "event_key", "aggregate_ref", setOf(root), 19)).isEmpty()
        assertThat(values("context_nodes", "node_key", "node_key", setOf(root), 19)).isEmpty()
        assertThat(values("context_edges", "to_key", "from_key", setOf(root), 19)).isEmpty()
    }

    private fun assertOrmEdges(payment: UUID, expectedEdges: Set<String>) {
        val edges = onVertx {
            sessions.withTransaction { session, _ ->
                session.createNativeQuery("SELECT set_config('openbank.bank_scope', :bank, true)", String::class.java)
                    .setParameter("bank", BANK).singleResult.flatMap {
                        session.createQuery(
                            "FROM ContextEdgeEntity WHERE bankScope = :bank AND fromKey = :root " +
                                "AND projectionGeneration IN (7, 8)",
                            ContextEdgeEntity::class.java,
                        ).setParameter("bank", BANK).setParameter("root", "transaction:$payment").resultList
                    }
            }.awaitSuspending()
        }
        val expected = expectedEdges.filter { it.startsWith("transaction:$payment|") }
        assertThat(edges).hasSize(expected.size * 2)
        assertThat(edges.groupBy { it.projectionGeneration }.keys).containsExactlyInAnyOrder(7L, 8L)
        for (generation in listOf(7L, 8L)) {
            assertThat(
                edges.filter { it.projectionGeneration == generation }
                    .map { "${it.fromKey}|${it.toKey}|${it.relationType}" },
            ).containsExactlyInAnyOrderElementsOf(expected)
        }
    }

    @Test
    fun `migration preserves unknown ledger generation and existing edge identity`() {
        val schema = "generation_replay_" + UUID.randomUUID().toString().replace("-", "")
        val edgeId = UUID.randomUUID()
        val migration = requireNotNull(
            javaClass.getResourceAsStream("/db/migration/V19__generation_scoped_replay.sql"),
        ).bufferedReader().use { it.readText() }
        connection { db ->
            db.createStatement().use { statement ->
                statement.execute("CREATE SCHEMA $schema")
                statement.execute("SET LOCAL search_path TO $schema")
                statement.execute(
                    "CREATE TABLE context_projection_events (bank_scope text NOT NULL, event_key text NOT NULL, " +
                        "source_system text NOT NULL, aggregate_ref text NOT NULL, source_version bigint NOT NULL, " +
                        "content_digest text, PRIMARY KEY (bank_scope, event_key))",
                )
                statement.execute(
                    "CREATE UNIQUE INDEX idx_context_incident_revision_digest ON context_projection_events " +
                        "(bank_scope, aggregate_ref, source_version) " +
                        "WHERE source_system = 'security-scanner' AND content_digest IS NOT NULL",
                )
                statement.execute(
                    "CREATE TABLE context_edges (edge_id uuid PRIMARY KEY, bank_scope text NOT NULL, " +
                        "projection_generation bigint NOT NULL)",
                )
                statement.execute(
                    "INSERT INTO context_projection_events VALUES " +
                        "('openbank-cz', 'legacy-incident', 'security-scanner', 'incident:synthetic', 1, 'digest')",
                )
                statement.execute("INSERT INTO context_edges VALUES ('$edgeId', 'openbank-cz', 13)")
                migration.lineSequence().map { it.substringBefore("--") }.joinToString("\n")
                    .split(';').filter { it.isNotBlank() }.forEach { statement.execute(it) }
                statement.executeQuery(
                    "SELECT projection_generation, content_digest FROM context_projection_events",
                ).use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getObject(1)).isNull()
                    assertThat(rows.getString(2)).isEqualTo("digest")
                    assertThat(rows.next()).isFalse()
                }
                statement.executeQuery("SELECT edge_id, projection_generation FROM context_edges").use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getObject(1, UUID::class.java)).isEqualTo(edgeId)
                    assertThat(rows.getLong(2)).isEqualTo(13)
                    assertThat(rows.next()).isFalse()
                }
                statement.execute("DROP SCHEMA $schema CASCADE")
            }
            db.commit()
        }
    }

    private fun values(
        table: String,
        expression: String,
        keyColumn: String,
        keys: Set<String>,
        generation: Long,
    ): List<String> = connection { db ->
        val placeholders = keys.joinToString(",") { "?" }
        db.prepareStatement(
            "SELECT $expression FROM $table WHERE bank_scope = ? AND projection_generation = ? " +
                "AND $keyColumn IN ($placeholders)",
        ).use { statement ->
            statement.setString(1, BANK)
            statement.setLong(2, generation)
            keys.forEachIndexed { index, key -> statement.setString(index + 3, key) }
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(rows.getString(1)) }
            }
        }
    }

    private fun <T> connection(action: (Connection) -> T): T = DriverManager.getConnection(
        ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String::class.java),
        ConfigProvider.getConfig().getValue("quarkus.datasource.username", String::class.java),
        ConfigProvider.getConfig().getValue("quarkus.datasource.password", String::class.java),
    ).use { db ->
        db.autoCommit = false
        db.prepareStatement("SELECT set_config('openbank.bank_scope', ?, true)").use { statement ->
            statement.setString(1, BANK)
            statement.executeQuery().close()
        }
        action(db)
    }

    private fun <T> onVertx(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }

    private companion object {
        const val BANK = "openbank-cz"
        val TIME: Instant = Instant.parse("2026-09-01T12:00:00Z")
    }
}
