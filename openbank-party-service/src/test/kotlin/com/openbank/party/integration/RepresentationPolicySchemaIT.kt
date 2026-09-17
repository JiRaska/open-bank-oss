// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.integration

import com.openbank.party.it.PostgresRedpandaTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

/** Real PostgreSQL proof that Flyway creates an immutable, naturally idempotent rule store. */
@QuarkusTest
@QuarkusTestResource(PostgresRedpandaTestResource::class)
class RepresentationPolicySchemaIT {
    @Inject lateinit var dataSource: DataSource

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
                (policy_id, principal_party_id, revision, source_case_id, rule_text_hash, mode,
                 required_signatures, required_offices_json, eligible_representatives_json,
                 evidence_ref, effective_from)
            VALUES (?, ?, 1, ?, ?, 'JOINT_N', 2, ?, ?, 'verified-case', now())
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, policyId)
            statement.setObject(2, principalId)
            statement.setObject(3, caseId)
            statement.setString(4, "a".repeat(64))
            statement.setString(5, "[\"Chair\",\"Member\"]")
            statement.setString(
                6,
                "[" +
                    "{\"partyId\":\"${UUID.randomUUID()}\",\"office\":\"Chair\"}," +
                    "{\"partyId\":\"${UUID.randomUUID()}\",\"office\":\"Member\"}]",
            )
            assertThat(statement.executeUpdate()).isEqualTo(1)
        }
    }
}
