// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.notification.integration

import com.openbank.notification.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * A null VALUE inside the `variables` map must be rejected with 400, not answered with 500.
 *
 * Jackson's Kotlin module null-checks CONSTRUCTOR PARAMETERS; it does not check the VALUES of a
 * map, so `{"variables":{"ticketReference":null}}` deserialises into a `Map<String, String>`
 * holding a null and the first dereference downstream fails. Driven over real HTTP on purpose: a
 * unit test calling the handler with a hand-built DTO cannot construct that state.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(OperatorMessageNullVariableIT.AdvisoryAuthzProfile::class)
@TestSecurity(user = "operator@openbank.test", roles = ["ROLE_OPERATOR"])
class OperatorMessageNullVariableIT {

    /**
     * `authz.enforce` defaults to true, and with no OPA sidecar in a test JVM the `@Authorize`
     * interceptor fails closed with 503 before the handler is ever entered — which would make this
     * test blind to the status code it exists to measure. Advisory mode is the only thing this
     * profile changes.
     */
    class AdvisoryAuthzProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf("authz.enforce" to "false")
    }

    @Test
    fun `a null variable value is rejected with 400`() {
        Given {
            contentType("application/json")
            body(
                """{
                    "partyId":"${UUID.randomUUID()}",
                    "template":"SUPPORT_FOLLOWUP",
                    "recipient":"customer@example.com",
                    "variables":{"ticketReference":null}
                }""",
            )
        } When {
            post("/api/v1/notifications/messages")
        } Then {
            statusCode(400)
        }
    }
}
