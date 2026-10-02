// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.context.domain.AuthorityEvidence
import com.openbank.context.infrastructure.AuthorityHistoryRepository
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
import org.hibernate.reactive.mutiny.Mutiny
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_context_it")],
)
@QuarkusTestResource(ContextMessagingTestResource::class)
class AuthorityHistoryRepositoryIT {
    @Inject lateinit var repository: AuthorityHistoryRepository

    @Inject lateinit var sessions: Mutiny.SessionFactory

    @Inject lateinit var mapper: ObjectMapper

    @Test
    fun `replay is idempotent and conflicting revision cannot replace original`() {
        val original = evidence(UUID.randomUUID(), 1)
        onVertx { repository.append(original) }
        onVertx { repository.append(original.copy()) }
        assertThatThrownBy {
            onVertx { repository.append(original.copy(eventType = "DelegationRevoked")) }
        }.hasStackTraceContaining("conflicting authority evidence revision")
        val history = onVertx { repository.history(original.delegationId, Instant.now(), repository.databaseNow()) }
        assertThat(history.observations.map { it.evidence }).containsExactly(original)
        assertThat(history.truncated).isFalse()
    }

    @Test
    fun `out of order evidence is retained in revision order with known time cutoff`() {
        val id = UUID.randomUUID()
        onVertx { repository.append(evidence(id, 2)) }
        val first = onVertx { repository.history(id, Instant.now(), repository.databaseNow()) }.observations.single()
        onVertx { repository.append(evidence(id, 1)) }
        val current = onVertx { repository.history(id, Instant.now(), repository.databaseNow()) }
        assertThat(current.observations.map { it.evidence.revision }).containsExactly(2L, 1L)
        val prior = onVertx { repository.history(id, Instant.now(), first.recordedAt) }
        assertThat(prior.observations.map { it.evidence.revision }).containsExactly(2L)
        val before = onVertx { repository.history(id, Instant.now(), first.recordedAt.minusMillis(1)) }
        assertThat(before.observations).isEmpty()
    }

    @Test
    fun `same delegation and revision in another bank remain isolated`() {
        val id = UUID.randomUUID()
        val otherBank = AuthorityHistoryRepository(sessions, mapper, "synthetic-bank-${UUID.randomUUID()}", 5000)
        val other = evidence(id, 1).copy(eventType = "DelegationRevoked")
        onVertx { otherBank.append(other) }
        assertThat(onVertx { repository.history(id, Instant.now(), repository.databaseNow()) }.observations).isEmpty()
        val local = evidence(id, 1)
        onVertx { repository.append(local) }
        assertThat(
            onVertx {
                repository.history(id, Instant.now(), repository.databaseNow())
            }.observations.map { it.evidence },
        )
            .containsExactly(local)
        assertThat(
            onVertx {
                otherBank.history(id, Instant.now(), otherBank.databaseNow())
            }.observations.map { it.evidence },
        )
            .containsExactly(other)
    }

    @Test
    fun `history returns newest hundred observations and explicitly reports truncation`() {
        val id = UUID.randomUUID()
        onVertx { (1L..101L).forEach { repository.append(evidence(id, it)) } }
        val history = onVertx { repository.history(id, Instant.now(), repository.databaseNow()) }
        assertThat(history.truncated).isTrue()
        assertThat(history.observations).hasSize(100)
        assertThat(
            history.observations.map {
                it.evidence.revision
            },
        ).containsExactlyElementsOf((101L downTo 2L).toList())
    }

    @Test
    fun `database RLS denies unscoped reads and writes and clears bank scope after transactions`() {
        val id = UUID.randomUUID()
        val bankA = "rls-a-${UUID.randomUUID()}"
        val bankB = "rls-b-${UUID.randomUUID()}"
        listOf(bankA, bankB).forEach { bank ->
            onVertx { AuthorityHistoryRepository(sessions, mapper, bank, 5000).append(evidence(id, 1)) }
        }
        val role = "history_rls_${UUID.randomUUID().toString().replace("-", "")}" // Generated SQL identifier only.
        connection().use { connection ->
            withRestrictedRole(connection, role) {
                assertRestrictedRole(connection)
                assertTransactionIsolation(connection, id, bankA, bankB)
            }
        }
    }

