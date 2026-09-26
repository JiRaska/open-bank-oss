// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.consent.integration

import com.openbank.consent.it.ConsentPostgresRedisTestResource
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
 * #10916 through real HTTP and real Redis: the idempotency key of `POST /api/v1/consents`
 * (tppTransactionId, else X-Request-ID) is bound to the request it was first used for. A retry
 * replays; the same key with a different body is refused 422 and creates nothing; key order and
 * whitespace do not count as a different body.
 */
@QuarkusTest
@QuarkusTestResource(ConsentPostgresRedisTestResource::class)
class ConsentCreateIdempotencyFingerprintIT {

    @Inject
    lateinit var dataSource: DataSource

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `same key and same body replays the first consent and creates no second one`() {
        val party = UUID.randomUUID()
        val key = "idem-${UUID.randomUUID()}"
        val first = create(key, body(party, "ACCOUNTS_READ"), expect = 201)
        assertThat(consentCount(party)).isEqualTo(1)

        Given {
            contentType("application/json")
            header("X-Request-ID", key)
            body(body(party, "ACCOUNTS_READ"))
        } When {
            post("/api/v1/consents")
        } Then {
            statusCode(201)
            header("X-Idempotency-Replayed", "true")
            body("id", equalTo(first))
        }
        assertThat(consentCount(party)).isEqualTo(1)
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `same key with a different body is refused 422 and creates nothing`() {
        val party = UUID.randomUUID()
        val key = "idem-${UUID.randomUUID()}"
        create(key, body(party, "ACCOUNTS_READ"), expect = 201)

        Given {
            contentType("application/json")
            header("X-Request-ID", key)
            body(body(party, "BALANCES_READ"))
        } When {
            post("/api/v1/consents")
        } Then {
            statusCode(422)
            body("code", equalTo("IDEMPOTENCY_KEY_REUSED"))
        }
        assertThat(consentCount(party)).isEqualTo(1)
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `same body with different key order and whitespace still replays`() {
        val party = UUID.randomUUID()
        val key = "idem-${UUID.randomUUID()}"
        val first = create(key, body(party, "ACCOUNTS_READ"), expect = 201)
        val reordered = """
            {  "validTo" : "$VALID_TO",  "scopes":[ "ACCOUNTS_READ" ],
               "accountIbans":["CZ6508000000192000145399"], "granteeName":"IT TPP",
               "granteeType":"TPP",    "granteeId":"tpp-it-idem", "partyId":"$party" }
        """.trimIndent()

        Given {
            contentType("application/json")
            header("X-Request-ID", key)
            body(reordered)
        } When {
            post("/api/v1/consents")
        } Then {
            statusCode(201)
            header("X-Idempotency-Replayed", "true")
            body("id", equalTo(first))
        }
        assertThat(consentCount(party)).isEqualTo(1)
    }

    private fun body(party: UUID, scope: String) =
        """{"partyId":"$party","granteeId":"tpp-it-idem","granteeType":"TPP","granteeName":"IT TPP",""" +
            """"scopes":["$scope"],"accountIbans":["CZ6508000000192000145399"],"validTo":"$VALID_TO"}"""

    private fun create(key: String, json: String, expect: Int): String = Given {
        contentType("application/json")
        header("X-Request-ID", key)
        body(json)
    } When {
        post("/api/v1/consents")
    } Then {
        statusCode(expect)
    } Extract {
        path("id")
    }

    private fun consentCount(party: UUID): Int = dataSource.connection.use { c ->
        c.prepareStatement("select count(*) from consents where party_id = ?").use { ps ->
            ps.setObject(1, party)
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

    private companion object {
        const val VALID_TO = "2099-01-01T00:00:00Z"
    }
}
