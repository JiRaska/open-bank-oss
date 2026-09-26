// SPDX-License-Identifier: Apache-2.0
package com.openbank.customeredge.integration

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Every Lístky and member-get-member route is behind the customer realm: no token, 401. */
@QuarkusTest
class CustomerLoyaltyReferralUnauthenticatedIT {

    @Test
    fun `every loyalty and referral route rejects an unauthenticated caller`() {
        val routes = listOf(
            "GET" to "/customer/v1/loyalty",
            "GET" to "/customer/v1/loyalty/benefits",
            "GET" to "/customer/v1/loyalty/earn-sources",
            "POST" to "/customer/v1/loyalty/redemptions",
            "GET" to "/customer/v1/loyalty/grants",
            "GET" to "/customer/v1/referrals/program",
            "GET" to "/customer/v1/referrals/invites",
            "POST" to "/customer/v1/referrals/invites",
            "POST" to "/customer/v1/referrals/attributions",
        )
        routes.forEach { (method, path) ->
            val spec = given().header("Idempotency-Key", "unauthenticated")
            if (method == "POST") spec.contentType("application/json").body("{}")
            val status = spec.request(method, path).then().extract().statusCode()
            assertThat(status).describedAs("$method $path").isEqualTo(401)
        }
    }
}
