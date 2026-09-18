// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.delegation.application.port.out.DelegationRepository
import com.openbank.delegation.application.port.out.StatutoryDecisionClosed
import com.openbank.delegation.application.port.out.StatutoryDelegationOperationRepository
import com.openbank.delegation.application.port.out.StatutoryOperationCreateOutcome
import com.openbank.delegation.domain.event.DelegationActivated
import com.openbank.delegation.domain.event.DelegationOffered
import com.openbank.delegation.domain.event.StatutoryDelegationProposalCancelled
import com.openbank.delegation.domain.event.StatutoryDelegationProposalOpened
import com.openbank.delegation.domain.model.DelegationCapability
import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.DelegationResourceType
import com.openbank.delegation.domain.model.DelegationStatus
import com.openbank.delegation.domain.model.StatutoryDecisionVerdict
import com.openbank.delegation.domain.model.StatutoryDelegationDecision
import com.openbank.delegation.domain.model.StatutoryDelegationOperation
import com.openbank.delegation.domain.model.StatutoryOperationKind
import com.openbank.delegation.domain.model.StatutoryOperationState
import com.openbank.delegation.domain.model.StatutoryRepresentationRule
import com.openbank.delegation.domain.model.StatutoryRepresentative
import com.openbank.delegation.domain.model.StatutoryRuleMode
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
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/** Real-Postgres proof for immutable, replay-safe JOINT proposal evidence; no grant is issued. */
@QuarkusTest
@QuarkusTestResource(StatutoryDelegationOperationSchemaIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
@Suppress("LargeClass") // One real-Postgres fixture checks the shared immutable operation ledger for both JOINT kinds.
class StatutoryDelegationOperationSchemaIT {
    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("delegation-events-out")

        override fun stop() = InMemoryConnector.clear()
    }

    @Inject lateinit var operations: StatutoryDelegationOperationRepository

    @Inject lateinit var grants: DelegationRepository

    @Inject lateinit var dataSource: DataSource

    @Inject lateinit var mapper: ObjectMapper

    private fun <T> onVertxContext(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    private suspend fun createOperation(operation: StatutoryDelegationOperation): StatutoryOperationCreateOutcome {
        val recipients = mapper.readTree(operation.ruleSnapshotJson).path("eligibleRepresentatives")
            .map { UUID.fromString(it.path("partyId").asText()) }.sortedBy(UUID::toString)
        return operations.create(
            operation,
            StatutoryDelegationProposalOpened(
                aggregateId = operation.id,
                principalPartyId = operation.principalPartyId,
                actorId = operation.initiatorPartyId,
                operationKind = operation.operationKind,
                representativePartyIds = recipients,
                requestHash = operation.requestHash,
                ruleHash = operation.ruleHash,
                expiresAt = operation.expiresAt,
                occurredAt = operation.createdAt,
            ),
        )
    }

    @Test
    fun `request key replays the exact proposal but rejects a different operation or rule`() {
        val proposed = operation()
        assertThat(onVertxContext { createOperation(proposed) })
            .isInstanceOf(StatutoryOperationCreateOutcome.Created::class.java)

        val retry = proposed.copy(id = UUID.randomUUID(), createdAt = proposed.createdAt.plusSeconds(1))
        val replayed = onVertxContext { createOperation(retry) }
        assertThat(replayed).isInstanceOf(StatutoryOperationCreateOutcome.Replayed::class.java)
        assertThat(replayed.operation.id).isEqualTo(proposed.id)
        assertThat(rowCount("delegation_outbox", "aggregate_id", proposed.id)).isEqualTo(1)
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT event_type, payload FROM delegation_outbox WHERE aggregate_id = ?",
            ).use { statement ->
                statement.setObject(1, proposed.id)
                statement.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getString("event_type")).isEqualTo("StatutoryDelegationProposalOpened")
                    assertThat(rows.getString("payload")).contains(proposed.initiatorPartyId.toString())
                    assertThat(rows.next()).isFalse()
                }
            }
        }
        assertThat(onVertxContext { operations.find(proposed.id, proposed.principalPartyId) })
            .isEqualTo(proposed)
        assertThat(onVertxContext { operations.find(proposed.id, UUID.randomUUID()) }).isNull()
        val changedPayload = """{"resourceId":"${UUID.randomUUID()}"}"""
        assertThatThrownBy {
            onVertxContext {
                createOperation(
                    retry.copy(payloadJson = changedPayload, requestHash = sha256(changedPayload)),
                )
            }
        }.isInstanceOf(com.openbank.delegation.application.port.out.StatutoryOperationCreateConflict::class.java)
        assertThatThrownBy {
            onVertxContext { createOperation(retry.copy(policyRevision = proposed.policyRevision + 1)) }
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
    @Suppress("LongMethod") // One race scenario covers authorization, replay, outbox and evidence.
    fun `only the initiator cancels an inert proposal and exact retry preserves decisions`() {
        val proposed = operation()
        onVertxContext { createOperation(proposed) }
        val at = proposed.createdAt.plusSeconds(2)
        val event = StatutoryDelegationProposalCancelled(
            aggregateId = proposed.id,
            principalPartyId = proposed.principalPartyId,
            actorId = proposed.initiatorPartyId,
            operationKind = StatutoryOperationKind.ISSUE,
            requestHash = proposed.requestHash,
            ruleHash = proposed.ruleHash,
            targetGrantId = null,
            occurredAt = at,
        )
        assertThatThrownBy {
            onVertxContext {
                operations.cancel(
                    proposed.id,
                    proposed.principalPartyId,
                    UUID.randomUUID(),
                    StatutoryOperationKind.ISSUE,
                    at,
                    event,
                )
            }
        }.isInstanceOf(StatutoryDecisionClosed::class.java)
        assertThatThrownBy {
            onVertxContext {
                operations.cancel(
                    proposed.id,
                    UUID.randomUUID(),
                    proposed.initiatorPartyId,
                    StatutoryOperationKind.ISSUE,
                    at,
                    event,
                )
            }
        }.isInstanceOf(StatutoryDecisionClosed::class.java)
        val cancelled = onVertxContext {
            operations.cancel(
                proposed.id,
                proposed.principalPartyId,
                proposed.initiatorPartyId,
                StatutoryOperationKind.ISSUE,
                at,
                event,
            )
        }
        assertThat(cancelled.state).isEqualTo(StatutoryOperationState.CANCELLED)
        assertThat(
            onVertxContext {
                operations.cancel(
                    proposed.id,
                    proposed.principalPartyId,
                    proposed.initiatorPartyId,
                    StatutoryOperationKind.ISSUE,
                    at,
                    event,
                )
            },
        ).isEqualTo(cancelled)
        assertThat(rowCount("delegation_outbox", "aggregate_id", proposed.id)).isEqualTo(2)
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT event_type, payload FROM delegation_outbox WHERE aggregate_id = ? " +
                    "AND event_type = 'StatutoryDelegationProposalCancelled'",
            ).use { statement ->
                statement.setObject(1, proposed.id)
                statement.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getString("event_type")).isEqualTo("StatutoryDelegationProposalCancelled")
                    assertThat(rows.getString("payload")).contains(proposed.initiatorPartyId.toString())
                    assertThat(rows.getString("payload")).contains(proposed.requestHash)
                    assertThat(rows.next()).isFalse()
                }
            }
        }
        assertThat(onVertxContext { operations.decisions(proposed.id) }).isEmpty()
        assertThat(
            onVertxContext {
                operations.pending(proposed.principalPartyId, proposed.ruleHash, at, 50)
            },
        ).isEmpty()
        assertThatThrownBy {
            onVertxContext {
                operations.recordDecision(
                    StatutoryDelegationDecision(
                        proposed.id,
                        proposed.initiatorPartyId,
                        StatutoryDecisionVerdict.REJECT,
                        null,
                        at,
                    ),
                )
            }
        }.isInstanceOf(StatutoryDecisionClosed::class.java)
    }

    @Test
    fun `pending inbox selects only current rule and company before expiry`() {
        val proposed = operation()
        onVertxContext { createOperation(proposed) }

        assertThat(
            onVertxContext {
                operations.pending(proposed.principalPartyId, proposed.ruleHash, proposed.createdAt, 50)
            },
        )
            .contains(proposed)
        assertThat(onVertxContext { operations.pending(UUID.randomUUID(), proposed.ruleHash, proposed.createdAt, 50) })
            .isEmpty()
        assertThat(
            onVertxContext {
                operations.pending(proposed.principalPartyId, proposed.ruleHash, proposed.expiresAt, 50)
            },
        )
            .doesNotContain(proposed)
        assertThat(
            onVertxContext {
                operations.pending(proposed.principalPartyId, "0".repeat(64), proposed.createdAt, 50)
            },
        )
            .isEmpty()
    }

    @Test
    fun `keyset inbox and decision progress read only committed evidence`() {
        val first = operation()
        val second = first.copy(
            id = UUID.randomUUID(),
            requestKey = UUID.randomUUID().toString(),
            createdAt = first.createdAt.plusSeconds(1),
            expiresAt = first.expiresAt.plusSeconds(1),
        )
        onVertxContext { createOperation(first) }
        onVertxContext { createOperation(second) }
        val newest = onVertxContext { operations.pending(first.principalPartyId, first.ruleHash, first.createdAt, 1) }
        assertThat(newest).containsExactly(second)
        val older = onVertxContext {
            operations.pending(first.principalPartyId, first.ruleHash, first.createdAt, 1, second.createdAt, second.id)
        }
        assertThat(older).containsExactly(first)

        val decision = StatutoryDelegationDecision(
            first.id,
            UUID.randomUUID(),
            StatutoryDecisionVerdict.REJECT,
            null,
            first.createdAt.plusSeconds(2),
        )
        onVertxContext { operations.recordDecision(decision) }
        assertThat(onVertxContext { operations.decisions(first.id) }).containsExactly(decision)
        assertThat(onVertxContext { operations.decisions(second.id) }).isEmpty()
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT count(*) FROM pg_indexes WHERE tablename = 'delegation_statutory_operations' " +
                    "AND indexname = 'idx_delegation_statutory_inbox_page'",
            ).use { statement ->
                statement.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getInt(1)).isEqualTo(1)
                }
            }
        }
    }

    @Test
    @Suppress("LongMethod") // One real-DB fixture proves both operation families and immutable target evidence.
    fun `acceptance target and lifecycle revision are immutable and absent from issuance inbox`() {
        val at = OffsetDateTime.ofInstant(Instant.parse("2026-09-18T12:00:00Z"), ZoneOffset.UTC)
        val grant = DelegationGrant(
            grantorPartyId = UUID.randomUUID(),
            granteePartyId = UUID.randomUUID(),
            resourceType = DelegationResourceType.ACCOUNT,
            resourceId = UUID.randomUUID(),
            capabilities = setOf(DelegationCapability.ACCOUNT_READ_BALANCES),
            validFrom = at,
            validTo = null,
            createdAt = at,
            updatedAt = at,
        )
        onVertxContext {
            grants.save(
                grant,
                DelegationOffered(
                    aggregateId = grant.id,
                    lifecycleRevision = grant.lifecycleRevision,
                    grantorPartyId = grant.grantorPartyId,
                    granteePartyId = grant.granteePartyId,
                    resourceType = grant.resourceType,
                    resourceId = grant.resourceId,
                    capabilities = grant.capabilities,
                    approvalPolicy = grant.approvalPolicy,
                    requiredApprovals = grant.requiredApprovals,
                    validFrom = grant.validFrom,
                    validTo = grant.validTo,
                    occurredAt = at.toInstant(),
                ),
            )
        }
        val payload = """{"kind":"ACCEPT","grantId":"${grant.id}"}"""
        val acceptance = operation().copy(
            principalPartyId = grant.granteePartyId,
            payloadJson = payload,
            requestHash = sha256(payload),
            operationKind = StatutoryOperationKind.ACCEPT,
            targetGrantId = grant.id,
            expectedLifecycleRevision = grant.lifecycleRevision,
        )
        onVertxContext { createOperation(acceptance) }

        assertThat(onVertxContext { operations.find(acceptance.id, grant.granteePartyId) }).isEqualTo(acceptance)
        assertThat(
            onVertxContext {
                operations.pending(grant.granteePartyId, acceptance.ruleHash, acceptance.createdAt, 50)
            },
        ).doesNotContain(acceptance)
        assertThat(
            onVertxContext {
                operations.pending(
                    grant.granteePartyId,
                    acceptance.ruleHash,
                    acceptance.createdAt,
                    50,
                    kind = StatutoryOperationKind.ACCEPT,
                )
            },
        ).contains(acceptance)
        assertThat(
            onVertxContext {
                operations.pending(
                    UUID.randomUUID(),
                    acceptance.ruleHash,
                    acceptance.createdAt,
                    50,
                    kind = StatutoryOperationKind.ACCEPT,
                )
            },
        ).doesNotContain(acceptance)
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE delegation_statutory_operations SET expected_lifecycle_revision = 9 WHERE operation_id = ?",
            ).use { update ->
                update.setObject(1, acceptance.id)
                assertThatThrownBy { update.executeUpdate() }
                    .isInstanceOf(SQLException::class.java)
                    .hasMessageContaining("immutable")
            }
        }
    }

    @Test
    fun `proposal evidence and SCA decisions cannot be rewritten or reused`() {
        val proposed = operation()
        onVertxContext { createOperation(proposed) }
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

    @Test
    fun `approval requires SCA evidence while an immutable rejection must not claim one`() {
        val proposed = operation()
        onVertxContext { createOperation(proposed) }
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "INSERT INTO delegation_statutory_decisions " +
                    "(operation_id, actor_party_id, sca_session_id, decision, decided_at) " +
                    "VALUES (?, ?, ?, ?, now())",
            ).use { statement ->
                statement.setObject(1, proposed.id)
                statement.setObject(2, UUID.randomUUID())
                statement.setObject(3, null)
                statement.setString(4, "REJECT")
                assertThat(statement.executeUpdate()).isEqualTo(1)

                statement.setObject(2, UUID.randomUUID())
                statement.setString(4, "APPROVE")
                assertThatThrownBy { statement.executeUpdate() }.isInstanceOf(SQLException::class.java)

                statement.setObject(2, UUID.randomUUID())
                statement.setObject(3, UUID.randomUUID())
                statement.setString(4, "REJECT")
                assertThatThrownBy { statement.executeUpdate() }.isInstanceOf(SQLException::class.java)
            }
        }
    }

    @Test
    fun `decision repository stores an exact retry once and never creates a grant`() {
        val proposed = operation()
        onVertxContext { createOperation(proposed) }
        val actor = UUID.randomUUID()
        val first = StatutoryDelegationDecision(
            proposed.id,
            actor,
            StatutoryDecisionVerdict.APPROVE,
            UUID.randomUUID(),
            proposed.createdAt.plusSeconds(60),
        )

        assertThat(onVertxContext { operations.recordDecision(first) }).isEqualTo(first)
        assertThat(onVertxContext { operations.recordDecision(first.copy(decidedAt = first.decidedAt.plusSeconds(1))) })
            .isEqualTo(first)
        assertThat(onVertxContext { operations.findDecision(proposed.id, actor) }).isEqualTo(first)
        assertThatThrownBy {
            onVertxContext { operations.recordDecision(first.copy(scaSessionId = UUID.randomUUID())) }
        }.isInstanceOf(com.openbank.delegation.application.port.out.StatutoryDecisionConflict::class.java)

        dataSource.connection.use { connection ->
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
    @Suppress("NestedBlockDepth") // Keep both JDBC transactions open to prove the real row-lock wait.
    fun `decision insertion waits on the operation row lock used by quorum execution`() {
        val proposed = operation()
        onVertxContext { createOperation(proposed) }
        dataSource.connection.use { executor ->
            executor.autoCommit = false
            try {
                executor.prepareStatement(
                    "SELECT operation_id FROM delegation_statutory_operations WHERE operation_id = ? FOR UPDATE",
                ).use { lock ->
                    lock.setObject(1, proposed.id)
                    lock.executeQuery().use { rows -> assertThat(rows.next()).isTrue() }
                }
                dataSource.connection.use { signer ->
                    signer.autoCommit = false
                    try {
                        signer.createStatement().use { it.execute("SET LOCAL statement_timeout = '150ms'") }
                        signer.prepareStatement(
                            "WITH eligible AS (SELECT operation_id FROM delegation_statutory_operations " +
                                "WHERE operation_id = ? AND state = 'PENDING' FOR UPDATE) " +
                                "INSERT INTO delegation_statutory_decisions " +
                                "(operation_id, actor_party_id, sca_session_id, decision, decided_at) " +
                                "SELECT operation_id, ?, NULL, 'REJECT', now() FROM eligible WHERE true " +
                                "ON CONFLICT DO NOTHING",
                        ).use { insert ->
                            insert.setObject(1, proposed.id)
                            insert.setObject(2, UUID.randomUUID())
                            assertThatThrownBy { insert.executeUpdate() }
                                .isInstanceOf(SQLException::class.java)
                                .hasMessageContaining("statement timeout")
                        }
                    } finally {
                        signer.rollback()
                    }
                }
            } finally {
                executor.rollback()
            }
        }
    }

    @Test
    fun `quorum execution commits one grant and one outbox row or nothing`() {
        val proposed = operation()
        onVertxContext { createOperation(proposed) }
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val rule = jointRule(proposed, first, second)
        val at = proposed.createdAt.plusSeconds(60)
        val now = OffsetDateTime.ofInstant(at, ZoneOffset.UTC)
        val grant = DelegationGrant(
            grantorPartyId = proposed.principalPartyId,
            granteePartyId = UUID.randomUUID(),
            resourceType = DelegationResourceType.ACCOUNT,
            resourceId = UUID.randomUUID(),
            capabilities = setOf(DelegationCapability.ACCOUNT_READ_BALANCES),
            validFrom = now,
            validTo = null,
            createdAt = now,
            updatedAt = now,
        )
        val event = DelegationOffered(
            aggregateId = grant.id,
            lifecycleRevision = grant.lifecycleRevision,
            grantorPartyId = grant.grantorPartyId,
            granteePartyId = grant.granteePartyId,
            resourceType = grant.resourceType,
            resourceId = grant.resourceId,
            capabilities = grant.capabilities,
            approvalPolicy = grant.approvalPolicy,
            requiredApprovals = grant.requiredApprovals,
            validFrom = grant.validFrom,
            validTo = grant.validTo,
            occurredAt = at,
        )
        fun sign(actor: UUID) = onVertxContext {
            operations.recordDecision(
                StatutoryDelegationDecision(
                    proposed.id,
                    actor,
                    StatutoryDecisionVerdict.APPROVE,
                    UUID.randomUUID(),
                    at,
                ),
            )
        }
        fun execute() = onVertxContext {
            operations.execute(proposed.id, proposed.principalPartyId, rule, proposed.ruleHash, grant, event, at)
        }

        sign(first)
        assertThatThrownBy { execute() }
            .isInstanceOf(com.openbank.delegation.application.port.out.StatutoryQuorumIncomplete::class.java)
        assertThat(rowCount("delegation_grants", "grantor_party_id", proposed.principalPartyId)).isZero()
        assertThat(rowCount("delegation_outbox", "aggregate_id", grant.id)).isZero()

        sign(second)
        assertThat(execute().id).isEqualTo(grant.id)
        assertThat(execute().id).isEqualTo(grant.id)
        assertThat(rowCount("delegation_grants", "grantor_party_id", proposed.principalPartyId)).isEqualTo(1)
        assertThat(rowCount("delegation_outbox", "aggregate_id", grant.id)).isEqualTo(1)
        assertThat(
            onVertxContext {
                operations.find(proposed.id, proposed.principalPartyId)
            }?.grantId,
        ).isEqualTo(grant.id)
    }

    @Test
    @Suppress("LongMethod") // Keep the real-DB offer, quorum, rollback and committed evidence assertions together.
    fun `joint acceptance atomically activates existing offer and records one proof and event`() {
        val at = OffsetDateTime.ofInstant(Instant.parse("2026-09-18T12:00:00Z"), ZoneOffset.UTC)
        val offered = DelegationGrant(
            grantorPartyId = UUID.randomUUID(),
            granteePartyId = UUID.randomUUID(),
            resourceType = DelegationResourceType.ACCOUNT,
            resourceId = UUID.randomUUID(),
            capabilities = setOf(DelegationCapability.ACCOUNT_READ_BALANCES),
            validFrom = at,
            validTo = null,
            createdAt = at,
            updatedAt = at,
        )
        val offerEvent = DelegationOffered(
            aggregateId = offered.id,
            lifecycleRevision = 0,
            grantorPartyId = offered.grantorPartyId,
            granteePartyId = offered.granteePartyId,
            resourceType = offered.resourceType,
            resourceId = offered.resourceId,
            capabilities = offered.capabilities,
            validFrom = offered.validFrom,
            occurredAt = at.toInstant(),
        )
        onVertxContext { grants.save(offered, offerEvent) }
        val payload = """{"kind":"ACCEPT","grantId":"${offered.id}"}"""
        val proposed = operation().copy(
            principalPartyId = offered.granteePartyId,
            payloadJson = payload,
            requestHash = sha256(payload),
            operationKind = StatutoryOperationKind.ACCEPT,
            targetGrantId = offered.id,
            expectedLifecycleRevision = 0,
        )
        onVertxContext { createOperation(proposed) }
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val rule = jointRule(proposed, first, second)
        val acceptedAt = proposed.createdAt.plusSeconds(60)
        val event = DelegationActivated(
            aggregateId = offered.id,
            lifecycleRevision = 1,
            grantorPartyId = offered.grantorPartyId,
            granteePartyId = offered.granteePartyId,
            resourceType = offered.resourceType,
            resourceId = offered.resourceId,
            capabilities = offered.capabilities,
            validFrom = offered.validFrom,
            occurredAt = acceptedAt,
        )
        fun sign(actor: UUID) = onVertxContext {
            operations.recordDecision(
                StatutoryDelegationDecision(
                    proposed.id,
                    actor,
                    StatutoryDecisionVerdict.APPROVE,
                    UUID.randomUUID(),
                    acceptedAt,
                ),
            )
        }
        fun execute(expected: DelegationGrant = offered) = onVertxContext {
            operations.executeAcceptance(
                proposed.id,
                offered.granteePartyId,
                rule,
                proposed.ruleHash,
                payload,
                expected,
                event,
                acceptedAt,
            )
        }

        sign(first)
        assertThatThrownBy { execute() }
            .isInstanceOf(com.openbank.delegation.application.port.out.StatutoryQuorumIncomplete::class.java)
        assertThat(onVertxContext { grants.findById(offered.id) }?.status).isEqualTo(DelegationStatus.OFFERED)
        assertThat(rowCount("delegation_outbox", "aggregate_id", offered.id)).isEqualTo(1)

        sign(second)
        assertThatThrownBy { execute(offered.copy(lifecycleRevision = 1)) }
            .isInstanceOf(com.openbank.delegation.application.port.out.StatutoryDecisionClosed::class.java)
        val activated = execute()
        assertThat(activated.status).isEqualTo(DelegationStatus.ACTIVE)
        assertThat(activated.lifecycleRevision).isEqualTo(1)
        assertThat(activated.acceptStatutoryOperationId).isEqualTo(proposed.id)
        assertThat(activated.acceptScaSessionId).isNull()
        assertThat(execute().id).isEqualTo(offered.id)
        assertThat(onVertxContext { grants.findById(offered.id) }?.acceptStatutoryOperationId).isEqualTo(proposed.id)
        assertThat(rowCount("delegation_outbox", "aggregate_id", offered.id)).isEqualTo(2)
        assertThat(onVertxContext { operations.find(proposed.id, offered.granteePartyId) }?.grantId)
            .isEqualTo(offered.id)
    }

    private fun jointRule(proposed: StatutoryDelegationOperation, first: UUID, second: UUID) =
        StatutoryRepresentationRule(
            policyId = proposed.policyId,
            principalPartyId = proposed.principalPartyId,
            revision = proposed.policyRevision,
            sourceCaseId = proposed.sourceCaseId,
            attestationId = UUID.randomUUID(),
            ruleTextHash = "a".repeat(64),
            mode = StatutoryRuleMode.JOINT_ALL,
            requiredSignatures = 2,
            requiredOffices = listOf("director"),
            registryRepresentativeCount = 2,
            eligibleRepresentatives = listOf(
                StatutoryRepresentative(first, setOf(0), setOf("director")),
                StatutoryRepresentative(second, setOf(1), setOf("director")),
            ),
        )

    private fun rowCount(table: String, column: String, id: UUID): Int = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT count(*) FROM $table WHERE $column = ?").use { statement ->
            statement.setObject(1, id)
            statement.executeQuery().use { rows ->
                assertThat(rows.next()).isTrue()
                rows.getInt(1)
            }
        }
    }

    private fun operation(): StatutoryDelegationOperation {
        val principal = UUID.randomUUID()
        val policy = UUID.randomUUID()
        val initiator = UUID.randomUUID()
        val other = UUID.randomUUID()
        val payload = """{"principalPartyId":"$principal","resourceId":"${UUID.randomUUID()}"}"""
        val rule = """{"policyId":"$policy","requiredSignatures":2,"eligibleRepresentatives":[""" +
            """{"partyId":"$initiator"},{"partyId":"$other"}]}"""
        val at = Instant.parse("2026-09-17T12:00:00Z")
        return StatutoryDelegationOperation(
            id = UUID.randomUUID(),
            principalPartyId = principal,
            initiatorPartyId = initiator,
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
