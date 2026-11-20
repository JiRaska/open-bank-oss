// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.

package com.openbank.devops.contract

import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.junit.jupiter.api.Test

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class DevOpsFindingsAuthorizationTest {
    @Test
    @TestSecurity(user = "pact-non-reader", roles = ["ROLE_API"])
    fun `findings are forbidden without a reader role`() {
        given().get("/api/v1/devops/findings").then().statusCode(403)
    }
}
