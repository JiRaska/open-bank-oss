// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.tppregistry.integration

import com.openbank.tppregistry.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource

/**
 * PR #10922 binds an `Idempotency-Key` to a SHA-256 fingerprint of (method, concrete path,
 * canonicalised body), reserved atomically via `IdempotencyStore.reserve`, so the same key with a
 * DIFFERENT request is refused (409 IDEMPOTENCY_KEY_REUSED) instead of replaying the first
 * response — which would otherwise answer a second, different registration with the first TPP's
 * entry. `registerTpp` is the endpoint under test; the count of `tpp_entries` rows for the reused
 * key is the side-effect assertion (a mocked use case can't see whether a SECOND registration
 * actually happened).
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
class TppRegistryIdempotencyFingerprintIT {

    @Inject
    lateinit var dataSource: DataSource

    private fun registerBody(tppId: String, name: String = "Fingerprint Probe") = """
        {"tppId":"$tppId","name":"$name","countryCode":"CZ","nca":"CNB",
         "roles":["AISP"],"qwacSubjectDn":"CN=QWAC","qsealSubjectDn":null}
    """.trimIndent()

    private fun countByTppId(tppId: String): Int = dataSource.connection.use { conn ->
        val ps = conn.prepareStatement("SELECT count(*) FROM tpp_entries WHERE tpp_id = ?")
        ps.setString(1, tppId)
        val rs = ps.executeQuery()
        rs.next()
        rs.getInt(1)
    }

    @Test
    @TestSecurity(user = "idem-it", roles = ["ROLE_ADMIN"])
    fun `same key and same body replays the first response`() {
        val tppId = "CZ-CNB-IDEM-${UUID.randomUUID().toString().take(8)}"
        val key = UUID.randomUUID().toString()
        val body = registerBody(tppId)

        Given {
            contentType("application/json")
            header("Idempotency-Key", key)
            body(body)
        } When {
            post("/api/v1/tpp-registry")
        } Then { statusCode(201) }

        Given {
            contentType("application/json")
            header("Idempotency-Key", key)
            body(body)
        } When {
            post("/api/v1/tpp-registry")
        } Then {
            statusCode(201)
            header("X-Idempotency-Replayed", "true")
        }

        assertThat(countByTppId(tppId)).describedAs("no second row for a genuine replay").isEqualTo(1)
    }

    @Test
    @TestSecurity(user = "idem-it", roles = ["ROLE_ADMIN"])
    fun `same key with a different body is refused, not replayed`() {
        // The service's own cache key is "tpp:register:<tppId>:<Idempotency-Key>" — scoped by
        // tppId, which is decided BEFORE the fingerprint check runs. So the case this test targets
        // (the fingerprint catching a body change the cache key itself does not) must keep tppId
        // identical between the two calls and vary a different field instead.
        val tppId = "CZ-CNB-IDEM-${UUID.randomUUID().toString().take(8)}"
        val key = UUID.randomUUID().toString()

        Given {
            contentType("application/json")
            header("Idempotency-Key", key)
            body(registerBody(tppId, name = "Fingerprint Probe"))
        } When {
            post("/api/v1/tpp-registry")
        } Then { statusCode(201) }

        Given {
            contentType("application/json")
            header("Idempotency-Key", key)
            body(registerBody(tppId, name = "A Different Name"))
        } When {
            post("/api/v1/tpp-registry")
        } Then {
            statusCode(409)
            body("code", org.hamcrest.Matchers.equalTo("IDEMPOTENCY_KEY_REUSED"))
        }

        // Exactly one row: the first (accepted) call registered it, the second (rejected) call
        // must not have created a second row or mutated the first one's name.
        assertThat(countByTppId(tppId)).describedAs("the reused-key request must not register again").isEqualTo(1)
    }

    @Test
    @TestSecurity(user = "idem-it", roles = ["ROLE_ADMIN"])
    fun `a use case failure releases the reservation so a retry with the same key and body is not stuck in-flight`() {
        // Register the tppId once (unkeyed) so the SECOND registration below fails at the
        // use-case layer with TppAlreadyExistsException (409 CONFLICT) rather than succeeding —
        // proving `release()` runs on that failure path, not just on success.
        val tppId = "CZ-CNB-IDEM-${UUID.randomUUID().toString().take(8)}"
        Given {
            contentType("application/json")
            body(registerBody(tppId))
        } When {
            post("/api/v1/tpp-registry")
        } Then { statusCode(201) }

        val key = UUID.randomUUID().toString()
        val body = registerBody(tppId, name = "Second Attempt")

        // First attempt under `key`: reserve() succeeds, the use case throws
        // TppAlreadyExistsException, and the resource must release() before rethrowing.
        Given {
            contentType("application/json")
            header("Idempotency-Key", key)
            body(body)
        } When {
            post("/api/v1/tpp-registry")
        } Then {
            statusCode(409)
            body("error", org.hamcrest.Matchers.equalTo("CONFLICT"))
        }

        // If release() had NOT run, this identical retry would hit the still-held in-flight
        // marker and answer 409 IDEMPOTENCY_REQUEST_IN_PROGRESS instead of reaching the use case
        // again. It must reach the use case again and get the SAME business failure, not the
        // idempotency one — proving the key was freed.
        Given {
            contentType("application/json")
            header("Idempotency-Key", key)
            body(body)
        } When {
            post("/api/v1/tpp-registry")
        } Then {
            statusCode(409)
            body("error", org.hamcrest.Matchers.equalTo("CONFLICT"))
        }
    }

    @Test
    @TestSecurity(user = "idem-it", roles = ["ROLE_ADMIN"])
    fun `same key and same body with different JSON whitespace and key order still replays`() {
        val tppId = "CZ-CNB-IDEM-${UUID.randomUUID().toString().take(8)}"
        val key = UUID.randomUUID().toString()
        val body = registerBody(tppId)
        // Same fields, different key order and extra whitespace — canonicalisation must equate them.
        val reordered = """
            {
              "countryCode" : "CZ",
              "tppId": "$tppId",
              "nca": "CNB",
              "roles": ["AISP"],
              "qwacSubjectDn": "CN=QWAC",
              "qsealSubjectDn": null,
              "name": "Fingerprint Probe"
            }
        """.trimIndent()

        Given {
            contentType("application/json")
            header("Idempotency-Key", key)
            body(body)
        } When {
            post("/api/v1/tpp-registry")
        } Then { statusCode(201) }

        Given {
            contentType("application/json")
            header("Idempotency-Key", key)
            body(reordered)
        } When {
            post("/api/v1/tpp-registry")
        } Then {
            statusCode(201)
            header("X-Idempotency-Replayed", "true")
        }

        assertThat(countByTppId(tppId)).isEqualTo(1)
    }
}
