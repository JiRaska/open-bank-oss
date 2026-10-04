// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.integration

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The holdings routes are SERVED, and behind the customer realm (#11966). A unit test that calls
 * the resource class cannot tell a registered route from one RESTEasy never picked up (#3371), so
 * this drives real HTTP. The control is what makes the 401s mean something: an unknown path under
 * the same prefix must answer 404, otherwise "401" would hold for a route that does not exist.
 */
@QuarkusTest
class CustomerHoldingsRoutingIT {

    private val id = "00000000-0000-4000-8000-000000000001"

    @Test
    fun `every holdings route is registered and rejects an unauthenticated caller`() {
        listOf(
            "GET" to "/customer/v1/holdings",
            "POST" to "/customer/v1/holdings",
            "GET" to "/customer/v1/holdings/$id",
            "DELETE" to "/customer/v1/holdings/$id",
            "PUT" to "/customer/v1/holdings/$id/valuation",
            "GET" to "/customer/v1/holdings/$id/valuations",
        ).forEach { (method, path) ->
            assertThat(status(method, path)).describedAs("$method $path").isEqualTo(401)
        }
    }

    @Test
    fun `an unknown path under the holdings prefix is a 404, so the 401s above discriminate`() {
        assertThat(status("GET", "/customer/v1/holdings/$id/no-such-route")).isEqualTo(404)
    }

    private fun status(method: String, path: String): Int {
        val spec = given()
        if (method == "POST" || method == "PUT") spec.contentType("application/json").body("{}")
        return spec.request(method, path).then().extract().statusCode()
    }
}
