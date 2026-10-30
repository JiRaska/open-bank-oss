// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.integration

import com.openbank.pension.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.jupiter.api.Test

/**
 * Repro probe for the live 500 on GET /state-contribution/deadlines
 * ("this.clock is null" NPE in StateContributionReturnService.today). The endpoint must answer 200
 * with all three counters through the real CDI wiring.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class StateContributionDeadlinesApiIT {

    @Test
    @TestSecurity(user = "alice", roles = ["ROLE_OPERATOR"])
    fun `deadlines answers through the injected service`() {
        given()
            .`when`().get("/api/v2/pension/funding/operations/state-contribution/deadlines")
            .then()
            .statusCode(200)
            .body("claimsPastFilingDeadline", notNullValue())
            .body("claimsPastExpectedPayment", notNullValue())
            .body("returnsOverdue", notNullValue())
    }
}
