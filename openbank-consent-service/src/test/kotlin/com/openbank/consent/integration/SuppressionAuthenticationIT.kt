// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.consent.integration

import com.openbank.consent.it.ConsentPostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The authentication gate on `/api/v1/suppressions`, driven over real HTTP.
 *
 * WHY THIS EXISTS: `SuppressionResource` carried `@Authorize` and no `@RolesAllowed`, so a request
 * with no bearer at all reached the OPA interceptor as an ANONYMOUS principal. Whether it was
 * refused depended entirely on `AUTHZ_ENFORCE`: the deployed pod (enforce=true) answered 403, but
 * wherever the interceptor runs advisory — `%test` here, or any environment that flips it off —
 * `GET /api/v1/suppressions/party/{partyId}` answered **200** to an anonymous caller, returning a
 * party's do-not-contact list. Authentication must not be a property of the policy toggle.
 *
 * `%test` runs with `AUTHZ_ENFORCE` unset (advisory), which is deliberate: every 401 below is
 * produced by `@RolesAllowed` alone, with no help from the PDP. No class-level `@TestSecurity`,
 * so the unannotated tests arrive genuinely unauthenticated.
 */
@QuarkusTest
@QuarkusTestResource(SuppressionAuthenticationIT.InMemoryKafkaResource::class)
@QuarkusTestResource(ConsentPostgresRedisTestResource::class)
class SuppressionAuthenticationIT {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> {
            val props = InMemoryConnector.switchOutgoingChannelsToInMemory("consent-events-out").toMutableMap()
            props["openbank.outbox.dispatch-enabled"] = "false"
            return props
        }

        override fun stop() = InMemoryConnector.clear()
    }

    private fun createBody(partyId: UUID = UUID.randomUUID()) =
        """{"partyId":"$partyId","scope":"ALL","value":null,"reason":"CUSTOMER_OPTOUT","source":"suppression-auth-it"}"""

    @Test
    fun `listing suppressions without a bearer is 401`() {
        When {
            get("/api/v1/suppressions/party/${UUID.randomUUID()}")
        } Then {
            statusCode(401)
        }
    }

    @Test
    fun `creating a suppression without a bearer is 401`() {
        Given {
            contentType("application/json")
            body(createBody())
        } When {
            post("/api/v1/suppressions")
        } Then {
            statusCode(401)
        }
    }

    @Test
    fun `revoking a suppression without a bearer is 401`() {
        When {
            post("/api/v1/suppressions/${UUID.randomUUID()}/revoke")
        } Then {
            statusCode(401)
        }
    }

    @Test
    @TestSecurity(user = "service-account-openbank-services", roles = ["ROLE_API"])
    fun `the shared M2M client campaign-service reads through is admitted to the list`() {
        When {
            get("/api/v1/suppressions/party/${UUID.randomUUID()}")
        } Then {
            statusCode(200)
        }
    }

    @Test
    @TestSecurity(user = "it-operator", roles = ["ROLE_OPERATOR"])
    fun `an operator is admitted to the list`() {
        When {
            get("/api/v1/suppressions/party/${UUID.randomUUID()}")
        } Then {
            statusCode(200)
        }
    }

    @Test
    @TestSecurity(user = "it-customer", roles = ["ROLE_USER"])
    fun `an authenticated caller without a granted role is 403`() {
        When {
            get("/api/v1/suppressions/party/${UUID.randomUUID()}")
        } Then {
            statusCode(403)
        }
    }

    @Test
    @TestSecurity(user = "service-account-openbank-services", roles = ["ROLE_API"])
    fun `the M2M role cannot write a suppression`() {
        Given {
            contentType("application/json")
            body(createBody())
        } When {
            post("/api/v1/suppressions")
        } Then {
            statusCode(403)
        }
    }

    @Test
    @TestSecurity(user = "it-operator", roles = ["ROLE_OPERATOR"])
    fun `an operator can write a suppression`() {
        Given {
            contentType("application/json")
            body(createBody())
        } When {
            post("/api/v1/suppressions")
        } Then {
            statusCode(201)
        }
    }
}
