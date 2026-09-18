// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.integration

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test

/** Proves the capability route is served over HTTP and keeps its customer-only boundary. */
@QuarkusTest
class CustomerCapabilitiesRoutingIT {
    @Test
    fun `unauthenticated caller cannot read capabilities`() {
        given().get(PATH).then().statusCode(401)
    }

    @Test
    @TestSecurity(user = "customer:capability-probe", roles = ["ROLE_CUSTOMER"])
    fun `customer can read the registered route`() {
        given().get(PATH).then()
            .statusCode(200)
            .header("Cache-Control", "private, max-age=300")
            .body("schemaVersion", equalTo(1))
            .body("capabilities[0].id", equalTo("loyalty"))
    }

    @Test
    @TestSecurity(user = "operator:capability-probe", roles = ["ROLE_OPERATOR"])
    fun `operator role cannot use the customer route`() {
        given().get(PATH).then().statusCode(403)
    }

    private companion object {
        const val PATH = "/customer/v1/capabilities"
    }
}
