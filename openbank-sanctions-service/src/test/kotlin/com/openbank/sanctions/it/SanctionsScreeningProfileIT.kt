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
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasKey
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource

/**
 * Screening profiles and inputs beyond the name (issue #15723), through real HTTP and Postgres.
 *
 * Every profile gets a must-hit AND a must-clear case, because each half alone is satisfied by a
 * broken implementation: "always HIT" passes the first, "always CLEAR" the second. The fixtures
 * are synthetic on purpose — invented names, and the user-assigned ISO 3166 codes `XE` / `XH`
 * that no real country carries — so nothing here asserts which real jurisdictions are listed.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
class SanctionsScreeningProfileIT {

    @Inject
    lateinit var dataSource: DataSource

    private fun sql(statement: String) {
        dataSource.connection.use { c -> c.createStatement().use { it.execute(statement) } }
    }

    @BeforeEach
    fun seed() {
        cleanUp()
        sql(
            """
            INSERT INTO sanctions_entries (list_type, external_id, entity_type, primary_name, search_text) VALUES
              ('OFAC_SDN',      'profile-it-sanctioned', 'INDIVIDUAL', '$SANCTIONED', lower('$SANCTIONED')),
              ('CZ_TERRORISTS', 'profile-it-terrorist',  'INDIVIDUAL', '$TERRORIST',  lower('$TERRORIST')),
              ('PEP_GLOBAL',    'profile-it-pep',        'INDIVIDUAL', '$PEP',        lower('$PEP'))
            """.trimIndent(),
        )
        sql(
            """
            INSERT INTO sanctions_jurisdictions
              (list_type, source_list, country_code, measure, version, source_ref, published_on, verified_on)
            VALUES
              ('EU_CONSOLIDATED', 'SYNTHETIC_TEST', '$EMBARGOED', 'EMBARGO',   1, 'synthetic test fixture',
               DATE '2026-01-01', DATE '2026-01-01'),
              ('FATF_HIGH_RISK',  'SYNTHETIC_TEST', '$HIGH_RISK', 'HIGH_RISK', 1, 'synthetic test fixture',
               DATE '2026-01-01', DATE '2026-01-01')
            """.trimIndent(),
        )
    }

    @AfterEach
    fun cleanUp() {
        sql("DELETE FROM sanctions_entries WHERE external_id LIKE 'profile-it-%'")
        sql("DELETE FROM sanctions_jurisdictions WHERE source_ref = 'synthetic test fixture'")
        sql("DELETE FROM sanctions_change_journal WHERE external_id LIKE 'profile-it-%'")
    }

    private fun screen(name: String, extra: String = "", major: Int = 3) = Given {
        contentType("application/json")
        body("""{"idempotencyKey":"${UUID.randomUUID()}","entityType":"INDIVIDUAL","name":"$name"$extra}""")
    } When {
        post("/api/v$major/sanctions/screen")
    }

    // ── PAYMENT ───────────────────────────────────────────────────────────────

    @Test
    fun `PAYMENT must-hit - a sanctioned name is a HIT of kind SANCTIONS`() {
        screen(SANCTIONED, ""","profile":"PAYMENT"""") Then {
            statusCode(201)
            body("status", equalTo("HIT"))
            body("profile", equalTo("PAYMENT"))
            body("matches.matchKind", hasItem("SANCTIONS"))
            body("$", not(hasKey("pepStatus")))
        }
    }

    @Test
    fun `PAYMENT must-hit - the national terrorist list is consulted`() {
        screen(TERRORIST, ""","profile":"PAYMENT"""") Then {
            statusCode(201)
            body("status", equalTo("HIT"))
            body("matches.matchKind", hasItem("TERRORIST"))
            body("checkedLists", hasItem("CZ_TERRORISTS"))
            body("checkedLists", not(hasItem("PEP_GLOBAL")))
        }
    }

    @Test
    fun `PAYMENT must-clear - a PEP name is not a payment sanctions hit`() {
        screen(PEP, ""","profile":"PAYMENT"""") Then {
            statusCode(201)
            body("status", equalTo("CLEAR"))
            body("matches", hasSize<Any>(0))
        }
    }

    @Test
    fun `control - the same PEP name is still a HIT for a legacy profile-less v1 caller`() {
        // Without this, the must-clear above would also pass if the PEP fixture simply never matched.
        screen(PEP, major = 1) Then {
            statusCode(201)
            body("status", equalTo("HIT"))
            body("$", not(hasKey("profile")))
            body("matches[0]", not(hasKey("matchKind")))
        }
    }

    @Test
    fun `PAYMENT must-clear - an unlisted name in an unlisted country is CLEAR`() {
        screen(HARMLESS, ""","profile":"PAYMENT","country":"$UNLISTED","bankIdentifier":"TEST${UNLISTED}AA"""") Then {
            statusCode(201)
            body("status", equalTo("CLEAR"))
            body("country", equalTo(UNLISTED))
            body("bankIdentifier", equalTo("TEST${UNLISTED}AA"))
        }
    }

    // ── Jurisdiction ──────────────────────────────────────────────────────────

    @Test
    fun `an embargoed country is a HIT independent of the name`() {
        screen(HARMLESS, ""","profile":"PAYMENT","country":"$EMBARGOED"""") Then {
            statusCode(201)
            body("status", equalTo("HIT"))
            body("matches", hasSize<Any>(1))
            body("matches[0].matchKind", equalTo("JURISDICTION"))
            body("matches[0].listType", equalTo("EU_CONSOLIDATED"))
            body("matches[0].matchedId", equalTo("country:$EMBARGOED"))
        }
    }

    @Test
    fun `a bank identifier in an embargoed country is a HIT without any country field`() {
        screen(HARMLESS, ""","profile":"PAYMENT","bankIdentifier":"TEST${EMBARGOED}AAXXX"""") Then {
            statusCode(201)
            body("status", equalTo("HIT"))
            body("matches[0].matchKind", equalTo("JURISDICTION"))
        }
    }

    @Test
    fun `a high-risk country goes to review, not to a block`() {
        screen(HARMLESS, ""","profile":"PAYMENT","country":"$HIGH_RISK"""") Then {
            statusCode(201)
            body("status", equalTo("POTENTIAL_HIT"))
            body("matches[0].matchKind", equalTo("JURISDICTION"))
            // ScreeningPolicy blocks a POTENTIAL_HIT above 0.85; a high-risk listing must stay REVIEW.
            body("overallScore", equalTo(0.65f))
        }
    }

    @Test
    fun `a retired jurisdiction row no longer matches`() {
        sql("UPDATE sanctions_jurisdictions SET active = false WHERE country_code = '$EMBARGOED'")
        screen(HARMLESS, ""","profile":"PAYMENT","country":"$EMBARGOED"""") Then {
            statusCode(201)
            body("status", equalTo("CLEAR"))
        }
    }

    // ── CUSTOMER ──────────────────────────────────────────────────────────────

    @Test
    fun `CUSTOMER must-hit - a sanctioned name is a HIT and the PEP outcome is CLEAR`() {
        screen(SANCTIONED, ""","profile":"CUSTOMER"""") Then {
            statusCode(201)
            body("status", equalTo("HIT"))
            body("pepStatus", equalTo("CLEAR"))
        }
    }

    @Test
    fun `CUSTOMER - a PEP name is reported as PEP and never raises the sanctions status`() {
        screen(PEP, ""","profile":"CUSTOMER"""") Then {
            statusCode(201)
            body("status", equalTo("CLEAR"))
            body("pepStatus", equalTo("HIT"))
            body("overallScore", equalTo(0.0f))
            body("matches", hasSize<Any>(1))
            body("matches[0].matchKind", equalTo("PEP"))
            body("checkedLists", hasItem("PEP_GLOBAL"))
            body("checkedLists", hasItem("CZ_TERRORISTS"))
        }
    }

    @Test
    fun `CUSTOMER must-clear - an unlisted name is CLEAR on both outcomes`() {
        screen(HARMLESS, ""","profile":"CUSTOMER"""") Then {
            statusCode(201)
            body("status", equalTo("CLEAR"))
            body("pepStatus", equalTo("CLEAR"))
            body("matches", hasSize<Any>(0))
        }
    }

    // ── Contract edges ────────────────────────────────────────────────────────

    @Test
    fun `the profile and its outcome survive a read back from the database`() {
        val id = screen(PEP, ""","profile":"CUSTOMER","country":"$UNLISTED"""").then().statusCode(201)
            .extract().path<String>("id")
        Given { contentType("application/json") } When { get("/api/v3/sanctions/$id") } Then {
            statusCode(200)
            body("profile", equalTo("CUSTOMER"))
            body("pepStatus", equalTo("HIT"))
            body("country", equalTo(UNLISTED))
            body("matches[0].matchKind", equalTo("PEP"))
        }
        // A check carrying the new fields is not representable in the frozen v1 schema.
        Given { contentType("application/json") } When { get("/api/v1/sanctions/$id") } Then { statusCode(404) }
    }

    @Test
    fun `a profile-less v3 screen keeps the legacy wire shape`() {
        screen(HARMLESS) Then {
            statusCode(201)
            body("checkedLists", hasSize<Any>(ALL_LISTS))
            body("$", not(hasKey("profile")))
            body("$", not(hasKey("pepStatus")))
            body("$", not(hasKey("country")))
            body("$", not(hasKey("bankIdentifier")))
            body("reviewedBy", nullValue())
        }
    }

    @Test
    fun `invalid inputs are rejected with 400, never screened narrower than asked`() {
        screen(HARMLESS, ""","profile":"PAYMENTS"""") Then { statusCode(400) }
        screen(HARMLESS, ""","profile":"PAYMENT","listTypes":["OFAC_SDN"]""") Then { statusCode(400) }
        screen(HARMLESS, ""","country":"XEE"""") Then { statusCode(400) }
        screen(HARMLESS, ""","bankIdentifier":"NOT-A-BIC"""") Then { statusCode(400) }
        screen(HARMLESS, ""","profile":"PAYMENT"""", major = 1) Then { statusCode(400) }
        screen(HARMLESS, ""","country":"$EMBARGOED"""", major = 1) Then { statusCode(400) }
    }

    private companion object {
        const val SANCTIONED = "Xanthippe Quorndale"
        const val TERRORIST = "Borivoj Zzyzxland"
        const val PEP = "Ottoline Pepfixture"
        const val HARMLESS = "Harmless Nobodyson"

        // ISO 3166-1 user-assigned codes: guaranteed never to denote a real country.
        const val EMBARGOED = "XE"
        const val HIGH_RISK = "XH"
        const val UNLISTED = "XC"
        const val ALL_LISTS = 8
    }
}
