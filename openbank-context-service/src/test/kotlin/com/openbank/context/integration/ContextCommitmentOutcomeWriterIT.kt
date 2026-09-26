// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.integration

import com.openbank.context.infrastructure.ContextCommitmentKind
import com.openbank.context.infrastructure.ContextCommitmentOutcome
import com.openbank.context.infrastructure.ContextCommitmentOutcomeStatus
import com.openbank.context.infrastructure.ContextCommitmentOutcomeWriter
import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
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
@TestProfile(ContextGraphTimeoutRecoveryProfile::class)
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_context_it")],
)
@QuarkusTestResource(ContextMessagingTestResource::class)
class ContextCommitmentOutcomeWriterIT {
    @Inject lateinit var writer: ContextCommitmentOutcomeWriter

    @Test
    fun `mixed outcomes finalize only current claims in both commitment tables`() {
        ContextCommitmentKind.entries.forEach { kind ->
            jdbc().use { connection ->
                scope(connection)
                val token = UUID.randomUUID()
                val sent = seed(connection, kind, token)
                val failed = seed(connection, kind, token)
                val renewed = seed(connection, kind, UUID.randomUUID())
                val otherBank = "synthetic-other-bank"
                scope(connection, otherBank)
                val foreign = seed(connection, kind, token, bankScope = otherBank)
                scope(connection)
                val sentAt = Instant.parse("2026-09-26T05:00:00Z")
                val failedAt = sentAt.plusSeconds(1)
                val count = persist(
                    kind,
                    listOf(
                        ContextCommitmentOutcome(sent, token, ContextCommitmentOutcomeStatus.SENT, sentAt),
                        ContextCommitmentOutcome(failed, token, ContextCommitmentOutcomeStatus.FAILED, failedAt),
                        ContextCommitmentOutcome(renewed, token, ContextCommitmentOutcomeStatus.SENT, sentAt),
                        ContextCommitmentOutcome(foreign, token, ContextCommitmentOutcomeStatus.SENT, sentAt),
                    ),
                )
                assertThat(count).isEqualTo(2)
                assertState(connection, kind, sent, "SENT", 1, sentAt, sentAt, false)
                assertState(connection, kind, failed, "FAILED", 1, failedAt, null, false)
                assertState(connection, kind, renewed, "DISPATCHING", 0, null, null, true)
                scope(connection, otherBank)
                assertState(connection, kind, foreign, "DISPATCHING", 0, null, null, true)
                scope(connection)
                assertThat(
                    persist(
                        kind,
                        listOf(
                            ContextCommitmentOutcome(sent, token, ContextCommitmentOutcomeStatus.SENT, sentAt),
                        ),
                    ),
                ).isZero()
            }
        }
    }

    @Test
    fun `database failure rolls back every outcome in each table`() {
        ContextCommitmentKind.entries.forEach { kind ->
            jdbc().use { connection ->
                scope(connection)
                val token = UUID.randomUUID()
                val first = seed(connection, kind, token)
                val overflow = seed(connection, kind, token, Int.MAX_VALUE)
                val at = Instant.parse("2026-09-26T05:00:00Z")
                assertThatThrownBy {
                    persist(
                        kind,
                        listOf(
                            ContextCommitmentOutcome(first, token, ContextCommitmentOutcomeStatus.SENT, at),
                            ContextCommitmentOutcome(overflow, token, ContextCommitmentOutcomeStatus.FAILED, at),
                        ),
                    )
                }.hasStackTraceContaining("integer out of range")
                assertState(connection, kind, first, "DISPATCHING", 0, null, null, true)
                assertState(connection, kind, overflow, "DISPATCHING", Int.MAX_VALUE, null, null, true)
                // A valid outcome can still commit after the failed transaction.
                assertThat(
                    persist(
                        kind,
                        listOf(
                            ContextCommitmentOutcome(first, token, ContextCommitmentOutcomeStatus.SENT, at),
                        ),
                    ),
                ).isEqualTo(1)
            }
        }
    }

