// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.it

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource

/**
 * #10916 through real HTTP and real Redis: the Idempotency-Key of `POST /api/v1/aml/cases` is
 * bound to the request it was first used for. A retry replays; the same key with a different body
 * is refused 422 and opens no case; key order and whitespace do not count as a different body.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
class AmlCaseCreateIdempotencyFingerprintIT {

    @Inject
    lateinit var dataSource: DataSource

    @Test
    @TestSecurity(user = "u-compliance", roles = ["ROLE_COMPLIANCE"])
    fun `same key and same body replays the first case and opens no second one`() {
        val party = UUID.randomUUID()
        val key = "idem-${UUID.randomUUID()}"
        val first = create(key, body(party, "ALERT-A"), expect = 201)
        assertThat(caseCount(party)).isEqualTo(1)

        Given {
            contentType("application/json")
            header("Idempotency-Key", key)
            body(body(party, "ALERT-A"))
        } When {
            post("/api/v1/aml/cases")
        } Then {
            statusCode(201)
            header("X-Idempotency-Replayed", "true")
            body("id", equalTo(first))
        }
        assertThat(caseCount(party)).isEqualTo(1)
    }

    @Test
    @TestSecurity(user = "u-compliance", roles = ["ROLE_COMPLIANCE"])
    fun `same key with a different body is refused 422 and opens no case`() {
        val party = UUID.randomUUID()
        val key = "idem-${UUID.randomUUID()}"
        create(key, body(party, "ALERT-A"), expect = 201)

        Given {
            contentType("application/json")
            header("Idempotency-Key", key)
            body(body(party, "ALERT-B"))
        } When {
            post("/api/v1/aml/cases")
        } Then {
            statusCode(422)
            body("code", equalTo("IDEMPOTENCY_KEY_REUSED"))
        }
        assertThat(caseCount(party)).isEqualTo(1)
    }

    @Test
    @TestSecurity(user = "u-compliance", roles = ["ROLE_COMPLIANCE"])
    fun `same body with different key order and whitespace still replays`() {
        val party = UUID.randomUUID()
        val key = "idem-${UUID.randomUUID()}"
        val first = create(key, body(party, "ALERT-A"), expect = 201)
        val reordered = """
            {  "alertCode" : "ALERT-A",  "riskLevel":"MEDIUM",
               "screeningType":"MANUAL_INVESTIGATION",   "customerReference":"REF-1",
               "partyId":"$party" }
        """.trimIndent()

        Given {
            contentType("application/json")
            header("Idempotency-Key", key)
            body(reordered)
        } When {
            post("/api/v1/aml/cases")
        } Then {
            statusCode(201)
            header("X-Idempotency-Replayed", "true")
            body("id", equalTo(first))
        }
        assertThat(caseCount(party)).isEqualTo(1)
    }

    private fun body(party: UUID, alertCode: String) =
        """{"partyId":"$party","customerReference":"REF-1","screeningType":"MANUAL_INVESTIGATION",""" +
            """"riskLevel":"MEDIUM","alertCode":"$alertCode"}"""

    private fun create(key: String, json: String, expect: Int): String = Given {
        contentType("application/json")
        header("Idempotency-Key", key)
        body(json)
    } When {
        post("/api/v1/aml/cases")
    } Then {
        statusCode(expect)
    } Extract {
        path("id")
    }

    private fun caseCount(party: UUID): Int = dataSource.connection.use { c ->
        c.prepareStatement("select count(*) from aml_cases where party_id = ?").use { ps ->
            ps.setObject(1, party)
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }
}
