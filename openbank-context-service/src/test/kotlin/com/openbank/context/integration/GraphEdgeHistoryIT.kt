// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.context.infrastructure.ContextSqlOperation
import com.openbank.context.infrastructure.GraphEdgeHistoryReader
import com.openbank.context.infrastructure.GraphEdgeHistoryWriter
import com.openbank.context.infrastructure.GraphEdgeObservation
import com.openbank.context.infrastructure.GraphEdgeRule
import com.openbank.libs.testing.containers.PostgresTestResource
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
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_context_complaint_revisions_it")],
)
@QuarkusTestResource(ContextMessagingTestResource::class)
class GraphEdgeHistoryIT {
    @Inject
    lateinit var sessions: Mutiny.SessionFactory

    @Inject
    lateinit var mapper: ObjectMapper

    @Test
    fun `eligible logical edges are deduplicated before the result bound`() {
        val root = "transaction:${UUID.randomUUID()}"
        val first = "booking-transaction:${UUID.randomUUID()}"
        val second = "booking-transaction:${UUID.randomUUID()}"
        for (version in 1L..20L) {
            append(root, "revision:$root:$version", listOf(observation(root, first, version)))
        }
        append(root, "second:$root", listOf(observation(root, second, 1)))
        val rejected = listOf(
            observation(root, "booking-transaction:${UUID.randomUUID()}", 1).copy(relation = "BOOKED_AS"),
            observation(root, "booking-transaction:${UUID.randomUUID()}", 1).copy(source = "untrusted-source"),
            observation(root, "account:${UUID.randomUUID()}", 1),
        )
        append(root, "excluded:$root", rejected)
        val selected = read(root)
        assertThat(selected.map { it.toKey }).containsExactlyInAnyOrder(first, second)
        val latest = selected.single { it.toKey == first }
        assertThat(latest.sourceVersion).isEqualTo(20)
        assertThat(latest.evidenceRef).isEqualTo("revision:$root:20")
        assertThat(latest.validFrom).isEqualTo(TIME.plusSeconds(20))
        val wideInput = listOf(root) + (1..150).map { "transaction:${UUID.randomUUID()}" }
        val wideResult = onVertx {
            GraphEdgeHistoryReader(sessions, mapper, BANK, GENERATION, 5000).find(
                wideInput,
                listOf(GraphEdgeRule("transaction-service", "booking-transaction:%", setOf("BOOKING_REQUESTED"))),
                TIME.plusSeconds(30),
                2,
            )
        }
        assertThat(wideResult.map { it.toKey }).containsExactlyInAnyOrder(first, second)
        assertThat(read(root, bank = "another-bank")).isEmpty()
        assertThat(read(root, generation = GENERATION + 1)).isEmpty()
    }

    @Test
    fun `exact replay is idempotent and changed complete edge sets roll back`() {
        val root = "transaction:${UUID.randomUUID()}"
        val event = "replay:$root"
        val observation = observation(root, "booking-transaction:${UUID.randomUUID()}", 1)
        append(root, event, listOf(observation))
        val before = counts(event)
        assertThat(before).isEqualTo(1L to 1L)
        append(root, event, listOf(observation))
        assertThat(counts(event)).isEqualTo(before)
        assertThatThrownBy { append(root, event, emptyList()) }
            .hasStackTraceContaining("conflicting graph edge event")
        assertThat(counts(event)).isEqualTo(before)
        assertThat(read(root).map { it.toKey }).containsExactly(observation.to)
    }

    @Test
    fun `overflow selects newest relationships and presents them chronologically`() {
        val root = "transaction:${UUID.randomUUID()}"
        val targets = (1..3).map { "booking-transaction:${UUID.randomUUID()}" }
        targets.forEachIndexed { index, target ->
            append(root, "overflow:$root:$index", listOf(observation(root, target, index.toLong() + 1)))
        }
        val result = read(root)
        assertThat(result.map { it.toKey }).containsExactly(targets[1], targets[2])
        assertThat(result.map { it.sourceVersion }).containsExactly(2L, 3L)
    }

