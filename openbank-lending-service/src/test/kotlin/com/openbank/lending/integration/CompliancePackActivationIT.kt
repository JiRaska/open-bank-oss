// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.integration

import com.openbank.lending.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import javax.sql.DataSource

/**
 * The ADR-0212 D4 four-eyes pack activation, driven end-to-end against a real database.
 *
 * WHY THIS TEST HAD TO EXIST BEFORE THE FEATURE COULD BE BELIEVED
 *
 * The whole workflow shipped — endpoints, `@RolesAllowed`, the maker-checker invariant, the console —
 * and had never once been executed. OPA denied `lending.compliance.propose` outright (#3402), so the
 * first request to reach the service body did so on 2026-08-02, and it found TWO defects stacked
 * behind that 403:
 *
 *  1. `No current Mutiny.Session found` — the repository carried neither `@WithTransaction` nor
 *     `@WithSession`, so every call failed before touching a row.
 *  2. `persistAndFlush` on an application-assigned `@Id`. Hibernate cannot distinguish transient from
 *     detached when the id is non-null either way, so it schedules an INSERT for both and the CHECKER
 *     leg — which loads a PROPOSED row, flips its state and saves — dies at flush with
 *     `duplicate key value violates ... _pkey`. Same defect as consent-service (#1521) and
 *     standing-order (#2079).
 *
 * Neither is visible to a test that mocks `CompliancePackActivationRepository`, and defect 2 is not
 * visible even to a real-DB test that only proposes: the INSERT is correct on the maker leg. It takes
 * the SECOND write to the SAME row, which is why maker and checker are separate ordered tests here
 * rather than one helper that asserts a happy path.
 *
 * A direct CDI call into a `@WithTransaction` repository from the bare test thread fails with
 * "No current Vertx context found" — only a real HTTP request carries a Vert.x context — so the flow
 * goes through the REST endpoints and the assertions read the table over plain JDBC.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@QuarkusTestResource(PostgresRedisTestResource::class)
class CompliancePackActivationIT {

    @Inject
    lateinit var dataSource: DataSource

    private lateinit var proposalId: String

    /**
     * The REAL shipped pack, with only its version changed. An invented payload would be rejected by
     * `CompliancePackParser`/`CompliancePackCompiler` and the maker leg would fail for a reason that
     * has nothing to do with what this test is about; version 999 keeps it from colliding with a
     * genuinely activated `cz-consumer-credit-v1` in the same database.
     */
    private val pack: String by lazy {
        val raw = checkNotNull(javaClass.getResourceAsStream("/compliance-packs/cz-consumer-credit-v1.json")) {
            "the shipped CZ pack must be on the test classpath"
        }.bufferedReader().readText()
        raw.replace(Regex("\"version\"\\s*:\\s*1"), "\"version\": $PACK_VERSION")
    }

    private companion object {
        const val PACK_VERSION = 999
    }

    @Test
    @Order(1)
    @TestSecurity(user = "pack-it-maker", roles = ["ROLE_COMPLIANCE"])
    fun `1 - maker proposes a pack`() {
        val response = Given {
            contentType("application/json")
            body(pack)
        } When {
            post("/api/v1/lending/compliance-packs/proposals")
        } Then {
            // 422 here is the "No current Mutiny.Session found" regression: the endpoint maps every
            // business-rule failure to 422, so a missing @WithTransaction reads as a domain rejection.
            statusCode(201)
        } Extract {
            this
        }
        proposalId = response.jsonPath().getString("id")
        assertThat(proposalId).isNotBlank()
        assertThat(response.jsonPath().getString("proposedAt")).isNotBlank()
        assertThat(response.jsonPath().getInt("pack.version")).isEqualTo(PACK_VERSION)
        assertThat(response.jsonPath().getString("pack.jurisdiction")).isEqualTo("CZ")

        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT state, proposed_by, decided_by FROM compliance_pack_activation WHERE id = ?::uuid",
            ).use { st ->
                st.setString(1, proposalId)
                st.executeQuery().use { rs ->
                    assertThat(rs.next()).describedAs("a compliance_pack_activation row for $proposalId").isTrue()
                    assertThat(rs.getString("state")).isEqualTo("PROPOSED")
                    assertThat(rs.getString("proposed_by")).isEqualTo("pack-it-maker")
                    // Nobody has decided yet. A checker stamped at proposal time would mean the
                    // four-eyes record is written by whoever proposes, which is the thing it prevents.
                    assertThat(rs.getString("decided_by")).isNull()
                }
            }
        }
    }

    @Test
    @Order(2)
    @TestSecurity(user = "pack-it-maker", roles = ["ROLE_COMPLIANCE"])
    fun `2 - the maker cannot also be the checker`() {
        Given {
            contentType("application/json")
            body("""{"approve":true,"reason":"same person"}""")
        } When {
            post("/api/v1/lending/compliance-packs/proposals/$proposalId/decide")
        } Then {
            // MakerCheckerViolation. This is the invariant the whole control exists for, so it is
            // asserted before the happy path — a green happy path over a broken invariant is worse
            // than no test, because it reads as the control working.
            statusCode(422)
        }

        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT state FROM compliance_pack_activation WHERE id = ?::uuid").use { st ->
                st.setString(1, proposalId)
                st.executeQuery().use { rs ->
                    assertThat(rs.next()).isTrue()
                    assertThat(
                        rs.getString("state"),
                    ).describedAs("a refused decision must not move the row").isEqualTo("PROPOSED")
                }
            }
        }
    }

    @Test
    @Order(3)
    @TestSecurity(user = "pack-it-checker", roles = ["ROLE_COMPLIANCE"])
    fun `3 - a different checker approves, and the row is UPDATED not re-inserted`() {
        Given {
            contentType("application/json")
            body("""{"approve":true,"reason":"reviewed against ADR-0212"}""")
        } When {
            post("/api/v1/lending/compliance-packs/proposals/$proposalId/decide")
        } Then {
            // 500 here is the persist-vs-merge defect: this is the second write to the same primary
            // key, so `persistAndFlush` fails with `duplicate key value violates ... _pkey`.
            statusCode(200)
            body("decisionReason", org.hamcrest.Matchers.equalTo("reviewed against ADR-0212"))
            body("decidedBy", org.hamcrest.Matchers.equalTo("pack-it-checker"))
            body("pack.version", org.hamcrest.Matchers.equalTo(PACK_VERSION))
        }

        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT state, proposed_by, decided_by FROM compliance_pack_activation WHERE id = ?::uuid",
            ).use { st ->
                st.setString(1, proposalId)
                st.executeQuery().use { rs ->
                    assertThat(rs.next()).isTrue()
                    assertThat(rs.getString("state")).isIn("APPROVED", "EXECUTED")
                    assertThat(rs.getString("proposed_by")).isEqualTo("pack-it-maker")
                    assertThat(rs.getString("decided_by")).isEqualTo("pack-it-checker")
                }
            }

            // Exactly one row. A merge that behaved like a persist would leave the original PROPOSED
            // row and add a second one — the count is what distinguishes an UPDATE from an INSERT,
            // and reading the row by id alone cannot.
            conn.prepareStatement(
                "SELECT count(*) FROM compliance_pack_activation WHERE pack_version = $PACK_VERSION",
            ).use { st ->
                st.executeQuery().use { rs ->
                    rs.next()
                    assertThat(
                        rs.getInt(1),
                    ).describedAs("the decision must UPDATE the proposal, not insert a second row").isEqualTo(1)
                }
            }
        }
    }

    @Test
    @Order(4)
    @TestSecurity(user = "pack-it-reader", roles = ["ROLE_COMPLIANCE"])
    fun `4 - the approved pack shows up as active`() {
        val body = Given {
            contentType("application/json")
        } When {
            get("/api/v1/lending/compliance-packs/active")
        } Then {
            statusCode(200)
        } Extract {
            asString()
        }
        // An empty list here is indistinguishable from "nothing activated yet" in the console, which
        // is precisely how the OPA denial stayed invisible for the life of the feature (#3402).
        assertThat(body).contains("\"packVersion\":$PACK_VERSION")
        assertThat(body).contains("\"pack\":{")
        assertThat(body).contains("\"jurisdiction\":\"CZ\"")
    }
}
