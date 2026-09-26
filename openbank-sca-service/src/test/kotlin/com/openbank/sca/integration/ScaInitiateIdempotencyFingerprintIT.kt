// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.integration

import com.openbank.sca.it.PostgresRedisTestResource
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
 * #10916 through real HTTP and real Redis: an Idempotency-Key on `POST /api/v1/sca/challenges`
 * is bound to the request it was first used for. A retry replays; the same key with a different
 * body is refused 409 IDEMPOTENCY_KEY_REUSED and mints nothing; key order / whitespace do not count
 * as a different body; a request that fails releases its reservation so the key can be reused.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
class ScaInitiateIdempotencyFingerprintIT {

    @Inject
    lateinit var dataSource: DataSource

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `same key and same body replays the first challenge and mints no second one`() {
        val party = UUID.randomUUID()
        val key = "idem-${UUID.randomUUID()}"
        val first = initiate(key, body(party, "https://example.test/a"), expect = 201)
        assertThat(challengeCount(party)).isEqualTo(1)

        Given {
            contentType("application/json")
            header("Idempotency-Key", key)
            body(body(party, "https://example.test/a"))
        } When {
            post("/api/v1/sca/challenges")
        } Then {
            statusCode(201)
            header("X-Idempotency-Replayed", "true")
            body("id", equalTo(first))
        }
        assertThat(challengeCount(party)).isEqualTo(1)
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `same key with a different body is refused 409 and mints nothing`() {
        val party = UUID.randomUUID()
        val key = "idem-${UUID.randomUUID()}"
        initiate(key, body(party, "https://example.test/a"), expect = 201)

        Given {
            contentType("application/json")
            header("Idempotency-Key", key)
            body(body(party, "https://example.test/b"))
        } When {
            post("/api/v1/sca/challenges")
        } Then {
            statusCode(409)
            body("code", equalTo("IDEMPOTENCY_KEY_REUSED"))
        }
        assertThat(challengeCount(party)).isEqualTo(1)
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `same body with different key order and whitespace still replays`() {
        val party = UUID.randomUUID()
        val key = "idem-${UUID.randomUUID()}"
        val first = initiate(key, body(party, "https://example.test/a"), expect = 201)
        val reordered = """
            {  "redirectUrl" : "https://example.test/a",
               "preferredMethod":"PUSH_NOTIFICATION",   "purpose":"LOGIN",
               "partyId":"$party" }
        """.trimIndent()

        Given {
            contentType("application/json")
            header("Idempotency-Key", key)
            body(reordered)
        } When {
            post("/api/v1/sca/challenges")
        } Then {
            statusCode(201)
            header("X-Idempotency-Replayed", "true")
            body("id", equalTo(first))
        }
        assertThat(challengeCount(party)).isEqualTo(1)
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `a request that fails releases the key so a retry is not stuck in progress`() {
        val party = UUID.randomUUID()
        val key = "idem-${UUID.randomUUID()}"
        // TOTP has no delivery path: the use case refuses it (422) after the key was reserved.
        val undeliverable =
            """{"partyId":"$party","purpose":"LOGIN","preferredMethod":"TOTP","redirectUrl":"https://example.test/a"}"""
        repeat(2) {
            Given {
                contentType("application/json")
                header("Idempotency-Key", key)
                body(undeliverable)
            } When {
                post("/api/v1/sca/challenges")
            } Then {
                // Not 409 IDEMPOTENCY_REQUEST_IN_PROGRESS: the failed attempt released its marker.
                statusCode(422)
                body("code", equalTo("VALIDATION_ERROR"))
            }
        }
        // The corrected request may reuse the key — no stale marker binds it to the failed body.
        initiate(key, body(party, "https://example.test/a"), expect = 201)
        assertThat(challengeCount(party)).isEqualTo(1)
    }

    private fun body(party: UUID, redirectUrl: String) =
        """{"partyId":"$party","purpose":"LOGIN","preferredMethod":"PUSH_NOTIFICATION","redirectUrl":"$redirectUrl"}"""

    private fun initiate(key: String, json: String, expect: Int): String = Given {
        contentType("application/json")
        header("Idempotency-Key", key)
        body(json)
    } When {
        post("/api/v1/sca/challenges")
    } Then {
        statusCode(expect)
    } Extract {
        path("id")
    }

    private fun challengeCount(party: UUID): Int = dataSource.connection.use { c ->
        c.prepareStatement("select count(*) from sca_challenges where party_id = ?").use { ps ->
            ps.setObject(1, party)
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }
}
