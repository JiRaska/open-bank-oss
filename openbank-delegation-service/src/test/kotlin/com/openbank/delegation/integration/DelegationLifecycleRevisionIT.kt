// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.delegation.integration

import com.openbank.delegation.application.port.out.DelegationConcurrentTransitionException
import com.openbank.delegation.application.port.out.DelegationRepository
import com.openbank.delegation.domain.event.DelegationActivated
import com.openbank.delegation.domain.event.DelegationRevoked
import com.openbank.delegation.domain.model.DelegationCapability
import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.DelegationResourceType
import com.openbank.delegation.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.vertx.core.runtime.context.VertxContextSafetyToggle
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import io.vertx.core.Vertx
import io.vertx.core.impl.ContextInternal
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real-Postgres proof for the lifecycle ordering boundary, including an old rolling writer. */
@QuarkusTest
@QuarkusTestResource(DelegationLifecycleRevisionIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(DelegationLifecycleRevisionIT.NoBackgroundWorkProfile::class)
class DelegationLifecycleRevisionIT {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> = InMemoryConnector.switchOutgoingChannelsToInMemory(
            "delegation-events-out",
            "spend-reservation-state-out",
        )

        override fun stop() = InMemoryConnector.clear()
    }

    class NoBackgroundWorkProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.outbox.dispatch-enabled" to "false",
            "quarkus.scheduler.enabled" to "false",
        )
    }

    @Inject
    lateinit var repository: DelegationRepository

    @Inject
    lateinit var vertx: Vertx

    private fun jdbcUrl(): String =
        ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String::class.java)

    private fun connect(): Connection = DriverManager.getConnection(jdbcUrl(), "openbank", "openbank_secret")

    @Test
    fun `deferred trigger stamps the committed revision when an old writer inserts outbox first`() {
        val grantId = UUID.randomUUID()
        seedOffered(grantId)

        connect().use { connection ->
            connection.autoCommit = false
            insertOutbox(connection, grantId, "DelegationActivated")
            connection.prepareStatement(
                "UPDATE delegation_grants SET status = 'ACTIVE', updated_at = NOW() WHERE id = ?",
            ).use { statement ->
                statement.setObject(1, grantId)
                assertThat(statement.executeUpdate()).isEqualTo(1)
            }
            connection.commit()
        }

        assertThat(revisionOf(grantId)).isEqualTo(1)
        assertThat(outboxRevision(grantId, "DelegationActivated"))
            .describedAs(
                "the deferred trigger must observe the grant UPDATE even though the old writer " +
                    "inserted its outbox row first",
            )
            .isEqualTo(1)
    }

    @Test
    fun `non-lifecycle outbox aggregate commits without a grant lookup or revision stamp`() {
        val reservationId = UUID.randomUUID()
        connect().use { connection ->
            connection.autoCommit = false
            insertOutbox(connection, reservationId, "DelegationSpendReserved")
            connection.commit()
        }

        assertThat(outboxCount(reservationId)).isEqualTo(1)
        assertThat(outboxHasRevision(reservationId, "DelegationSpendReserved")).isFalse()
    }

    @Test
    fun `two concurrent detached transitions produce one revision and one outbox event`() {
        warmLifecycleWritePath()
        val grantId = UUID.randomUUID()
        val base = seedOffered(grantId)
        val transitionAt = OffsetDateTime.of(2026, 9, 1, 12, 0, 0, 0, ZoneOffset.UTC)
        val accepted = base.accept(UUID.randomUUID(), transitionAt)
        val revoked = base.revoke(base.grantorPartyId, "concurrent revoke", transitionAt)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)

        try {
            val attempts = listOf(
                workers.submit<DelegationGrant> {
                    ready.countDown()
                    start.await()
                    onVertxContext {
                        repository.save(accepted, activatedEvent(accepted, transitionAt))
                    }
                },
                workers.submit<DelegationGrant> {
                    ready.countDown()
                    start.await()
                    onVertxContext {
                        repository.save(revoked, revokedEvent(revoked, transitionAt))
                    }
                },
            )
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue()
            start.countDown()

            val outcomes = attempts.map { attempt ->
                runCatching { attempt.get(CONCURRENT_ATTEMPT_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            }
            assertThat(outcomes.count { it.isSuccess }).isEqualTo(1)
            assertThat(outcomes.count { rootCause(it.exceptionOrNull()) is DelegationConcurrentTransitionException })
                .isEqualTo(1)
            assertThat(revisionOf(grantId)).isEqualTo(1)
            assertThat(outboxCount(grantId)).isEqualTo(1)
        } finally {
            workers.shutdownNow()
        }
    }

    @Test
    fun `a second stale terminal merge cannot overwrite closure evidence`() {
        val grantId = UUID.randomUUID()
        val firstActor = UUID.randomUUID()
        val secondActor = UUID.randomUUID()
        seedOffered(grantId)
        updateStatusAsOldWriter(grantId, "ACTIVE")
        closeAsOldWriter(grantId, firstActor, "first committed revoke")

        assertThatThrownBy {
            closeAsOldWriter(grantId, secondActor, "stale overwrite")
        }.isInstanceOf(java.sql.SQLException::class.java)

        val audit = closureAuditOf(grantId)
        assertThat(audit?.closedBy).isEqualTo(firstActor)
        assertThat(audit?.closedReason).isEqualTo("first committed revoke")
        assertThat(revisionOf(grantId)).isEqualTo(2)
    }

    @Test
    fun `an old writer cannot suspend the stale ACTIVE snapshot after revoke committed`() {
        val grantId = UUID.randomUUID()
        seedOffered(grantId)
        updateStatusAsOldWriter(grantId, "ACTIVE")
        updateStatusAsOldWriter(grantId, "REVOKED")

        assertThat(statusOf(grantId)).isEqualTo("REVOKED")
        assertThat(revisionOf(grantId)).isEqualTo(2)

        assertThatThrownBy {
            connect().use { connection ->
                connection.autoCommit = false
                try {
                    insertOutbox(connection, grantId, "DelegationSuspended")
                    connection.prepareStatement(
                        "UPDATE delegation_grants SET status = 'SUSPENDED', updated_at = NOW() WHERE id = ?",
                    ).use { statement ->
                        statement.setObject(1, grantId)
                        statement.executeUpdate()
                    }
                    connection.commit()
                } catch (failure: Exception) {
                    connection.rollback()
                    throw failure
                }
            }
        }.isInstanceOf(java.sql.SQLException::class.java)

        assertThat(statusOf(grantId)).isEqualTo("REVOKED")
        assertThat(revisionOf(grantId)).isEqualTo(2)
        assertThat(outboxRevision(grantId, "DelegationSuspended")).isNull()
    }

    @Test
    fun `an old writer cannot reinstate across a newer ACTIVE SUSPENDED cycle`() {
        val grantId = UUID.randomUUID()
        seedOffered(grantId)
        updateStatusAsOldWriter(grantId, "ACTIVE")
        updateStatusAsOldWriter(grantId, "SUSPENDED")

        // A revision-aware writer legitimately reinstates, then a newer fraud signal suspends the
        // grant again. The status has returned to the stale writer's source state, but the revision
        // proves it is a different suspension (the ABA shape).
        updateStatusWithRevision(grantId, "ACTIVE", 3)
        updateStatusWithRevision(grantId, "SUSPENDED", 4)

        assertThatThrownBy {
            updateStatusAsOldWriter(grantId, "ACTIVE")
        }.isInstanceOf(java.sql.SQLException::class.java)

        assertThat(statusOf(grantId)).isEqualTo("SUSPENDED")
        assertThat(revisionOf(grantId)).isEqualTo(4)
    }

    private fun seedOffered(id: UUID): DelegationGrant {
        val grantor = UUID.randomUUID()
        val grantee = UUID.randomUUID()
        val resource = UUID.randomUUID()
        val validFrom = OffsetDateTime.parse("2026-01-01T00:00:00Z")
        connect().use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO delegation_grants
                  (id, grantor_party_id, grantee_party_id, resource_type, resource_id,
                   approval_policy, valid_from, status, created_at, updated_at)
                VALUES (?, ?, ?, 'ACCOUNT', ?, 'SOLO', ?, 'OFFERED', NOW(), NOW())
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, id)
                statement.setObject(2, grantor)
                statement.setObject(3, grantee)
                statement.setObject(4, resource)
                statement.setObject(5, validFrom)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO delegation_capabilities (grant_id, capability) VALUES (?, ?)",
            ).use { statement ->
                statement.setObject(1, id)
                statement.setString(2, "ACCOUNT_READ_BALANCES")
                statement.executeUpdate()
            }
        }
        return DelegationGrant(
            id = id,
            grantorPartyId = grantor,
            granteePartyId = grantee,
            resourceType = DelegationResourceType.ACCOUNT,
            resourceId = resource,
            capabilities = setOf(DelegationCapability.ACCOUNT_READ_BALANCES),
            validFrom = validFrom,
            validTo = null,
            createdAt = validFrom,
            updatedAt = validFrom,
        )
    }

    private fun insertOutbox(connection: Connection, grantId: UUID, eventType: String) {
        connection.prepareStatement(
            """
            INSERT INTO delegation_outbox
                (id, event_id, aggregate_id, event_type, payload, status, created_at, updated_at)
            VALUES (nextval('delegation_outbox_seq'), ?, ?, ?, ?, 'PENDING', NOW(), NOW())
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, UUID.randomUUID())
            statement.setObject(2, grantId)
            statement.setString(3, eventType)
            statement.setString(
                4,
                """{"eventType":"$eventType","aggregateId":"$grantId"}""",
            )
            statement.executeUpdate()
        }
    }

    private fun activatedEvent(grant: DelegationGrant, at: OffsetDateTime) = DelegationActivated(
        aggregateId = grant.id,
        lifecycleRevision = grant.lifecycleRevision,
        grantorPartyId = grant.grantorPartyId,
        granteePartyId = grant.granteePartyId,
        resourceType = grant.resourceType,
        resourceId = grant.resourceId,
        capabilities = grant.capabilities,
        validFrom = grant.validFrom,
        validTo = grant.validTo,
        occurredAt = at.toInstant(),
    )

    private fun revokedEvent(grant: DelegationGrant, at: OffsetDateTime) = DelegationRevoked(
        aggregateId = grant.id,
        lifecycleRevision = grant.lifecycleRevision,
        grantorPartyId = grant.grantorPartyId,
        granteePartyId = grant.granteePartyId,
        resourceType = grant.resourceType,
        resourceId = grant.resourceId,
        capabilities = grant.capabilities,
        reason = "concurrent revoke",
        occurredAt = at.toInstant(),
    )

    private fun warmLifecycleWritePath() {
        val at = OffsetDateTime.parse("2026-09-01T11:00:00Z")
        val offered = seedOffered(UUID.randomUUID())
        val accepted = offered.accept(UUID.randomUUID(), at)
        onVertxContext {
            repository.save(accepted, activatedEvent(accepted, at))
        }
    }

    private fun updateStatusAsOldWriter(grantId: UUID, status: String) {
        connect().use { connection ->
            connection.prepareStatement(
                "UPDATE delegation_grants SET status = ?, updated_at = NOW() WHERE id = ?",
            ).use { statement ->
                statement.setString(1, status)
                statement.setObject(2, grantId)
                assertThat(statement.executeUpdate()).isEqualTo(1)
            }
        }
    }

    private fun updateStatusWithRevision(grantId: UUID, status: String, lifecycleRevision: Long) {
        connect().use { connection ->
            connection.prepareStatement(
                "UPDATE delegation_grants SET status = ?, lifecycle_revision = ?, updated_at = NOW() WHERE id = ?",
            ).use { statement ->
                statement.setString(1, status)
                statement.setLong(2, lifecycleRevision)
                statement.setObject(3, grantId)
                assertThat(statement.executeUpdate()).isEqualTo(1)
            }
        }
    }

    private fun closeAsOldWriter(grantId: UUID, actor: UUID, reason: String) {
        connect().use { connection ->
            connection.prepareStatement(
                """
                UPDATE delegation_grants
                   SET status = 'REVOKED', updated_at = NOW(), closed_at = NOW(),
                       closed_by = ?, closed_reason = ?
                 WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, actor)
                statement.setString(2, reason)
                statement.setObject(3, grantId)
                assertThat(statement.executeUpdate()).isEqualTo(1)
            }
        }
    }

    private fun closureAuditOf(grantId: UUID): ClosureAudit? = queryOne(
        "SELECT closed_by, closed_reason FROM delegation_grants WHERE id = ?",
        grantId,
    ) { ClosureAudit(it.getObject(1, UUID::class.java), it.getString(2)) }

    private fun statusOf(grantId: UUID): String? = queryOne(
        "SELECT status FROM delegation_grants WHERE id = ?",
        grantId,
    ) { it.getString(1) }

    private fun revisionOf(grantId: UUID): Long? = queryOne(
        "SELECT lifecycle_revision FROM delegation_grants WHERE id = ?",
        grantId,
    ) { it.getLong(1) }

    private fun outboxRevision(grantId: UUID, eventType: String): Long? = queryOne(
        """
        SELECT (payload::jsonb ->> 'lifecycleRevision')::bigint
          FROM delegation_outbox
         WHERE aggregate_id = ? AND event_type = ?
        """.trimIndent(),
        grantId,
        eventType,
    ) { it.getLong(1) }

    private fun outboxCount(grantId: UUID): Int = queryOne(
        "SELECT COUNT(*) FROM delegation_outbox WHERE aggregate_id = ?",
        grantId,
    ) { it.getInt(1) } ?: 0

    private fun outboxHasRevision(aggregateId: UUID, eventType: String): Boolean = queryOne(
        """
        SELECT jsonb_exists(payload::jsonb, 'lifecycleRevision')
          FROM delegation_outbox
         WHERE aggregate_id = ? AND event_type = ?
        """.trimIndent(),
        aggregateId,
        eventType,
    ) { it.getBoolean(1) } ?: false

    private fun <T> queryOne(sql: String, vararg args: Any, read: (ResultSet) -> T): T? {
        connect().use { connection ->
            connection.prepareStatement(sql).use { statement ->
                args.forEachIndexed { index, arg -> statement.setObject(index + 1, arg) }
                val result = statement.executeQuery()
                return if (result.next()) read(result) else null
            }
        }
    }

    private fun <T> onVertxContext(block: suspend () -> T): T {
        val future = CompletableFuture<T>()
        val duplicated = (vertx.orCreateContext as ContextInternal).duplicate()
        VertxContextSafetyToggle.setContextSafe(duplicated, true)
        val dispatcher = Executor { command -> duplicated.runOnContext { command.run() } }.asCoroutineDispatcher()
        CoroutineScope(dispatcher).launch {
            try {
                future.complete(block())
            } catch (t: Throwable) {
                future.completeExceptionally(t)
            }
        }
        return future.get(VERTX_OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun rootCause(throwable: Throwable?): Throwable? {
        var current = throwable
        while (current is ExecutionException && current.cause != null) current = current.cause
        return current
    }

    private data class ClosureAudit(val closedBy: UUID, val closedReason: String)

    private companion object {
        const val VERTX_OPERATION_TIMEOUT_SECONDS = 90L
        const val CONCURRENT_ATTEMPT_TIMEOUT_SECONDS = 120L
    }
}