    @Test
    fun `legacy baseline remains eligible until retained observations cover the requested time`() {
        val root = "transaction:${UUID.randomUUID()}"
        val target = "booking-transaction:${UUID.randomUUID()}"
        seedBaseline(root, target)
        val laterEvent = "later:$root"
        append(root, laterEvent, listOf(observation(root, target, 2)))
        val baseline = read(root, asOf = TIME.plusSeconds(1)).single()
        assertThat(baseline.evidenceRef).isEqualTo("legacy:$root")
        assertThat(baseline.sourceVersion).isEqualTo(1)
        val later = read(root, asOf = TIME.plusSeconds(2)).single()
        assertThat(later.evidenceRef).isEqualTo(laterEvent)
        assertThat(later.sourceVersion).isEqualTo(2)
        val priorEvent = "prior:$root"
        append(root, priorEvent, listOf(observation(root, target, 0)))
        val prior = read(root, asOf = TIME).single()
        assertThat(prior.evidenceRef).isEqualTo(priorEvent)
        assertThat(prior.sourceVersion).isZero()
        assertThat(prior.validFrom).isEqualTo(TIME)
        assertThat(read(root, asOf = TIME.plusSeconds(1)).single().evidenceRef).isEqualTo(priorEvent)
        assertThat(read(root, asOf = TIME.plusSeconds(2)).single().evidenceRef).isEqualTo(laterEvent)
    }

    @Test
    fun `incoming authoritative association is bounded historical bank and generation evidence`() {
        val booking = "booking-transaction:${UUID.randomUUID()}"
        val payment = "transaction:${UUID.randomUUID()}"
        seedBaseline(payment, booking)
        append(payment, "booking-later:$booking", listOf(observation(payment, booking, 2)))
        append(payment, "booking-prior:$booking", listOf(observation(payment, booking, 1)))
        val excluded = listOf(
            observation("transaction:${UUID.randomUUID()}", booking, 1).copy(source = "untrusted-source"),
            observation("account:${UUID.randomUUID()}", booking, 1),
            observation("transaction:${UUID.randomUUID()}", booking, 1).copy(relation = "BOOKED_AS"),
        )
        append(booking, "booking-excluded:$booking", excluded)
        fun incoming(
            bank: String = BANK,
            generation: Long = GENERATION,
            asOf: Instant = TIME.plusSeconds(2),
            limit: Int = 1,
        ) = onVertx {
            GraphEdgeHistoryReader(sessions, mapper, bank, generation, 5000).find(
                listOf(booking),
                listOf(GraphEdgeRule("transaction-service", "transaction:%", setOf("BOOKING_REQUESTED"))),
                asOf,
                limit,
                incoming = true,
            )
        }
        val prior = incoming(asOf = TIME.plusSeconds(1)).single()
        assertThat(prior.fromKey).isEqualTo(payment)
        assertThat(prior.toKey).isEqualTo(booking)
        assertThat(prior.sourceVersion).isEqualTo(1)
        assertThat(prior.evidenceRef).isEqualTo("booking-prior:$booking")
        assertThat(incoming().single().evidenceRef).isEqualTo("booking-later:$booking")
        assertThat(incoming(bank = "another-bank")).isEmpty()
        assertThat(incoming(generation = GENERATION + 1)).isEmpty()
        val secondPayment = "transaction:${UUID.randomUUID()}"
        append(secondPayment, "booking-second:$booking", listOf(observation(secondPayment, booking, 3)))
        val allEligible = incoming(asOf = TIME.plusSeconds(3), limit = 2)
        assertThat(allEligible.map { it.fromKey }).containsExactly(payment, secondPayment)
        val bounded = incoming(asOf = TIME.plusSeconds(3), limit = 1)
        assertThat(bounded.map { it.fromKey }).containsExactly(secondPayment)
        assertThat(bounded.single().evidenceRef).isEqualTo("booking-second:$booking")
    }

