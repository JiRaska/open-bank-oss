// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.integration

import com.openbank.context.infrastructure.DomesticPaymentProjectionConsumer
import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.asUni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_context_complaint_revisions_it")],
)
@QuarkusTestResource(ContextMessagingTestResource::class)
class GraphNodeHistoryPrivacyIT {
    @Inject
    lateinit var payments: DomesticPaymentProjectionConsumer

    @Test
    fun `graph revision history is bank scoped for a nonowner and remains append only`() {
        val paymentId = UUID.randomUUID()
        val evidenceRef = "domestic-payment:$paymentId:1"
        onVertx { payments.consume(paymentCreated(paymentId)) }

        val role = "graph_history_rls_${UUID.randomUUID().toString().replace("-", "")}" // SQL identifier only.
        connection().use { connection ->
            withRestrictedRole(connection, role) {
                assertRestrictedRole(connection)
                assertThat(visibleCount(connection, "context_graph_event_revisions", evidenceRef)).isZero()
                assertThat(visibleCount(connection, "context_graph_node_revisions", evidenceRef)).isZero()

                scope(connection, "another-bank")
                assertThat(visibleCount(connection, "context_graph_event_revisions", evidenceRef)).isZero()
                assertThat(visibleCount(connection, "context_graph_node_revisions", evidenceRef)).isZero()

                scope(connection, BANK)
                assertThat(visibleCount(connection, "context_graph_event_revisions", evidenceRef)).isEqualTo(1)
                assertThat(visibleCount(connection, "context_graph_node_revisions", evidenceRef)).isEqualTo(2)

                listOf("context_graph_event_revisions", "context_graph_node_revisions").forEach { table ->
                    assertMutationRejected(connection, table, evidenceRef, delete = false)
                    assertMutationRejected(connection, table, evidenceRef, delete = true)
                }
            }
        }
    }

    private fun withRestrictedRole(connection: Connection, role: String, assertions: () -> Unit) {
        connection.createStatement().use { statement ->
            statement.execute("CREATE ROLE $role NOLOGIN NOSUPERUSER NOBYPASSRLS")
            try {
                statement.execute("GRANT USAGE ON SCHEMA public TO $role")
                statement.execute(
                    "GRANT SELECT ON context_graph_event_revisions, context_graph_node_revisions TO $role",
                )
                statement.execute(
                    "GRANT UPDATE, DELETE ON context_graph_event_revisions, context_graph_node_revisions TO $role",
                )
                statement.execute("SET ROLE $role")
                assertions()
            } finally {
                if (!connection.autoCommit) connection.rollback()
                connection.autoCommit = true
                statement.execute("RESET ROLE")
                statement.execute(
                    "REVOKE SELECT, UPDATE, DELETE ON context_graph_event_revisions, context_graph_node_revisions FROM $role",
                )
                statement.execute("REVOKE USAGE ON SCHEMA public FROM $role")
                statement.execute("DROP ROLE $role")
            }
        }
    }

    private fun assertRestrictedRole(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname = current_user").use {
                assertThat(it.next()).isTrue()
                assertThat(it.getBoolean(1)).isFalse()
                assertThat(it.getBoolean(2)).isFalse()
            }
            listOf("context_graph_event_revisions", "context_graph_node_revisions").forEach { table ->
                statement.executeQuery(
                    "SELECT pg_get_userbyid(relowner) = current_user FROM pg_class WHERE oid = '$table'::regclass",
                ).use {
                    assertThat(it.next()).isTrue()
                    assertThat(it.getBoolean(1)).isFalse()
                }
            }
        }
    }

    private fun visibleCount(connection: Connection, table: String, evidenceRef: String): Int =
        connection.prepareStatement("SELECT count(*) FROM $table WHERE evidence_ref = ?").use { statement ->
            statement.setString(1, evidenceRef)
            statement.executeQuery().use { rows ->
                check(rows.next())
                rows.getInt(1)
            }
        }

    private fun assertMutationRejected(connection: Connection, table: String, evidenceRef: String, delete: Boolean) {
        if (!connection.autoCommit) connection.rollback()
        connection.autoCommit = false
        scope(connection, BANK)
        val sql = if (delete) {
            "DELETE FROM $table WHERE evidence_ref = ?"
        } else {
            "UPDATE $table SET recorded_at = recorded_at WHERE evidence_ref = ?"
        }
        assertThatThrownBy {
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, evidenceRef)
                statement.executeUpdate()
            }
        }.hasMessageContaining("append-only")
        connection.rollback()
    }

    private fun scope(connection: Connection, bank: String) {
        connection.autoCommit = false
        connection.prepareStatement("SELECT set_config('openbank.bank_scope', ?, true)").use {
            it.setString(1, bank)
            it.execute()
        }
    }

    private fun paymentCreated(id: UUID): String =
        """{"eventType":"DOMESTIC_PAYMENT_CREATED","sourceService":"domestic-payment","paymentId":"$id",""" +
            """"aggregateRevision":1,"status":"RECEIVED","occurredAt":"$OCCURRED_AT"}"""

    private fun <T> onVertx(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }

    private fun connection(): Connection = DriverManager.getConnection(
        ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String::class.java),
        ConfigProvider.getConfig().getValue("quarkus.datasource.username", String::class.java),
        ConfigProvider.getConfig().getValue("quarkus.datasource.password", String::class.java),
    )

    private companion object {
        const val BANK = "openbank-cz"
        val OCCURRED_AT: Instant = Instant.parse("2026-09-01T12:00:00Z")
    }
}
