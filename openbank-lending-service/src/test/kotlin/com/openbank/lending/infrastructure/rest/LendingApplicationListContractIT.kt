// SPDX-License-Identifier: Apache-2.0

package com.openbank.lending.infrastructure.rest

import com.openbank.lending.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.util.UUID

@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
class LendingApplicationListContractIT {
    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_ADMIN"])
    fun `bounded party list preserves the array response contract`() {
        Given {
            queryParam("partyId", UUID.randomUUID().toString())
            queryParam("limit", 31)
        } When {
            get("/api/v1/lending/applications")
        } Then {
            statusCode(200)
            body("size()", equalTo(0))
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_ADMIN"])
    fun `bounded party list rejects an invalid limit`() {
        Given {
            queryParam("partyId", UUID.randomUUID().toString())
            queryParam("limit", 0)
        } When {
            get("/api/v1/lending/applications")
        } Then {
            statusCode(400)
        }
    }
}
