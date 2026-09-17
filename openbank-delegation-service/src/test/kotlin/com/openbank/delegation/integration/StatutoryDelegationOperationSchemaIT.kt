// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.integration

import com.openbank.delegation.application.port.out.StatutoryDelegationOperationRepository
import com.openbank.delegation.application.port.out.StatutoryOperationCreateOutcome
import com.openbank.delegation.domain.model.StatutoryDelegationOperation
import com.openbank.delegation.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.uni
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/** Real-Postgres proof for immutable, replay-safe JOINT proposal evidence; no grant is issued. */
@QuarkusTest
@QuarkusTestResource(StatutoryDelegationOperationSchemaIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class StatutoryDelegationOperationSchemaIT {
    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("delegation-events-out")

        override fun stop() = InMemoryConnector.clear()
    }

    @Inject lateinit var operations: StatutoryDelegationOperationRepository

    @Inject lateinit var dataSource: DataSource

    private fun <T> onVertxContext(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    @Test
    fun `request key replays the exact proposal but rejects a different operation or rule`() {
        val proposed = operation()
        assertThat(onVertxContext { operations.create(proposed) })
            .isInstanceOf(StatutoryOperationCreateOutcome.Created::class.java)

        val retry = proposed.copy(id = UUID.randomUUID(), createdAt = proposed.createdAt.plusSeconds(1))
        val replayed = onVertxContext { operations.create(retry) }
        assertThat(replayed).isInstanceOf(StatutoryOperationCreateOutcome.Replayed::class.java)
        assertThat(replayed.operation.id).isEqualTo(proposed.id)
        assertThat(onVertxContext { operations.find(proposed.id, proposed.principalPartyId) })
            .isEqualTo(proposed)
        assertThat(onVertxContext { operations.find(proposed.id, UUID.randomUUID()) }).isNull()

        val changedPayload = """{"resourceId":"${UUID.randomUUID()}"}"""
        assertThatThrownBy {
            onVertxContext {
                operations.create(
                    retry.copy(payloadJson = changedPayload, requestHash = sha256(changedPayload)),
                )
            }
        }.isInstanceOf(com.openbank.delegation.application.port.out.StatutoryOperationCreateConflict::class.java)
        assertThatThrownBy {
            onVertxContext { operations.create(retry.copy(policyRevision = proposed.policyRevision + 1)) }
        }.isInstanceOf(com.openbank.delegation.application.port.out.StatutoryOperationCreateConflict::class.java)

        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT count(*) FROM delegation_statutory_operations WHERE principal_party_id = ?",
            ).use { statement ->
                statement.setObject(1, proposed.principalPartyId)
                statement.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getInt(1)).isEqualTo(1)
                }
            }
            connection.prepareStatement("SELECT count(*) FROM delegation_grants WHERE grantor_party_id = ?")
                .use { statement ->
                    statement.setObject(1, proposed.principalPartyId)
                    statement.executeQuery().use { rows ->
                        assertThat(rows.next()).isTrue()
                        assertThat(rows.getInt(1)).isZero()
                    }
                }
        }
    }

    @Test
    fun `proposal evidence and SCA decisions cannot be rewritten or reused`() {
        val proposed = operation()
        onVertxContext { operations.create(proposed) }
        val actor = UUID.randomUUID()
        val sca = UUID.randomUUID()
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE delegation_statutory_operations SET request_hash = ? WHERE operation_id = ?",
            ).use { statement ->
                statement.setString(1, "f".repeat(64))
                statement.setObject(2, proposed.id)
                assertThatThrownBy { statement.executeUpdate() }
                    .isInstanceOf(SQLException::class.java)
                    .hasMessageContaining("immutable")
            }
            connection.prepareStatement(
                "INSERT INTO delegation_statutory_decisions " +
                    "(operation_id, actor_party_id, sca_session_id, decision, decided_at) " +
                    "VALUES (?, ?, ?, 'APPROVE', now())",
            ).use { statement ->
                statement.setObject(1, proposed.id)
                statement.setObject(2, actor)
                statement.setObject(3, sca)
                assertThat(statement.executeUpdate()).isEqualTo(1)
                statement.setObject(2, UUID.randomUUID())
                assertThatThrownBy { statement.executeUpdate() }.isInstanceOf(SQLException::class.java)
            }
            connection.prepareStatement(
                "DELETE FROM delegation_statutory_decisions WHERE operation_id = ?",
            ).use { statement ->
                statement.setObject(1, proposed.id)
                assertThatThrownBy { statement.executeUpdate() }
                    .isInstanceOf(SQLException::class.java)
                    .hasMessageContaining("immutable")
            }
        }
    }

    private fun operation(): StatutoryDelegationOperation {
        val principal = UUID.randomUUID()
        val policy = UUID.randomUUID()
        val payload = """{"principalPartyId":"$principal","resourceId":"${UUID.randomUUID()}"}"""
        val rule = """{"policyId":"$policy","requiredSignatures":2}"""
        val at = Instant.parse("2026-09-17T12:00:00Z")
        return StatutoryDelegationOperation(
            id = UUID.randomUUID(),
            principalPartyId = principal,
            initiatorPartyId = UUID.randomUUID(),
            requestKey = UUID.randomUUID().toString(),
            requestHash = sha256(payload),
            payloadJson = payload,
            policyId = policy,
            policyRevision = 1,
            sourceCaseId = UUID.randomUUID(),
            ruleHash = sha256(rule),
            ruleSnapshotJson = rule,
            createdAt = at,
            expiresAt = at.plusSeconds(86_400),
        )
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
