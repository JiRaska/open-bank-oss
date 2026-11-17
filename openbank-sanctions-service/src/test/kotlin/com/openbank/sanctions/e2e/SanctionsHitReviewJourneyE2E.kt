// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.e2e

import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.sql.DataSource

/**
 * The analyst journey over the real HTTP surface: an operator screens a person against the
 * consolidated lists, an exact listed name is a HIT, a replayed screen returns the same check
 * (no second row), an unrelated name is CLEAR, and a reviewed hit becomes WHITELISTED with an
 * expiry. The negative surface fails closed: a viewer cannot screen, a null alias element is a
 * 400 (never the NPE-driven 500 of #7867), and a review of an unknown check is a 404.
 *
 * The screens run on `/api/v3` because an explicit `profile` requires it; a v3-created check is
 * not v1-compatible, so the read-back and review steps stay on v3 as well. The legacy v1 surface
 * is exercised by the fail-closed tests.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(SanctionsHitReviewJourneyE2E.JourneyProfile::class)
class SanctionsHitReviewJourneyE2E {

    /** Own boot (own container DB), mirroring the ITs' isolation. */
    class JourneyProfile : QuarkusTestProfile

    @Inject
    lateinit var dataSource: DataSource

    private var checkId: String = ""

    @BeforeAll
    fun seed() {
        dataSource.connection.use { c ->
            c.createStatement().use {
                it.execute(
                    "INSERT INTO sanctions_entries (list_type, external_id, entity_type, primary_name, search_text) " +
                        "VALUES " +
                        "('OFAC_SDN',   'e2e-sanctioned', 'INDIVIDUAL', '$LISTED', lower('$LISTED')), " +
                        "('PEP_GLOBAL', 'e2e-pep',        'INDIVIDUAL', '$LISTED_PEP', lower('$LISTED_PEP'))",
                )
            }
        }
    }

    @AfterAll
    fun cleanUp() {
        dataSource.connection.use { c ->
            c.createStatement().use {
                it.execute("DELETE FROM sanctions_change_journal WHERE external_id LIKE 'e2e-%'")
                it.execute("DELETE FROM sanctions_entries WHERE external_id LIKE 'e2e-%'")
            }
        }
    }

    @Test
    @Order(1)
    @TestSecurity(user = "sanctions-e2e-operator", roles = ["ROLE_OPERATOR"])
    fun `1 - an exact listed name screens as a HIT with the matched list entry`() {
        checkId = given().contentType("application/json")
            .body(screenBody(SCREEN_KEY, LISTED))
            .post("/api/v3/sanctions/screen").then()
            .statusCode(201)
            .body("status", equalTo("HIT"))
            .body("pepStatus", equalTo("CLEAR"))
            .body("matches", hasSize<Any>(1))
            .body("matches[0].matchKind", equalTo("SANCTIONS"))
            .body("matches[0].matchedId", equalTo("e2e-sanctioned"))
            .body("subjectId", equalTo("e2e-subject-1"))
            .extract().path<String>("id")
    }

    @Test
    @Order(2)
    @TestSecurity(user = "sanctions-e2e-operator", roles = ["ROLE_OPERATOR"])
    fun `2 - a replayed screen with the same key returns the same check, not a second row`() {
        given().contentType("application/json")
            .body(screenBody(SCREEN_KEY, LISTED))
            .post("/api/v3/sanctions/screen").then()
            .statusCode(201)
            .body("id", equalTo(checkId))

        given().get("/api/v3/sanctions").then()
            .statusCode(200)
            .body("findAll { it.id == '$checkId' }", hasSize<Any>(1))
    }

    @Test
    @Order(3)
    @TestSecurity(user = "sanctions-e2e-operator", roles = ["ROLE_OPERATOR"])
    fun `3 - an unrelated name screens CLEAR against every enabled list`() {
        given().contentType("application/json")
            .body(screenBody(UUID.randomUUID().toString(), "E2E Unrelated Person"))
            .post("/api/v3/sanctions/screen").then()
            .statusCode(201)
            .body("status", equalTo("CLEAR"))
            .body("pepStatus", equalTo("CLEAR"))
            .body("matches", hasSize<Any>(0))
    }

    @Test
    @Order(4)
    @TestSecurity(user = "sanctions-e2e-operator", roles = ["ROLE_OPERATOR"])
    fun `4 - the hit is readable back and appears in the hits list`() {
        given().get("/api/v3/sanctions/$checkId").then()
            .statusCode(200)
            .body("id", equalTo(checkId))
            .body("status", equalTo("HIT"))
            .body("name", equalTo(LISTED))

        given().get("/api/v3/sanctions/hits").then()
            .statusCode(200)
            .body("findAll { it.id == '$checkId' }", hasSize<Any>(1))
    }

    @Test
    @Order(5)
    @TestSecurity(user = "sanctions-e2e-analyst", roles = ["ROLE_OPERATOR"])
    fun `5 - an analyst whitelists the reviewed hit and clearance carries an expiry`() {
        val before = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val clearanceExpiresAt = given().contentType("application/json")
            .body("""{"checkId":"$checkId","note":"verified false positive by E2E","newStatus":"WHITELISTED"}""")
            .post("/api/v3/sanctions/review").then()
            .statusCode(200)
            .body("status", equalTo("WHITELISTED"))
            .body("reviewedBy", equalTo("sanctions-e2e-analyst"))
            .extract().path<String>("clearanceExpiresAt")
        // An expiry must lie in the future of the review; non-null would pass against EPOCH.
        org.assertj.core.api.Assertions.assertThat(Instant.parse(clearanceExpiresAt)).isAfter(before)

        given().get("/api/v3/sanctions/$checkId").then()
            .statusCode(200)
            .body("status", equalTo("WHITELISTED"))
            .body("reviewNote", equalTo("verified false positive by E2E"))
    }

    @Test
    @Order(6)
    @TestSecurity(user = "sanctions-e2e-viewer", roles = ["ROLE_VIEWER"])
    fun `6 - a viewer cannot screen and cannot review`() {
        given().contentType("application/json")
            .body(screenBody(UUID.randomUUID().toString(), "E2E Viewer Attempt"))
            .post("/api/v3/sanctions/screen").then()
            .statusCode(403)

        given().contentType("application/json")
            .body("""{"checkId":"$checkId","note":"x","newStatus":"CLEAR"}""")
            .post("/api/v1/sanctions/review").then()
            .statusCode(403)
    }

    @Test
    @Order(7)
    @TestSecurity(user = "sanctions-e2e-operator", roles = ["ROLE_OPERATOR"])
    fun `7 - the surface fails closed on a null alias, a null list type, and an unknown check`() {
        // #7867: [null] elements are rejected with a 400, never an NPE-driven 500.
        given().contentType("application/json")
            .body(
                """
                {"idempotencyKey":"${UUID.randomUUID()}","entityType":"INDIVIDUAL",
                "name":"E2E Null Alias","aliases":[null]}
                """.trimIndent().replace("\n", ""),
            )
            .post("/api/v3/sanctions/screen").then()
            .statusCode(400)

        given().contentType("application/json")
            .body(
                """
                {"idempotencyKey":"${UUID.randomUUID()}","entityType":"INDIVIDUAL",
                "name":"E2E Null ListType","listTypes":[null]}
                """.trimIndent().replace("\n", ""),
            )
            .post("/api/v3/sanctions/screen").then()
            .statusCode(400)

        given().contentType("application/json")
            .body("""{"checkId":"${UUID.randomUUID()}","note":"x","newStatus":"CLEAR"}""")
            .post("/api/v1/sanctions/review").then()
            .statusCode(404)

        given().get("/api/v1/sanctions/${UUID.randomUUID()}").then()
            .statusCode(404)
    }

    private fun screenBody(key: String, name: String) =
        """
        {"idempotencyKey":"$key","entityType":"INDIVIDUAL",
        "name":"$name","subjectId":"e2e-subject-1","profile":"CUSTOMER"}
        """.trimIndent().replace("\n", "")

    private companion object {
        const val LISTED = "Radcliffe E2E Sanctioned Person"
        const val LISTED_PEP = "E2E Listed Pep Person"
        val SCREEN_KEY = UUID.randomUUID().toString()
    }
}
