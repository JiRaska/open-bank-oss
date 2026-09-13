// SPDX-License-Identifier: Apache-2.0
package com.openbank.incentive.integration

import com.openbank.incentive.it.IncentivePostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * A null element inside the `codes` array must be rejected with 400, not answered with 500.
 *
 * Jackson's Kotlin module null-checks CONSTRUCTOR PARAMETERS; it does not check the ELEMENTS of a
 * collection, so `{"codes":[null]}` deserialises into a `List<String>` holding a null and the
 * first dereference downstream fails. Driven over real HTTP on purpose: a unit test calling the
 * handler with a hand-built DTO cannot construct that state.
 */
@QuarkusTest
@QuarkusTestResource(IncentivePostgresTestResource::class)
@TestSecurity(user = "maker@openbank.test", roles = ["ROLE_OPERATOR", "ROLE_API"])
class IncentiveNullCodeElementIT {
    @Test
    fun `a null element in codes is rejected with 400`() {
        Given {
            contentType("application/json")
            body("""{"codes":["ABC-1",null]}""")
        } When {
            post("/api/v1/incentives/offers/${UUID.randomUUID()}/codes")
        } Then {
            statusCode(400)
        }
    }
}
