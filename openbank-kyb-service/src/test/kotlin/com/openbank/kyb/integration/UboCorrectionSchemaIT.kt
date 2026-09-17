// SPDX-License-Identifier: Apache-2.0
package com.openbank.kyb.integration

import com.openbank.kyb.domain.model.IdentifierScheme
import com.openbank.kyb.domain.model.LegalEntityIdentifier
import com.openbank.kyb.domain.model.UboFinding
import com.openbank.kyb.domain.model.UboSource
import com.openbank.kyb.infrastructure.persistence.repository.KybUboJson
import com.openbank.kyb.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

/** Real-Postgres proof that a correction is case-scoped, reviewed and committed with its successor. */
@QuarkusTest
@QuarkusTestResource(KybBootSmokeIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class UboCorrectionSchemaIT {
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "quarkus.datasource.jdbc.url")
    lateinit var jdbcUrl: String

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "quarkus.datasource.username")
    lateinit var jdbcUser: String

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "quarkus.datasource.password")
    lateinit var jdbcPassword: String

    @Test
    fun `candidate hash and case ownership are enforced before review`() {
        val (caseId, priorId) = seed()
        val candidate = candidate()
        DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword).use { connection ->
            val (priorJson, priorHash) = candidate("original register statement")
            assertThatThrownBy {
                insertProposal(connection, UUID.randomUUID(), caseId, priorId, priorJson, priorHash)
            }.hasMessageContaining("cannot repeat the prior mapped finding")
            assertThatThrownBy {
                insertProposal(connection, UUID.randomUUID(), caseId, priorId, candidate.first, "0".repeat(64))
            }.hasMessageContaining("chk_kyb_ubo_correction_hash")
            assertThatThrownBy {
                connection.prepareStatement(
                    """INSERT INTO kyb_ubo_observation_corrections
                       (correction_id, case_id, prior_observation_id, candidate_finding_json,
                        candidate_sha256, candidate_source, candidate_fetched_at, reason_code,
                        proposed_by, proposed_at, status, decided_by, decided_at)
                       VALUES (?, ?, ?, ?, ?, 'REGISTER', now(), 'MAPPING_ERROR',
                               'maker', now(), 'APPROVED', 'checker', now())""",
                ).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, caseId)
                    statement.setObject(3, priorId)
                    statement.setString(4, candidate.first)
                    statement.setString(5, candidate.second)
                    statement.executeUpdate()
                }
            }.hasMessageContaining("must start pending")
            assertThatThrownBy {
                insertProposal(
                    connection,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    priorId,
                    candidate.first,
                    candidate.second,
                )
            }.hasMessageContaining("fk_kyb_ubo_correction_prior_case")
        }
    }

    @Test
    fun `approval requires another actor and a matching successor in the same commit`() {
        val (caseId, priorId) = seed()
        val (json, hash) = candidate()
        val correctionId = UUID.randomUUID()
        DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword).use { connection ->
            insertProposal(connection, correctionId, caseId, priorId, json, hash)
        }
        assertInvalidApprovals(correctionId)
        val successorId = approveWithSuccessor(caseId, priorId, correctionId, json, hash)
        assertStoredSuccessor(priorId, correctionId, successorId)
    }

    private fun assertInvalidApprovals(correctionId: UUID) {
        DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword).use { connection ->
            assertThatThrownBy { approve(connection, correctionId, "maker") }
                .hasMessageContaining("chk_kyb_ubo_correction_decision")
            assertThatThrownBy { approve(connection, correctionId, "checker") }
                .hasMessageContaining("approved correction requires a replacement observation")
        }
    }

    private fun approveWithSuccessor(
        caseId: UUID,
        priorId: UUID,
        correctionId: UUID,
        json: String,
        hash: String,
    ): UUID {
        val successorId = UUID.randomUUID()
        DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword).use { connection ->
            connection.autoCommit = false
            try {
                assertThat(approve(connection, correctionId, "checker")).isEqualTo(1)
                connection.prepareStatement(
                    """INSERT INTO kyb_ubo_observations
                       (observation_id, case_id, revision, source, source_sha256, finding_json,
                        fetched_at, recorded_at, supersedes_observation_id, correction_id)
                       SELECT ?, ?, 2, 'REGISTER', ?, ?, p.candidate_fetched_at, now(), ?, ?
                       FROM kyb_ubo_observation_corrections p WHERE p.correction_id = ?""",
                ).use { statement ->
                    statement.setObject(1, successorId)
                    statement.setObject(2, caseId)
                    statement.setString(3, hash)
                    statement.setString(4, json)
                    statement.setObject(5, priorId)
                    statement.setObject(6, correctionId)
                    statement.setObject(7, correctionId)
                    assertThat(statement.executeUpdate()).isEqualTo(1)
                }
                connection.commit()
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            }
        }
        return successorId
    }

    private fun assertStoredSuccessor(priorId: UUID, correctionId: UUID, successorId: UUID) {
        DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword).use { connection ->
            connection.prepareStatement(
                "SELECT supersedes_observation_id FROM kyb_ubo_observations WHERE observation_id = ?",
            ).use { statement ->
                statement.setObject(1, successorId)
                statement.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getObject(1, UUID::class.java)).isEqualTo(priorId)
                }
            }
            assertThatThrownBy {
                connection.prepareStatement(
                    "UPDATE kyb_ubo_observation_corrections SET reason_code = 'MAPPING_ERROR' WHERE correction_id = ?",
                ).use { statement ->
                    statement.setObject(1, correctionId)
                    statement.executeUpdate()
                }
            }.hasMessageContaining("immutable")
        }
    }

    private fun approve(connection: java.sql.Connection, correctionId: UUID, checker: String): Int =
        connection.prepareStatement(
            """UPDATE kyb_ubo_observation_corrections SET status = 'APPROVED',
               decided_by = ?, decided_at = now() WHERE correction_id = ?""",
        ).use { statement ->
            statement.setString(1, checker)
            statement.setObject(2, correctionId)
            statement.executeUpdate()
        }

    private fun candidate(statement: String = "synthetic register correction"): Pair<String, String> {
        val finding = UboFinding(
            identifier = LegalEntityIdentifier.of(IdentifierScheme.CZ_ICO, "45274649"),
            source = UboSource.REGISTER,
            owners = emptyList(),
            registerStatements = listOf(statement),
            threshold = 0.25,
            registerName = "synthetic",
            sourceRef = "synthetic-case",
            fetchedAt = Instant.parse("2026-01-02T12:00:00Z"),
        )
        val json = KybUboJson.write(finding)
        val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.toByteArray()))
        return json to hash
    }

    private fun seed(): Pair<UUID, UUID> {
        val caseId = UUID.randomUUID()
        val priorId = UUID.randomUUID()
        val (json, hash) = candidate("original register statement")
        val now = Timestamp.from(Instant.parse("2026-01-01T12:00:00Z"))
        DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword).use { connection ->
            connection.autoCommit = false
            connection.prepareStatement(
                """INSERT INTO kyb_cases (id, case_id, identifier_scheme, identifier_value,
                   initiator_party_id, status, created_at, updated_at)
                   VALUES (nextval('kyb_cases_seq'), ?, 'CZ_ICO', '45274649', ?, 'MANUAL_REVIEW', ?, ?)""",
            ).use { statement ->
                statement.setObject(1, caseId)
                statement.setObject(2, UUID.randomUUID())
                statement.setTimestamp(3, now)
                statement.setTimestamp(4, now)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """INSERT INTO kyb_ubo_observations
                   (observation_id, case_id, revision, source, source_sha256, finding_json, fetched_at, recorded_at)
                   VALUES (?, ?, 1, 'REGISTER', ?, ?, ?, ?)""",
            ).use { statement ->
                statement.setObject(1, priorId)
                statement.setObject(2, caseId)
                statement.setString(3, hash)
                statement.setString(4, json)
                statement.setTimestamp(5, now)
                statement.setTimestamp(6, now)
                statement.executeUpdate()
            }
            connection.commit()
        }
        return caseId to priorId
    }

    private fun insertProposal(
        connection: java.sql.Connection,
        correctionId: UUID,
        caseId: UUID,
        priorId: UUID,
        json: String,
        hash: String,
    ) {
        connection.prepareStatement(
            """INSERT INTO kyb_ubo_observation_corrections
               (correction_id, case_id, prior_observation_id, candidate_finding_json, candidate_sha256,
                candidate_source, candidate_fetched_at, reason_code, proposed_by, proposed_at)
               VALUES (?, ?, ?, ?, ?, 'REGISTER', now(), 'REGISTER_CORRECTION', 'maker', now())""",
        ).use { statement ->
            statement.setObject(1, correctionId)
            statement.setObject(2, caseId)
            statement.setObject(3, priorId)
            statement.setString(4, json)
            statement.setString(5, hash)
            statement.executeUpdate()
        }
    }
}
