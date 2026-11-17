// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tppregistry.e2e

import com.openbank.libs.testing.containers.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.util.UUID

/**
 * The TPP admission journey over the real HTTP surface: an admin registers a TPP under an
 * idempotency key, the registry check authorizes its declared role, and blacklisting revokes
 * that authorization — while the endpoints prove the boundaries the service enforces: a genuine
 * replay is answered from the first response, the same key with a different body is refused as
 * `IDEMPOTENCY_KEY_REUSED`, a missing query parameter is a 400 rather than a 500, an unknown
 * TPP is unauthorized, and a viewer cannot register anyone.
 *
 * The module's tests prove one property each; this proves the route sequence serves the whole
 * "TPP is admitted, authorized, and revoked" story, so a wiring regression that deregisters a
 * route (#3371), replays a different body, or keeps authorizing a blacklisted TPP fails here
 * even when every property-level assertion stays green.
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_tpp_registry_e2e")],
)
@TestMethodOrder(OrderAnnotation::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestSecurity(user = "admin@openbank.test", roles = ["ROLE_ADMIN"])
class TppAdmissionJourneyE2E {

    private val tppId = "CZ-CNB-E2E-${UUID.randomUUID().toString().take(8).uppercase()}"
    private val idempotencyKey = UUID.randomUUID().toString()

    @Test
    @Order(1)
    fun `1 - an admin registers a TPP under an idempotency key`() {
        Given {
            contentType("application/json")
            header("Idempotency-Key", idempotencyKey)
            body(registerBody("Example Fintech"))
        } When {
            post("/api/v1/tpp-registry")
        } Then {
            statusCode(201)
            body("tppId", equalTo(tppId))
            body("name", equalTo("Example Fintech"))
        }
    }

    @Test
    @Order(2)
    fun `2 - the same key and body replays the first response`() {
        Given {
            contentType("application/json")
            header("Idempotency-Key", idempotencyKey)
            body(registerBody("Example Fintech"))
        } When {
            post("/api/v1/tpp-registry")
        } Then {
            statusCode(201)
            header("X-Idempotency-Replayed", equalTo("true"))
            body("tppId", equalTo(tppId))
        }
    }

    @Test
    @Order(3)
    fun `3 - the same key with a different body is refused not replayed`() {
        Given {
            contentType("application/json")
            header("Idempotency-Key", idempotencyKey)
            body(registerBody("A Different Name"))
        } When {
            post("/api/v1/tpp-registry")
        } Then {
            statusCode(409)
            body("code", equalTo("IDEMPOTENCY_KEY_REUSED"))
        }
    }

    @Test
    @Order(4)
    fun `4 - the registry check authorizes the registered role and refuses strangers`() {
        Given {
            queryParam("tppId", tppId)
            queryParam("role", "AISP")
        }
            .When { get("/api/v1/tpp-registry/check") }
            .Then {
                statusCode(200)
                body("authorized", equalTo(true))
            }

        Given {
            queryParam("tppId", "CZ-CNB-NOPE-${UUID.randomUUID().toString().take(8)}")
            queryParam("role", "AISP")
        }
            .When { get("/api/v1/tpp-registry/check") }
            .Then {
                statusCode(403)
                body("authorized", equalTo(false))
            }
    }

    @Test
    @Order(5)
    fun `5 - a missing query parameter is a bad request not a server error`() {
        Given { queryParam("role", "AISP") }
            .When { get("/api/v1/tpp-registry/check") }
            .Then { statusCode(400) }
    }

    @Test
    @Order(6)
    fun `6 - blacklisting revokes the authorization`() {
        Given {
            contentType("application/json")
            header("Idempotency-Key", "e2e-blacklist-$tppId")
            body("""{"reason":"revoked certificate"}""")
        } When {
            post("/api/v1/tpp-registry/$tppId/blacklist")
        } Then { statusCode(200) }

        Given {
            queryParam("tppId", tppId)
            queryParam("role", "AISP")
        }
            .When { get("/api/v1/tpp-registry/check") }
            .Then {
                statusCode(403)
                body("authorized", equalTo(false))
            }
    }

    @Test
    @Order(7)
    @TestSecurity(user = "viewer@openbank.test", roles = ["ROLE_VIEWER"])
    fun `7 - a viewer cannot register a TPP`() {
        Given {
            contentType("application/json")
            body(registerBody("Sneaky Fintech"))
        } When {
            post("/api/v1/tpp-registry")
        } Then {
            statusCode(403)
        }
    }

    private fun registerBody(name: String) = """
        {"tppId":"$tppId","name":"$name","countryCode":"CZ","nca":"CNB",
         "roles":["AISP"],"qwacSubjectDn":"CN=QWAC","qsealSubjectDn":null}
    """.trimIndent()
}
