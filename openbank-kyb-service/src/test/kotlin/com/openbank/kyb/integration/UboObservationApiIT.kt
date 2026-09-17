// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.integration

import com.openbank.kyb.application.port.out.UboObservationAccessDecision
import com.openbank.kyb.domain.model.IdentifierScheme
import com.openbank.kyb.domain.model.LegalEntityIdentifier
import com.openbank.kyb.domain.model.UboFinding
import com.openbank.kyb.domain.model.UboSource
import com.openbank.kyb.infrastructure.persistence.repository.KybJson
import com.openbank.kyb.infrastructure.persistence.repository.KybUboJson
import com.openbank.kyb.it.PostgresTestResource
import com.openbank.kyb.it.StubContextOwnershipAccess
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

@QuarkusTest
@QuarkusTestResource(KybBootSmokeIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class UboObservationApiIT {
    @Inject lateinit var access: StubContextOwnershipAccess

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "quarkus.datasource.jdbc.url")
    lateinit var jdbcUrl: String

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "quarkus.datasource.username")
    lateinit var jdbcUser: String

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "quarkus.datasource.password")
    lateinit var jdbcPassword: String

    @Test
    @TestSecurity(user = "kyc-reviewer", roles = ["ROLE_KYC"])
    fun `authorised reviewer can read exact case observation with purpose but not another case`() {
        val caseId = UUID.randomUUID()
        val otherCaseId = UUID.randomUUID()
        val observationId = UUID.randomUUID()
        seed(caseId, observationId)
        access.decisions[caseId] = UboObservationAccessDecision.ALLOWED
        val url = "/api/v1/kyb/cases/$caseId/ubo-observations/$observationId"

        Given {
            header("X-Investigation-Purpose", "KYB_OWNERSHIP_REVIEW")
            header("Authorization", "Bearer synthetic-reviewer")
        } When { get(url) } Then {
            statusCode(200)
            body("caseId", equalTo(caseId.toString()))
            body("id", equalTo(observationId.toString()))
            body("finding.source", equalTo("SELF_DECLARATION"))
        }
        Given { this } When { get(url) } Then { statusCode(400) }
        access.decisions[otherCaseId] = UboObservationAccessDecision.ALLOWED
        Given {
            header("X-Investigation-Purpose", "KYB_OWNERSHIP_REVIEW")
            header("Authorization", "Bearer synthetic-reviewer")
        } When {
            get("/api/v1/kyb/cases/$otherCaseId/ubo-observations/$observationId")
        } Then { statusCode(404) }
        DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword).use { connection ->
            connection.prepareStatement(
                "SELECT principal_id, purpose FROM kyb_ubo_observation_reads WHERE observation_id = ?",
            ).use { statement ->
                statement.setObject(1, observationId)
                statement.executeQuery().use { reads ->
                    assertThat(reads.next()).isTrue()
                    assertThat(reads.getString("principal_id")).isEqualTo("kyc-reviewer")
                    assertThat(reads.getString("purpose")).isEqualTo("KYB_OWNERSHIP_REVIEW")
                    assertThat(reads.next()).isFalse()
                }
            }
        }
    }

    @Test
    @TestSecurity(user = "kyc-reviewer", roles = ["ROLE_KYC"])
    fun `direct source read fails closed without live assignment`() {
        val caseId = UUID.randomUUID()
        val observationId = UUID.randomUUID()
        seed(caseId, observationId)
        val url = "/api/v1/kyb/cases/$caseId/ubo-observations/$observationId"
        Given {
            header("X-Investigation-Purpose", "KYB_OWNERSHIP_REVIEW")
            header("Authorization", "Bearer synthetic-reviewer")
        } When { get(url) } Then { statusCode(403) }
        access.decisions[caseId] = UboObservationAccessDecision.UNAVAILABLE
        Given {
            header("X-Investigation-Purpose", "KYB_OWNERSHIP_REVIEW")
            header("Authorization", "Bearer synthetic-reviewer")
        } When { get(url) } Then { statusCode(503) }
        access.decisions[caseId] = UboObservationAccessDecision.ALLOWED
        Given {
            header("X-Investigation-Purpose", "KYB_OWNERSHIP_REVIEW")
        } When { get(url) } Then { statusCode(403) }
    }

    @Test
    @TestSecurity(user = "kyb-admin", roles = ["ROLE_ADMIN"])
    fun `restriction immediately hides source detail and commits one reference-only event`() {
        val caseId = UUID.randomUUID()
        val observationId = UUID.randomUUID()
        seed(caseId, observationId)
        access.decisions[caseId] = UboObservationAccessDecision.ALLOWED
        val url = "/api/v1/kyb/cases/$caseId/ubo-observations/$observationId"
        val restriction = "$url/restrict"

        Given {
            contentType("application/json")
            header("X-Investigation-Purpose", "KYB_OWNERSHIP_REVIEW")
            body("""{"reasonCode":"EVIDENCE_CHALLENGED"}""")
        } When { post(restriction) } Then { statusCode(204) }
        Given {
            contentType("application/json")
            header("X-Investigation-Purpose", "KYB_OWNERSHIP_REVIEW")
            body("""{"reasonCode":"EVIDENCE_CHALLENGED"}""")
        } When { post(restriction) } Then { statusCode(204) }
        Given {
            header("X-Investigation-Purpose", "KYB_OWNERSHIP_REVIEW")
            header("Authorization", "Bearer synthetic-admin")
        } When { get(url) } Then { statusCode(404) }

        DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword).use { connection ->
            connection.prepareStatement(
                "SELECT reason_code, actor_id FROM kyb_ubo_observation_restrictions WHERE observation_id = ?",
            ).use { statement ->
                statement.setObject(1, observationId)
                statement.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getString("reason_code")).isEqualTo("EVIDENCE_CHALLENGED")
                    assertThat(rows.getString("actor_id")).isEqualTo("kyb-admin")
                    assertThat(rows.next()).isFalse()
                }
            }
            connection.prepareStatement(
                "SELECT payload FROM kyb_outbox WHERE aggregate_id = ? " +
                    "AND event_type = 'KybUboObservationRestricted'",
            ).use { statement ->
                statement.setObject(1, caseId)
                statement.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue()
                    val event = KybJson.mapper.readTree(rows.getString("payload"))
                    assertThat(event.fieldNames().asSequence().toSet()).containsExactlyInAnyOrder(
                        "schemaVersion",
                        "eventType",
                        "caseId",
                        "observationId",
                        "revision",
                        "sourceSha256",
                    )
                    assertThat(event.path("observationId").asText()).isEqualTo(observationId.toString())
                    assertThat(rows.next()).isFalse()
                }
            }
        }
    }

    @Test
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
    fun `shared API service identity cannot read an observation`() {
        Given { header("X-Investigation-Purpose", "KYB_OWNERSHIP_REVIEW") } When {
            get("/api/v1/kyb/cases/${UUID.randomUUID()}/ubo-observations/${UUID.randomUUID()}")
        } Then { statusCode(403) }
    }

    private fun seed(caseId: UUID, observationId: UUID) {
        val now = Instant.parse("2026-01-01T12:00:00Z")
        val finding = UboFinding(
            identifier = LegalEntityIdentifier.of(IdentifierScheme.CZ_ICO, "45274649"),
            source = UboSource.SELF_DECLARATION,
            owners = emptyList(),
            registerStatements = emptyList(),
            threshold = 0.25,
            registerName = null,
            sourceRef = null,
            fetchedAt = now,
        )
        val findingJson = KybUboJson.write(finding)
        val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(findingJson.toByteArray()))
        DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword).use { connection ->
            connection.autoCommit = false
            connection.prepareStatement(
                "INSERT INTO kyb_cases (id, case_id, identifier_scheme, identifier_value, initiator_party_id, " +
                    "status, created_at, updated_at) VALUES (nextval('kyb_cases_seq'), ?, 'CZ_ICO', " +
                    "'45274649', ?, 'MANUAL_REVIEW', ?, ?)",
            ).use { statement ->
                statement.setObject(1, caseId)
                statement.setObject(2, UUID.randomUUID())
                statement.setTimestamp(3, Timestamp.from(now))
                statement.setTimestamp(4, Timestamp.from(now))
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO kyb_ubo_observations (observation_id, case_id, revision, source, source_sha256, " +
                    "finding_json, fetched_at, recorded_at) VALUES (?, ?, 1, 'SELF_DECLARATION', ?, ?, ?, ?)",
            ).use { statement ->
                statement.setObject(1, observationId)
                statement.setObject(2, caseId)
                statement.setString(3, hash)
                statement.setString(4, findingJson)
                statement.setTimestamp(5, Timestamp.from(now))
                statement.setTimestamp(6, Timestamp.from(now))
                statement.executeUpdate()
            }
            connection.commit()
        }
    }
}
