// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.integration

import com.openbank.party.application.port.out.RepresentationPolicyRepository
import com.openbank.party.domain.model.EligibleRepresentative
import com.openbank.party.domain.model.RepresentationPolicyMode
import com.openbank.party.domain.model.RepresentationPolicySnapshot
import com.openbank.party.it.PostgresRedpandaTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/** Real PostgreSQL proof that Flyway creates an immutable, naturally idempotent rule store. */
@QuarkusTest
@QuarkusTestResource(PostgresRedpandaTestResource::class)
class RepresentationPolicySchemaIT {
    @Inject lateinit var dataSource: DataSource

    @Inject lateinit var policies: RepresentationPolicyRepository

    private fun <T> onVertxContext(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    @Test
    fun `repository round-trips the entire verified roster and office rule`() {
        val chair = UUID.randomUUID()
        val member = UUID.randomUUID()
        val snapshot = RepresentationPolicySnapshot(
            id = UUID.randomUUID(),
            principalPartyId = UUID.randomUUID(),
            revision = 1,
            sourceCaseId = UUID.randomUUID(),
            attestationId = UUID.randomUUID(),
            ruleTextHash = "b".repeat(64),
            registrySource = "verified-registry",
            registrySourceRef = "register-entry",
            registryRepresentativeCount = 2,
            mode = RepresentationPolicyMode.JOINT_N,
            requiredSignatures = 2,
            requiredOffices = listOf("Chair", "Member"),
            eligibleRepresentatives = listOf(
                EligibleRepresentative(chair, setOf(0), setOf("Chair", "Member")),
                EligibleRepresentative(member, setOf(1), setOf("Member")),
            ),
            evidenceRef = "verified-case",
            effectiveFrom = Instant.parse("2026-09-17T00:00:00Z"),
        )
        onVertxContext { policies.insert(snapshot) }
        val restored = onVertxContext { policies.findById(snapshot.id) }
        assertThat(restored).isEqualTo(snapshot)
        assertThat(restored!!.satisfiedBy(setOf(chair, member))).isTrue()
        assertThat(onVertxContext { policies.findBySourceCaseId(snapshot.sourceCaseId) }).isEqualTo(snapshot)
    }

    @Test
    fun `verified rule evidence cannot be rewritten or inserted twice for one KYB case`() {
        val policyId = UUID.randomUUID()
        val caseId = UUID.randomUUID()
        val principalId = UUID.randomUUID()
        dataSource.connection.use { connection ->
            insert(connection, policyId, caseId, principalId)
            connection.prepareStatement(
                "SELECT mode, required_signatures FROM party_representation_policies WHERE policy_id = ?",
            ).use { statement ->
                statement.setObject(1, policyId)
                statement.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getString("mode")).isEqualTo("JOINT_N")
                    assertThat(rows.getInt("required_signatures")).isEqualTo(2)
                }
            }
            assertThatThrownBy { insert(connection, UUID.randomUUID(), caseId, principalId) }
                .isInstanceOf(SQLException::class.java)
            connection.prepareStatement(
                "UPDATE party_representation_policies SET required_signatures = 1 WHERE policy_id = ?",
            ).use { statement ->
                statement.setObject(1, policyId)
                assertThatThrownBy { statement.executeUpdate() }
                    .isInstanceOf(SQLException::class.java)
                    .hasMessageContaining("append-only")
            }
            connection.prepareStatement(
                "DELETE FROM party_representation_policies WHERE policy_id = ?",
            ).use { statement ->
                statement.setObject(1, policyId)
                assertThatThrownBy { statement.executeUpdate() }
                    .isInstanceOf(SQLException::class.java)
                    .hasMessageContaining("append-only")
            }
        }
    }

    private fun insert(connection: Connection, policyId: UUID, caseId: UUID, principalId: UUID) {
        connection.prepareStatement(
            """
            INSERT INTO party_representation_policies
                (policy_id, principal_party_id, revision, source_case_id, attestation_id, rule_text_hash,
                 registry_source, registry_source_ref, registry_representative_count, mode,
                 required_signatures, required_offices_json, eligible_representatives_json,
                 evidence_ref, effective_from)
            VALUES (?, ?, 1, ?, ?, ?, 'verified-registry', 'register-entry', 2, 'JOINT_N', 2, ?, ?, 'verified-case', now())
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, policyId)
            statement.setObject(2, principalId)
            statement.setObject(3, caseId)
            statement.setObject(4, UUID.randomUUID())
            statement.setString(5, "a".repeat(64))
            statement.setString(6, "[\"Chair\",\"Member\"]")
            statement.setString(
                7,
                "[" +
                    "{\"partyId\":\"${UUID.randomUUID()}\",\"registryRepresentativeIndices\":[0]," +
                    "\"officeTags\":[\"Chair\",\"Member\"]}," +
                    "{\"partyId\":\"${UUID.randomUUID()}\",\"registryRepresentativeIndices\":[1]," +
                    "\"officeTags\":[\"Member\"]}]",
            )
            assertThat(statement.executeUpdate()).isEqualTo(1)
        }
    }
}
