// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.integration

import com.openbank.party.application.port.out.KybSignedCaseProjection
import com.openbank.party.application.port.out.KybSignedCaseProjectionRepository
import com.openbank.party.application.port.out.KybSignedMandateHolder
import com.openbank.party.application.port.out.RepresentationPolicyRepository
import com.openbank.party.domain.model.EligibleRepresentative
import com.openbank.party.domain.model.RepresentationPolicyMode
import com.openbank.party.domain.model.RepresentationPolicySnapshot
import com.openbank.party.it.PostgresRedpandaTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.vertx.VertxContextSupport
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
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

    @Inject lateinit var signedCases: KybSignedCaseProjectionRepository

    private fun <T> onVertxContext(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_API"])
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
        assertThat(onVertxContext { policies.findLatestEffective(snapshot.principalPartyId) }).isEqualTo(snapshot)
        assertThat(onVertxContext { policies.allocateRevision() }).isPositive()
        Given { this } When {
            get("/api/v1/parties/${snapshot.principalPartyId}/representation-policy")
        } Then {
            statusCode(200)
            body("sourceCaseId", org.hamcrest.Matchers.equalTo(snapshot.sourceCaseId.toString()))
            body("mode", org.hamcrest.Matchers.equalTo("JOINT_N"))
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_API"])
    fun `representation evidence returns 404 when no verified signed case exists`() {
        Given { this } When {
            get("/api/v1/parties/${UUID.randomUUID()}/representation-policy")
        } Then {
            statusCode(404)
        }
    }

    @Test
    fun `all mandates rule and outbox commit together or all roll back`() {
        val caseId = UUID.randomUUID()
        val principalId = UUID.randomUUID()
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val at = Instant.parse("2026-09-17T12:00:00Z")
        val policy = RepresentationPolicySnapshot(
            id = UUID.randomUUID(),
            principalPartyId = principalId,
            revision = 1,
            sourceCaseId = caseId,
            attestationId = UUID.randomUUID(),
            ruleTextHash = "b".repeat(64),
            registrySource = "verified-registry",
            registrySourceRef = null,
            registryRepresentativeCount = 2,
            mode = RepresentationPolicyMode.JOINT_ALL,
            requiredSignatures = 2,
            requiredOffices = emptyList(),
            eligibleRepresentatives = listOf(
                EligibleRepresentative(first, setOf(0), setOf("statutory-representative")),
                EligibleRepresentative(second, setOf(1), setOf("statutory-representative")),
            ),
            evidenceRef = "kyb-case:$caseId:ceremony:${UUID.randomUUID()}",
            effectiveFrom = at,
        )
        val projection = KybSignedCaseProjection(
            caseId = caseId,
            principalPartyId = principalId,
            payloadHash = "a".repeat(64),
            occurredAt = at,
            requiredSignatures = 2,
            soleTrader = false,
            holders = listOf(
                KybSignedMandateHolder(UUID.randomUUID(), first, 0),
                KybSignedMandateHolder(UUID.randomUUID(), second, 1),
            ),
            policy = policy,
        )

        assertThatThrownBy {
            onVertxContext {
                signedCases.project(projection.copy(policy = policy.copy(registrySource = "x".repeat(65))))
            }
        }.isInstanceOf(Exception::class.java)
        assertProjectionCounts(caseId, principalId, 0)

        assertThat(onVertxContext { signedCases.project(projection) }).isTrue()
        assertProjectionCounts(caseId, principalId, 2)
        assertThat(onVertxContext { signedCases.project(projection) }).isFalse()
        assertProjectionCounts(caseId, principalId, 2)
        assertThatThrownBy {
            onVertxContext { signedCases.project(projection.copy(payloadHash = "c".repeat(64))) }
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertProjectionCounts(caseId, principalId, 2)
    }

    private fun assertProjectionCounts(caseId: UUID, principalId: UUID, expectedMandates: Int) {
        dataSource.connection.use { connection ->
            fun count(sql: String, id: UUID): Int = connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, id)
                statement.executeQuery().use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
            assertThat(count("SELECT count(*) FROM party_kyb_signed_case_projections WHERE case_id = ?", caseId))
                .isEqualTo(if (expectedMandates == 0) 0 else 1)
            assertThat(count("SELECT count(*) FROM party_representation_policies WHERE source_case_id = ?", caseId))
                .isEqualTo(if (expectedMandates == 0) 0 else 1)
            assertThat(count("SELECT count(*) FROM party_mandates WHERE principal_party_id = ?", principalId))
                .isEqualTo(expectedMandates)
            assertThat(count("SELECT count(*) FROM party_outbox WHERE aggregate_id = ?", principalId))
                .isEqualTo(expectedMandates)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_VIEWER"])
    fun `ordinary viewer cannot enumerate statutory representative ids`() {
        Given { this } When {
            get("/api/v1/parties/${UUID.randomUUID()}/representation-policy")
        } Then {
            statusCode(403)
        }
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
