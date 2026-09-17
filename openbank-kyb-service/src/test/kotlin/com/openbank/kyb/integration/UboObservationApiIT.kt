// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.integration

import com.openbank.kyb.domain.model.IdentifierScheme
import com.openbank.kyb.domain.model.LegalEntityIdentifier
import com.openbank.kyb.domain.model.UboFinding
import com.openbank.kyb.domain.model.UboSource
import com.openbank.kyb.infrastructure.persistence.repository.KybUboJson
import com.openbank.kyb.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
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
        val url = "/api/v1/kyb/cases/$caseId/ubo-observations/$observationId"

        Given { header("X-Investigation-Purpose", "Ownership review") } When { get(url) } Then {
            statusCode(200)
            body("caseId", equalTo(caseId.toString()))
            body("id", equalTo(observationId.toString()))
            body("finding.source", equalTo("SELF_DECLARATION"))
        }
        Given { this } When { get(url) } Then { statusCode(400) }
        Given { header("X-Investigation-Purpose", "Ownership review") } When {
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
                    assertThat(reads.getString("purpose")).isEqualTo("Ownership review")
                    assertThat(reads.next()).isFalse()
                }
            }
        }
    }

    @Test
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
    fun `shared API service identity cannot read an observation`() {
        Given { header("X-Investigation-Purpose", "Ownership review") } When {
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
