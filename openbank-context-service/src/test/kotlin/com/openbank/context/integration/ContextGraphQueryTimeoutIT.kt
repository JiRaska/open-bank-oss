// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.integration

import com.openbank.context.domain.ContextNamespace
import com.openbank.context.infrastructure.ContextGraphRepository
import com.openbank.context.infrastructure.boundedGraphRead
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
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.config.ConfigProvider
import org.hibernate.reactive.mutiny.Mutiny
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_context_it")],
)
@QuarkusTestResource(ContextMessagingTestResource::class)
class ContextGraphQueryTimeoutIT {
    @Inject lateinit var sessions: Mutiny.SessionFactory

    @Inject lateinit var graph: ContextGraphRepository

    @Test
    fun `graph reads set a transaction local PostgreSQL statement timeout`() {
        val configured = ConfigProvider.getConfig().getValue("openbank.context.query-timeout-ms", Int::class.java)
        val actual = VertxContextSupport.subscribeAndAwait {
            CoroutineScope(Dispatchers.Unconfined).async {
                sessions.boundedGraphRead(configured) { session ->
                    session.createNativeQuery(
                        "select (extract(epoch from current_setting('statement_timeout')::interval) * 1000)::int",
                        Int::class.javaObjectType,
                    )
                        .singleResult
                }.awaitSuspending()
            }.asUni()
        }
        assertThat(actual).isEqualTo(configured)
    }

    @Test
    fun `graph rejects oversized caller budgets before database access`() {
        for ((nodes, edges) in listOf(101 to 200, 100 to 201, 0 to 200, 100 to 0)) {
            assertThatThrownBy {
                runBlocking {
                    graph.neighborhood(ContextNamespace.COMPLAINT, "complaint:bounded", Instant.now(), nodes, edges)
                }
            }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    @Suppress("LongMethod", "NestedBlockDepth") // Real-DB fixture and cleanup must bracket both assertions.
    fun `bounded root query merges both directions once and excludes expired evidence`() {
        val root = "complaint:${UUID.randomUUID()}"
        val target = "transaction:${UUID.randomUUID()}"
        val other = "transaction:${UUID.randomUUID()}"
        val asOf = Instant.now().minusSeconds(30)
        val newest = UUID.randomUUID()
        val outgoing = UUID.randomUUID()
        val incoming = UUID.randomUUID()
        val expired = UUID.randomUUID()
        val bank = ConfigProvider.getConfig().getValue("openbank.context.bank-scope", String::class.java)
        val generation = ConfigProvider.getConfig().getValue("openbank.context.projection-generation", Long::class.java)
        val config = ConfigProvider.getConfig()
        DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        ).use { connection ->
            try {
                for (key in listOf(root, target, other)) {
                    connection.prepareStatement(
                        """INSERT INTO context_nodes
                          (node_row_id,node_key,bank_scope,projection_generation,namespace,node_type,
                           source_system,source_ref,display_label,classification,valid_from,recorded_at,source_version)
                          VALUES (?,?,?,?,'COMPLAINT','Synthetic','test',?,?,'INTERNAL',?,?,1)""",
                    ).use { statement ->
                        statement.setObject(1, UUID.randomUUID())
                        statement.setString(2, key)
                        statement.setString(3, bank)
                        statement.setLong(4, generation)
                        statement.setString(5, key)
                        statement.setString(6, key)
                        statement.setTimestamp(7, Timestamp.from(asOf.minusSeconds(60)))
                        statement.setTimestamp(8, Timestamp.from(asOf.minusSeconds(60)))
                        statement.executeUpdate()
                    }
                }
                for (fixture in listOf(
                    EdgeFixture(newest, root, root, asOf.minusSeconds(1), null),
                    EdgeFixture(outgoing, root, target, asOf.minusSeconds(2), null),
                    EdgeFixture(incoming, other, root, asOf.minusSeconds(3), null),
                    EdgeFixture(expired, root, target, asOf.minusSeconds(4), asOf.minusSeconds(1)),
                )) {
                    connection.prepareStatement(
                        """INSERT INTO context_edges
                          (edge_id,bank_scope,projection_generation,namespace,from_key,to_key,relation_type,
                           source_system,evidence_ref,valid_from,valid_to,recorded_at,source_version)
                          VALUES (?,?,?,'COMPLAINT',?,?,'CONNECTED','test',?,?,?,?,1)""",
                    ).use { statement ->
                        statement.setObject(1, fixture.id)
                        statement.setString(2, bank)
                        statement.setLong(3, generation)
                        statement.setString(4, fixture.from)
                        statement.setString(5, fixture.to)
                        statement.setString(6, fixture.id.toString())
                        statement.setTimestamp(7, Timestamp.from(asOf.minusSeconds(60)))
                        statement.setTimestamp(8, fixture.validTo?.let(Timestamp::from))
                        statement.setTimestamp(9, Timestamp.from(fixture.recordedAt))
                        statement.executeUpdate()
                    }
                }

                val bounded = graphRead(root, asOf, 2)
                assertThat(bounded?.edges?.map { it.id }).containsExactly(newest.toString(), outgoing.toString())
                assertThat(bounded?.truncated).isTrue()
                val complete = graphRead(root, asOf, 3)
                assertThat(complete?.edges?.map { it.id })
                    .containsExactly(newest.toString(), outgoing.toString(), incoming.toString())
                assertThat(complete?.truncated).isFalse()
            } finally {
                connection.prepareStatement("DELETE FROM context_edges WHERE bank_scope = ? AND edge_id IN (?,?,?,?)")
                    .use { statement ->
                        statement.setString(1, bank)
                        listOf(newest, outgoing, incoming, expired).forEachIndexed { index, id ->
                            statement.setObject(index + 2, id)
                        }
                        statement.executeUpdate()
                    }
                connection.prepareStatement("DELETE FROM context_nodes WHERE bank_scope = ? AND node_key IN (?,?,?)")
                    .use { statement ->
                        statement.setString(1, bank)
                        listOf(root, target, other).forEachIndexed { index, key ->
                            statement.setString(index + 2, key)
                        }
                        statement.executeUpdate()
                    }
            }
        }
    }

    private fun graphRead(root: String, asOf: Instant, edges: Int) = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async {
            graph.neighborhood(ContextNamespace.COMPLAINT, root, asOf, 10, edges)
        }.asUni()
    }

    private data class EdgeFixture(
        val id: UUID,
        val from: String,
        val to: String,
        val recordedAt: Instant,
        val validTo: Instant?,
    )
}