    private fun withRestrictedRole(connection: Connection, role: String, assertions: () -> Unit) {
        connection.createStatement().use { statement ->
            statement.execute("CREATE ROLE $role NOLOGIN NOSUPERUSER NOBYPASSRLS")
            try {
                statement.execute("GRANT USAGE ON SCHEMA public TO $role")
                statement.execute("GRANT SELECT, INSERT ON context_authority_history TO $role")
                statement.execute("SET ROLE $role")
                assertions()
            } finally {
                if (!connection.autoCommit) connection.rollback()
                connection.autoCommit = true
                statement.execute("RESET ROLE")
                statement.execute("REVOKE SELECT, INSERT ON context_authority_history FROM $role")
                statement.execute("REVOKE USAGE ON SCHEMA public FROM $role")
                statement.execute("DROP ROLE $role")
            }
        }
    }

    private fun assertRestrictedRole(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname = current_user",
            ).use {
                assertThat(it.next()).isTrue()
                assertThat(it.getBoolean(1)).isFalse()
                assertThat(it.getBoolean(2)).isFalse()
            }
            statement.executeQuery(
                "SELECT pg_get_userbyid(relowner) = current_user FROM pg_class " +
                    "WHERE oid = 'context_authority_history'::regclass",
            ).use {
                assertThat(it.next()).isTrue()
                assertThat(it.getBoolean(1)).isFalse()
            }
        }
    }

    private fun assertTransactionIsolation(connection: Connection, id: UUID, bankA: String, bankB: String) {
        connection.autoCommit = false
        assertThat(visibleBanks(connection, id)).isEmpty()
        scope(connection, bankA)
        assertThat(visibleBanks(connection, id)).containsExactly(bankA)
        assertThatThrownBy { insertCrossBank(connection, id, bankB) }
            .hasMessageContaining("row-level security")
        connection.rollback()
        assertThat(visibleBanks(connection, id)).isEmpty()
        scope(connection, bankB)
        assertThat(visibleBanks(connection, id)).containsExactly(bankB)
        connection.commit()
        assertThat(visibleBanks(connection, id)).isEmpty()
    }

    private fun insertCrossBank(connection: Connection, id: UUID, bank: String) {
        connection.prepareStatement(
            "INSERT INTO context_authority_history " +
                "(bank_scope, delegation_id, revision, event_type, observation_id, evidence, " +
                "occurred_at, evidence_ref, content_hash) " +
                "SELECT ?, delegation_id, 2, event_type, ?, evidence, occurred_at, evidence_ref, content_hash " +
                "FROM context_authority_history WHERE delegation_id = ?",
        ).use {
            it.setString(1, bank)
            it.setObject(2, UUID.randomUUID())
            it.setObject(3, id)
            it.executeUpdate()
        }
    }

    private fun visibleBanks(connection: Connection, id: UUID): List<String> = connection.prepareStatement(
        "SELECT bank_scope FROM context_authority_history WHERE delegation_id = ?",
    ).use { statement ->
        statement.setObject(1, id)
        statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
    }

    private fun scope(connection: Connection, bank: String) {
        connection.prepareStatement("SELECT set_config('openbank.bank_scope', ?, true)").use {
            it.setString(1, bank)
            it.execute()
        }
    }

    private fun connection(): Connection = DriverManager.getConnection(
        ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String::class.java),
        ConfigProvider.getConfig().getValue("quarkus.datasource.username", String::class.java),
        ConfigProvider.getConfig().getValue("quarkus.datasource.password", String::class.java),
    )

    private fun evidence(id: UUID, revision: Long) = AuthorityEvidence(
        id, revision, "DelegationActivated",
        UUID.fromString("00000000-0000-4000-8000-000000000001"),
        UUID.fromString("00000000-0000-4000-8000-000000000002"),
        "ACCOUNT", UUID.fromString("00000000-0000-4000-8000-000000000003"),
        listOf("VIEW"), "SOLO", 1, Instant.parse("2026-01-01T00:00:00Z"), null,
        Instant.parse("2026-02-01T00:00:00Z"),
    )

    private fun <T> onVertx(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }
}