    private fun seedBaseline(root: String, target: String) {
        val config = ConfigProvider.getConfig()
        DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        ).use { db ->
            db.autoCommit = false
            db.prepareStatement("SELECT set_config('openbank.bank_scope', ?, true)").use { statement ->
                statement.setString(1, BANK)
                statement.executeQuery().close()
            }
            for (key in listOf(root, target)) {
                db.prepareStatement(
                    "INSERT INTO context_nodes (node_row_id, node_key, bank_scope, projection_generation, " +
                        "namespace, node_type, source_system, source_ref, display_label, classification, " +
                        "valid_from, recorded_at, source_version) " +
                        "VALUES (?, ?, ?, ?, 'COMPLAINT', 'PAYMENT', 'transaction-service', ?, " +
                        "'Synthetic reference', 'RESTRICTED', ?::timestamptz, ?::timestamptz, 1)",
                ).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setString(2, key)
                    statement.setString(3, BANK)
                    statement.setLong(4, GENERATION)
                    statement.setString(5, key)
                    statement.setString(6, TIME.toString())
                    statement.setString(7, TIME.toString())
                    statement.executeUpdate()
                }
            }
            db.prepareStatement(
                "INSERT INTO context_edges (edge_id, bank_scope, projection_generation, namespace, from_key, " +
                    "to_key, relation_type, source_system, evidence_ref, valid_from, recorded_at, source_version) " +
                    "VALUES (?, ?, ?, 'COMPLAINT', ?, ?, 'BOOKING_REQUESTED', 'transaction-service', ?, " +
                    "?::timestamptz, ?::timestamptz, 1)",
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setString(2, BANK)
                statement.setLong(3, GENERATION)
                statement.setString(4, root)
                statement.setString(5, target)
                statement.setString(6, "legacy:$root")
                statement.setString(7, TIME.plusSeconds(1).toString())
                statement.setString(8, TIME.toString())
                assertThat(statement.executeUpdate()).isEqualTo(1)
            }
            db.commit()
        }
    }

    private fun observation(root: String, target: String, version: Long) = GraphEdgeObservation(
        root,
        target,
        "BOOKING_REQUESTED",
        "transaction-service",
        TIME.plusSeconds(version),
        version,
    )

    private fun append(root: String, event: String, observations: List<GraphEdgeObservation>) = onVertx {
        ContextSqlOperation.execute(sessions, 5000) { operation ->
            GraphEdgeHistoryWriter.append(operation, BANK, GENERATION, event, root, observations, TIME)
        }.awaitSuspending()
    }

    private fun read(
        root: String,
        bank: String = BANK,
        generation: Long = GENERATION,
        asOf: Instant = TIME.plusSeconds(30),
    ) = onVertx {
        GraphEdgeHistoryReader(sessions, mapper, bank, generation, 5000).find(
            listOf(root),
            listOf(GraphEdgeRule("transaction-service", "booking-transaction:%", setOf("BOOKING_REQUESTED"))),
            asOf,
            2,
        )
    }

    private fun counts(event: String): Pair<Long, Long> = onVertx {
        sessions.withTransaction { session, _ ->
            session.createNativeQuery("SELECT set_config('openbank.bank_scope', :bank, true)", String::class.java)
                .setParameter("bank", BANK).singleResult.flatMap {
                    session.createNativeQuery(
                        "SELECT count(*) FROM context_graph_edge_events WHERE bank_scope = :bank " +
                            "AND projection_generation = :generation AND evidence_ref = :event",
                        Long::class.javaObjectType,
                    ).setParameter("bank", BANK).setParameter("generation", GENERATION)
                        .setParameter("event", event).singleResult.flatMap { events ->
                            session.createNativeQuery(
                                "SELECT count(*) FROM context_graph_edge_revisions WHERE bank_scope = :bank " +
                                    "AND projection_generation = :generation AND evidence_ref = :event",
                                Long::class.javaObjectType,
                            ).setParameter("bank", BANK).setParameter("generation", GENERATION)
                                .setParameter("event", event).singleResult.map { revisions -> events to revisions }
                        }
                }
        }.awaitSuspending()
    }

    private fun <T> onVertx(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }

    private companion object {
        const val BANK = "openbank-cz"
        const val GENERATION = 31L
        val TIME: Instant = Instant.parse("2026-09-01T12:00:00Z")
    }
}
