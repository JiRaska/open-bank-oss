// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.integration

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

private const val DISABLED_HUMAN = "11111111-1111-4111-8111-111111111111"
private const val DISABLED_COMPANY = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
private const val DISABLED_BATCH = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
private const val CUSTOMER_BATCH_PATH = "/customer/v1/business/payment-batches"
private const val BACKEND_BATCH_PATH = "/api/v1/business-payment-batches"

@QuarkusTest
@QuarkusTestResource(BusinessApprovalStubs::class, restrictToAnnotatedClass = true)
@TestSecurity(user = "customer:$DISABLED_HUMAN", roles = ["ROLE_CUSTOMER"])
@OidcSecurity(claims = [Claim(key = "party_id", value = DISABLED_HUMAN)])
class BusinessPaymentBatchDisabledHttpIT {
    @BeforeEach
    fun reset() = BusinessApprovalStubs.reset()

    @Test
    fun `every customer draft route returns 404 when feature is off and contacts no backend`() {
        Given { header("X-Acting-For", DISABLED_COMPANY) } When {
            get(CUSTOMER_BATCH_PATH)
        } Then { statusCode(404) }
        Given { header("X-Acting-For", DISABLED_COMPANY) } When {
            get("$CUSTOMER_BATCH_PATH/$DISABLED_BATCH")
        } Then { statusCode(404) }
        Given {
            header("X-Acting-For", DISABLED_COMPANY)
            header("Idempotency-Key", "disabled-route-test")
            contentType("application/json")
            body("""{"debtorAccountId":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","items":[]}""")
        } When { post(CUSTOMER_BATCH_PATH) } Then { statusCode(404) }
        Given {
            header("X-Acting-For", DISABLED_COMPANY)
            header("If-Match", "0")
            contentType("application/json")
            body("""{"items":[]}""")
        } When { put("$CUSTOMER_BATCH_PATH/$DISABLED_BATCH/items") } Then { statusCode(404) }

        assertTrue(BusinessApprovalStubs.requests("GET", BACKEND_BATCH_PATH).isEmpty())
        assertTrue(BusinessApprovalStubs.requests("GET", "$BACKEND_BATCH_PATH/$DISABLED_BATCH").isEmpty())
        assertTrue(BusinessApprovalStubs.requests("POST", BACKEND_BATCH_PATH).isEmpty())
        assertTrue(BusinessApprovalStubs.requests("PUT", "$BACKEND_BATCH_PATH/$DISABLED_BATCH/items").isEmpty())
    }
}
