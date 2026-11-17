// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.it

import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource

/**
 * The review band of a NAME match (0.65 <= score < 0.85), through real HTTP and the real
 * `sanctions_entries` trigram query.
 *
 * The candidate search used the HIT threshold as its floor for every caller, so the query never
 * returned a candidate below 0.85 and `POTENTIAL_HIT` could not be produced by a name at all: a
 * near-miss spelling of a listed person screened `CLEAR`. The CUSTOMER profile now searches down
 * to the review floor; PAYMENT and the profile-less path keep the HIT floor, because a payment
 * has no state to hold a review in yet (issue #15724).
 *
 * The fixture is only meaningful while its similarity really sits inside the band, so the first
 * test measures that against the database instead of trusting the constant.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
class SanctionsFuzzyNameBandIT {

    @Inject
    lateinit var dataSource: DataSource

    private fun sql(statement: String) {
        dataSource.connection.use { c -> c.createStatement().use { it.execute(statement) } }
    }

    private fun similarity(query: String, listed: String): Double = dataSource.connection.use { c ->
        c.prepareStatement("SELECT word_similarity(?, ?)").use { ps ->
            ps.setString(1, query.lowercase())
            ps.setString(2, listed.lowercase())
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getDouble(1)
            }
        }
    }

    @BeforeEach
    fun seed() {
        cleanUp()
        sql(
            """
            INSERT INTO sanctions_entries (list_type, external_id, entity_type, primary_name, search_text) VALUES
              ('OFAC_SDN',   'fuzzy-it-sanctioned', 'INDIVIDUAL', '$LISTED',     lower('$LISTED')),
              ('PEP_GLOBAL', 'fuzzy-it-pep',        'INDIVIDUAL', '$LISTED_PEP', lower('$LISTED_PEP'))
            """.trimIndent(),
        )
    }

    @AfterEach
    fun cleanUp() {
        sql("DELETE FROM sanctions_entries WHERE external_id LIKE 'fuzzy-it-%'")
        sql("DELETE FROM sanctions_change_journal WHERE external_id LIKE 'fuzzy-it-%'")
    }

    private fun screen(name: String, extra: String = "", major: Int = 3) = Given {
        contentType("application/json")
        body("""{"idempotencyKey":"${UUID.randomUUID()}","entityType":"INDIVIDUAL","name":"$name"$extra}""")
    } When {
        post("/api/v$major/sanctions/screen")
    }

    @Test
    fun `control - the fixtures sit where the tests below assume`() {
        assertThat(similarity(NEAR_MISS, LISTED)).isGreaterThanOrEqualTo(REVIEW_FLOOR).isLessThan(HIT_FLOOR)
        assertThat(similarity(NEAR_MISS_PEP, LISTED_PEP)).isGreaterThanOrEqualTo(REVIEW_FLOOR).isLessThan(HIT_FLOOR)
        assertThat(similarity(FAR_MISS, LISTED)).isLessThan(REVIEW_FLOOR)
    }

    @Test
    fun `CUSTOMER - a near-miss spelling of a sanctioned name goes to review`() {
        screen(NEAR_MISS, ""","profile":"CUSTOMER"""") Then {
            statusCode(201)
            body("status", equalTo("POTENTIAL_HIT"))
            body("pepStatus", equalTo("CLEAR"))
            body("matches", hasSize<Any>(1))
            body("matches[0].matchKind", equalTo("SANCTIONS"))
            body("matches[0].matchedId", equalTo("fuzzy-it-sanctioned"))
        }
    }

    @Test
    fun `CUSTOMER - a near-miss spelling of a PEP is a potential PEP and never a sanctions status`() {
        screen(NEAR_MISS_PEP, ""","profile":"CUSTOMER"""") Then {
            statusCode(201)
            body("status", equalTo("CLEAR"))
            body("pepStatus", equalTo("POTENTIAL_HIT"))
            body("matches[0].matchKind", equalTo("PEP"))
        }
    }

    @Test
    fun `CUSTOMER must-clear - a spelling below the review floor stays CLEAR`() {
        screen(FAR_MISS, ""","profile":"CUSTOMER"""") Then {
            statusCode(201)
            body("status", equalTo("CLEAR"))
            body("pepStatus", equalTo("CLEAR"))
            body("matches", hasSize<Any>(0))
        }
    }

    @Test
    fun `CUSTOMER - an exact name is still a HIT`() {
        screen(LISTED, ""","profile":"CUSTOMER"""") Then {
            statusCode(201)
            body("status", equalTo("HIT"))
        }
    }

    @Test
    fun `PAYMENT - the same near-miss stays CLEAR, a payment has nowhere to hold a review`() {
        screen(NEAR_MISS, ""","profile":"PAYMENT"""") Then {
            statusCode(201)
            body("status", equalTo("CLEAR"))
            body("matches", hasSize<Any>(0))
            body("overallScore", equalTo(0.0f))
        }
    }

    @Test
    fun `a profile-less screen keeps the HIT floor on every API major`() {
        for (major in listOf(1, 3)) {
            screen(NEAR_MISS, major = major) Then {
                statusCode(201)
                body("status", equalTo("CLEAR"))
                body("matches", hasSize<Any>(0))
                body("overallScore", equalTo(0.0f))
            }
        }
    }

    private companion object {
        const val LISTED = "Xanthippe Quorndale"
        const val NEAR_MISS = "Xanthippa Quorndala"
        const val FAR_MISS = "Xantippe Kvorndale"
        const val LISTED_PEP = "Ottoline Pepfixture"
        const val NEAR_MISS_PEP = "Ottolina Pepfixtura"
        const val REVIEW_FLOOR = 0.65
        const val HIT_FLOOR = 0.85
    }
}
