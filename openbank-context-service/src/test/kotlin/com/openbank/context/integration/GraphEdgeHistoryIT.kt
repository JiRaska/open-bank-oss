// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.integration

import com.fasterxml.jackson.databind.ObjectMapper
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
import org.hibernate.reactive.mutiny.Mutiny
import org.junit.jupiter.api.Test
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

    private fun observation(root: String, target: String, version: Long) = GraphEdgeObservation(
        root,
        target,
        "BOOKING_REQUESTED",
        "transaction-service",
        TIME.plusSeconds(version),
        version,
    )

    private fun append(root: String, event: String, observations: List<GraphEdgeObservation>) = onVertx {
        sessions.withTransaction { session, _ ->
            GraphEdgeHistoryWriter.append(session, BANK, GENERATION, event, root, observations, TIME)
        }.awaitSuspending()
    }

    private fun read(root: String, bank: String = BANK, generation: Long = GENERATION) = onVertx {
        GraphEdgeHistoryReader(sessions, mapper, bank, generation, 5000).find(
            listOf(root),
            listOf(GraphEdgeRule("transaction-service", "booking-transaction:%", setOf("BOOKING_REQUESTED"))),
            TIME.plusSeconds(30),
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
