// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.integration

import com.openbank.context.application.ContextReadAudit
import com.openbank.context.application.ContextReadAuditPort
import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
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
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@QuarkusTest
@TestProfile(ContextGraphTimeoutRecoveryProfile::class)
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_context_it")],
)
@QuarkusTestResource(ContextMessagingTestResource::class)
class ContextAuditCancellationIT {
    @Inject lateinit var audit: ContextReadAuditPort

    @Test
    @Suppress("LongMethod", "NestedBlockDepth") // Real lock observation brackets cancellation and rollback.
    fun `cancelled audit rolls back audit and commitment while uncancelled writes recover`() {
        jdbc().use { observer ->
            bankScope(observer)
            // Warm the real write path and prove its uncancelled audit/outbox control.
            recordControl(observer)
            jdbc().use { blocker ->
                blocker.autoCommit = false
                bankScope(blocker)
                val entry = entry()
                insertBlocker(blocker, entry)
                val blockerPid = blocker.createStatement().use { statement ->
                    statement.executeQuery("select pg_backend_pid()").use { rows ->
                        rows.next()
                        rows.getInt(1)
                    }
                }
                val context = AtomicReference<Context>()
                val subscription = VertxContextSupport.subscribeAndAwait {
                    Uni.createFrom().item {
                        context.set(Vertx.currentContext())
                        CoroutineScope(Dispatchers.Unconfined).async { audit.record(entry) }
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
                                "AND ? = ANY(pg_blocking_pids(pid))",
                        ).use { statement ->
                            statement.setInt(1, blockerPid)
                            statement.executeQuery().use { rows ->
                                if (rows.next()) waitingPid = rows.getInt(1)
                                waitingPid != 0
                            }
                        }
                    }
                    cancel()
                    blocker.rollback()
                    awaitCondition {
                        observer.prepareStatement(
                            "SELECT NOT EXISTS (SELECT 1 FROM pg_stat_activity WHERE pid = ? AND xact_start IS NOT NULL)",
                        ).use { statement ->
                            statement.setInt(1, waitingPid)
                            statement.executeQuery().use { rows ->
                                rows.next()
                                rows.getBoolean(1)
                            }
                        }
                    }
                    assertRows(observer, entry.id, 0)
                    // More successful writes than the two pool slots establish recovery.
                    repeat(3) { recordControl(observer) }
                } finally {
                    cancel()
                    blocker.rollback()
                }
            }
        }
    }

    private fun recordControl(observer: Connection) {
        val entry = entry()
        VertxContextSupport.subscribeAndAwait<Unit> {
            CoroutineScope(Dispatchers.Unconfined).async { audit.record(entry) }.asUni()
                .ifNoItem().after(java.time.Duration.ofSeconds(5)).fail()
        }
        assertRows(observer, entry.id, 1)
        // Append-only evidence remains in the isolated test database; no trigger bypass cleanup.
    }

    private fun assertRows(connection: Connection, id: UUID, expected: Int) {
        for (table in listOf("context_read_audit", "context_audit_commitment_outbox")) {
            connection.prepareStatement("SELECT count(*) FROM $table WHERE audit_id = ?").use { statement ->
                statement.setObject(1, id)
                statement.executeQuery().use { rows ->
                    rows.next()
                    assertThat(rows.getInt(1)).isEqualTo(expected)
                }
            }
        }
    }

    private fun insertBlocker(connection: Connection, entry: ContextReadAudit) {
        connection.prepareStatement(
            "INSERT INTO context_read_audit (audit_id,bank_scope,principal_id,case_id,purpose,action," +
                "root_ref,decision,policy_version,reason_code,occurred_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
        ).use { statement ->
            statement.setObject(1, entry.id)
            statement.setString(2, bank)
            statement.setString(3, entry.principalId)
            statement.setString(4, entry.caseId)
            statement.setString(5, entry.purpose)
            statement.setString(6, entry.action)
            statement.setString(7, entry.rootRef)
            statement.setString(8, entry.decision)
            statement.setString(9, entry.policyVersion)
            statement.setString(10, entry.reasonCode)
            statement.setTimestamp(11, Timestamp.from(entry.occurredAt))
            statement.executeUpdate()
        }
    }

    private fun bankScope(connection: Connection) {
        connection.prepareStatement("SELECT set_config('openbank.bank_scope', ?, ?)").use { statement ->
            statement.setString(1, bank)
            statement.setBoolean(2, !connection.autoCommit)
            statement.executeQuery().close()
        }
    }

    private val bank: String
        get() = ConfigProvider.getConfig().getValue("openbank.context.bank-scope", String::class.java)

    private fun jdbc(): Connection = ConfigProvider.getConfig().let { config ->
        DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        )
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertThat(condition()).`as`("database condition must complete within five seconds").isTrue()
    }

    private fun entry() = ContextReadAudit(
        principalId = "synthetic-investigator",
        caseId = "synthetic-case-${UUID.randomUUID()}",
        purpose = "PAYMENT_COMPLAINT",
        action = "context.complaint.read",
        rootRef = "complaint:${UUID.randomUUID()}",
        decision = "ALLOWED",
        policyVersion = "synthetic-policy-v1",
        reasonCode = "POLICY_ALLOWED",
        occurredAt = Instant.now(),
    )
}
