// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.flakytest.infrastructure.rest

import com.openbank.flakytest.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.junit.jupiter.api.Test

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class FlakyTestResourceRoutingIT {

    @Test
    @TestSecurity(user = "viewer", roles = ["ROLE_VIEWER"])
    fun `findings read is served from the reactive Vertx context`() {
        given()
            .`when`().get("/api/v1/flaky-test-hunter/findings")
            .then().statusCode(200)
    }

    @Test
    fun `async trigger is a served route that rejects an anonymous caller`() {
        given()
            .`when`().post("/api/v1/flaky-test-hunter/check/trigger-async")
            .then().statusCode(401)
    }

    @Test
    @TestSecurity(user = "viewer", roles = ["ROLE_VIEWER"])
    fun `async trigger rejects a viewer before workflow admission`() {
        given()
            .`when`().post("/api/v1/flaky-test-hunter/check/trigger-async")
            .then().statusCode(403)
    }
}