    private fun persist(kind: ContextCommitmentKind, outcomes: List<ContextCommitmentOutcome>): Int =
        VertxContextSupport.subscribeAndAwait {
            CoroutineScope(Dispatchers.Unconfined).async { writer.persist(kind, outcomes) }.asUni()
        }

    private fun seed(
        connection: Connection,
        kind: ContextCommitmentKind,
        token: UUID,
        attempts: Int = 0,
        bankScope: String = bank,
    ): UUID {
        val auditId = UUID.randomUUID()
        connection.prepareStatement(
            "INSERT INTO context_read_audit " +
                "(audit_id,bank_scope,principal_id,case_id,purpose,action,root_ref,decision,reason_code,occurred_at) " +
                "VALUES (?,?,'synthetic','synthetic','PAYMENT_COMPLAINT','context.complaint.read'," +
                "'complaint:synthetic','ALLOWED','POLICY_ALLOWED',now())",
        ).use { statement ->
            statement.setObject(1, auditId)
            statement.setString(2, bankScope)
            statement.executeUpdate()
        }
        val id = if (kind == ContextCommitmentKind.AUDIT) auditId else UUID.randomUUID()
        if (kind == ContextCommitmentKind.DISCLOSURE) {
            connection.prepareStatement(
                "INSERT INTO context_disclosure_audit " +
                    "(disclosure_id,decision_audit_id,bank_scope,query_hash,evidence_refs_json," +
                    "evidence_count,truncated,occurred_at) VALUES (?,?,?,?,'[]',0,false,now())",
            ).use { statement ->
                statement.setObject(1, id)
                statement.setObject(2, auditId)
                statement.setString(3, bankScope)
                statement.setString(4, "a".repeat(64))
                statement.executeUpdate()
            }
        }
        val prefix = prefix(kind)
        connection.prepareStatement(
            "INSERT INTO context_${prefix}_commitment_outbox " +
                "(${prefix}_id,bank_scope,commitment,occurred_at,status,claim_token,claimed_at,attempt_count) " +
                "VALUES (?,?,?,now(),'DISPATCHING',?,now(),?)",
        ).use { statement ->
            statement.setObject(1, id)
            statement.setString(2, bankScope)
            statement.setString(3, "a".repeat(64))
            statement.setObject(4, token)
            statement.setInt(5, attempts)
            statement.executeUpdate()
        }
        return id
    }

    @Suppress("LongParameterList")
    private fun assertState(
        connection: Connection,
        kind: ContextCommitmentKind,
        id: UUID,
        status: String,
        attempts: Int,
        updatedAt: Instant?,
        sentAt: Instant?,
        tokenPresent: Boolean,
    ) {
        val prefix = prefix(kind)
        connection.prepareStatement(
            "SELECT status,attempt_count,updated_at,sent_at,claim_token " +
                "FROM context_${prefix}_commitment_outbox WHERE ${prefix}_id = ?",
        ).use { statement ->
            statement.setObject(1, id)
            statement.executeQuery().use { rows ->
                assertThat(rows.next()).isTrue()
                assertThat(rows.getString(1)).isEqualTo(status)
                assertThat(rows.getInt(2)).isEqualTo(attempts)
                if (updatedAt != null) assertThat(rows.getTimestamp(3).toInstant()).isEqualTo(updatedAt)
                assertThat(rows.getTimestamp(4)?.toInstant()).isEqualTo(sentAt)
                assertThat(rows.getObject(5) != null).isEqualTo(tokenPresent)
            }
        }
    }

    private fun prefix(kind: ContextCommitmentKind): String = when (kind) {
        ContextCommitmentKind.AUDIT -> "audit"
        ContextCommitmentKind.DISCLOSURE -> "disclosure"
    }

    private val bank: String get() = ConfigProvider.getConfig().getValue(
        "openbank.context.bank-scope",
        String::class.java,
    )

    private fun scope(connection: Connection, bankScope: String = bank) {
        connection.prepareStatement("SELECT set_config('openbank.bank_scope', ?, false)").use { statement ->
            statement.setString(1, bankScope)
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
}
