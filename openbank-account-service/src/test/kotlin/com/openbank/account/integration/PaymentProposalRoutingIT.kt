// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.account.integration

import com.openbank.account.it.PostgresRedpandaRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.util.UUID

/** A direct resource call cannot prove that Quarkus registered the actual HTTP path. */
@QuarkusTest
@QuarkusTestResource(PostgresRedpandaRedisTestResource::class)
class PaymentProposalRoutingIT {
    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `maker authorization route serves a classified denial for a missing account`() {
        given()
            .queryParam("partyId", UUID.randomUUID().toString())
            .queryParam("amount", "100.00")
            .queryParam("currency", "CZK")
            .get("/api/v1/accounts/${UUID.randomUUID()}/delegation/payment-proposal-authorization")
            .then()
            .statusCode(200)
            .body("authorized", equalTo(false))
            .body("outcome", equalTo("ACCOUNT_NOT_FOUND"))
    }
}
