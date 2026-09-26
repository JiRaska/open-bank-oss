// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.integration

import com.openbank.context.infrastructure.DomesticPaymentProjectionConsumer
import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.asUni
import io.vertx.core.Context
import io.vertx.core.Vertx
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@QuarkusTest
@TestProfile(ContextProjectionCancellationProfile::class)
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_context_it")],
)
@QuarkusTestResource(ContextMessagingTestResource::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Suppress("TooManyFunctions") // JDBC fixture and observation helpers keep one real-consumer regression readable.
class ContextProjectionCancellationIT {
    @Inject lateinit var payments: DomesticPaymentProjectionConsumer

    @Test
    // Observe an actual consumer after history writes and before deduplication.
    @Suppress("LongMethod", "NestedBlockDepth")
    fun `interrupted payment projection rolls back history and can be replayed completely`() {
        consume(UUID.randomUUID())
        val payment = UUID.randomUUID()
        jdbc().use { observer ->
            setScope(observer)
            jdbc().use { blocker ->
                blocker.autoCommit = false
                setScope(blocker)
                insertDedupBlocker(blocker, payment)
                val blockerPid = scalar(blocker, "SELECT pg_backend_pid()")
                val context = AtomicReference<Context>()
                val subscription = VertxContextSupport.subscribeAndAwait {
                    Uni.createFrom().item {
                        context.set(Vertx.currentContext())
                        CoroutineScope(Dispatchers.Unconfined).async { payments.consume(payload(payment)) }
                            .asUni().subscribe().with({ }, { })
                    }
                }
                fun cancel() {
                    val acknowledged = CompletableFuture<Void>()
                    context.get().runOnContext {
                        subscription.cancel()
                        acknowledged.complete(null)
                    }
                    acknowledged.get(5, TimeUnit.SECONDS)
                }
                try {
                    var waitingPid = 0
                    awaitCondition {
                        observer.prepareStatement(
                            "SELECT pid FROM pg_stat_activity WHERE wait_event_type = 'Lock' " +
                                "AND query LIKE '%INSERT INTO context_projection_events%' " +
                                "AND ? = ANY(pg_blocking_pids(pid))",
                        ).use { statement ->
                            statement.setInt(1, blockerPid)
                            statement.executeQuery().use { rows ->
                                if (rows.next()) waitingPid = rows.getInt(1)
                                waitingPid != 0
                            }
                        }
                    }
                    // The blocked dedup insert follows all node and edge history writes.
                    assertThat(
                        scalar(
                            observer,
                            "SELECT count(DISTINCT relation)::int FROM pg_locks WHERE pid = $waitingPid " +
                                "AND mode = 'RowExclusiveLock' AND relation IN " +
                                "('context_graph_event_revisions'::regclass,'context_graph_node_revisions'::regclass," +
                                "'context_graph_edge_events'::regclass,'context_graph_edge_revisions'::regclass)",
                        ),
                    ).isEqualTo(4)
                    cancel()
                    blocker.rollback()
                    awaitCondition {
                        scalar(
                            observer,
                            "SELECT count(*)::int FROM pg_stat_activity " +
                                "WHERE pid = $waitingPid AND xact_start IS NOT NULL",
                        ) == 0
                    }
                    assertProjection(observer, payment, committed = false)
                    consume(payment)
                    assertProjection(observer, payment, committed = true)
                    // Duplicate delivery must retain exactly the same complete projection.
                    consume(payment)
                    assertProjection(observer, payment, committed = true)
                } finally {
                    cancel()
                    blocker.rollback()
                }
            }
        }
    }

    private fun assertProjection(connection: Connection, payment: UUID, committed: Boolean) {
        val event = "domestic-payment:$payment:1"
        val filters = listOf(
            Triple("context_graph_event_revisions", "evidence_ref = ?", 1),
            Triple("context_graph_node_revisions", "evidence_ref = ?", 2),
            Triple("context_graph_edge_events", "evidence_ref = ?", 1),
            Triple("context_graph_edge_revisions", "evidence_ref = ?", 1),
            Triple("context_projection_events", "event_key = ?", 1),
            Triple("context_nodes", "node_key IN (?,?)", 2),
            Triple("context_edges", "evidence_ref = ?", 1),
        )
        for ((table, filter, expected) in filters) {
            connection.prepareStatement(
                "SELECT count(*)::int FROM $table WHERE bank_scope = ? AND projection_generation = ? AND $filter",
            ).use { statement ->
                statement.setString(1, bank())
                statement.setLong(2, generation())
                if (table == "context_nodes") {
                    statement.setString(3, "transaction:$payment")
                    statement.setString(4, "payment-stage:domestic:$payment:1")
                } else {
                    statement.setString(3, event)
                }
                statement.executeQuery().use { rows ->
                    rows.next()
                    assertThat(rows.getInt(1)).`as`("$table must belong to one atomic projection")
                        .isEqualTo(if (committed) expected else 0)
                }
            }
        }
    }

    private fun insertDedupBlocker(connection: Connection, payment: UUID) {
        connection.prepareStatement(
            "INSERT INTO context_projection_events (bank_scope,projection_generation,event_key,source_system," +
                "aggregate_ref,source_version,occurred_at,processed_at) VALUES (?,?,?,'domestic-payment',?,1,?,?)",
        ).use { statement ->
            statement.setString(1, bank())
            statement.setLong(2, generation())
            statement.setString(3, "domestic-payment:$payment:1")
            statement.setString(4, "transaction:$payment")
            statement.setTimestamp(5, Timestamp.from(OCCURRED_AT))
            statement.setTimestamp(6, Timestamp.from(OCCURRED_AT))
            statement.executeUpdate()
        }
    }

    private fun consume(payment: UUID) = VertxContextSupport.subscribeAndAwait<Unit> {
        CoroutineScope(Dispatchers.Unconfined).async { payments.consume(payload(payment)) }.asUni()
            .ifNoItem().after(Duration.ofSeconds(5)).fail()
    }

    private fun payload(payment: UUID): String =
        """{"eventType":"DOMESTIC_PAYMENT_CREATED","sourceService":"domestic-payment","paymentId":"$payment",""" +
            """"aggregateRevision":1,"status":"RECEIVED","occurredAt":"$OCCURRED_AT"}"""

    private fun scalar(connection: Connection, sql: String): Int = connection.createStatement().use { statement ->
        statement.queryTimeout = 5
        statement.executeQuery(sql).use { rows ->
            rows.next()
            rows.getInt(1)
        }
    }

    private fun setScope(connection: Connection) {
        connection.prepareStatement("SELECT set_config('openbank.bank_scope', ?, ?)").use { statement ->
            statement.setString(1, bank())
            statement.setBoolean(2, !connection.autoCommit)
            statement.executeQuery().close()
        }
    }

    private fun jdbc(): Connection = ConfigProvider.getConfig().let { config ->
        DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        )
    }

    private fun bank(): String = ConfigProvider.getConfig().getValue("openbank.context.bank-scope", String::class.java)
    private fun generation(): Long =
        ConfigProvider.getConfig().getValue("openbank.context.projection-generation", Long::class.java)

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertThat(condition()).`as`("database condition must complete within five seconds").isTrue()
    }

    private companion object {
        val OCCURRED_AT: Instant = Instant.parse("2026-09-26T00:00:00Z")
    }
}

class ContextProjectionCancellationProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "quarkus.datasource.reactive.max-size" to "2",
        "quarkus.scheduler.enabled" to "false",
        "openbank.context.query-timeout-ms" to "5000",
    )
}
