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
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

private const val BATCH_HUMAN = "11111111-1111-4111-8111-111111111111"
private const val BATCH_COMPANY = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
private const val BATCH_ID = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
private const val BATCH_BASE = "/api/v1/business-payment-batches"

@QuarkusTest
@QuarkusTestResource(BusinessApprovalStubs::class, restrictToAnnotatedClass = true)
@TestSecurity(user = "customer:$BATCH_HUMAN", roles = ["ROLE_CUSTOMER"])
@OidcSecurity(claims = [Claim(key = "party_id", value = BATCH_HUMAN)])
class BusinessPaymentBatchHttpIT {
    @BeforeEach
    fun stubs() {
        BusinessApprovalStubs.reset()
        BusinessApprovalStubs.stub(
            "GET", "/api/v1/parties/$BATCH_HUMAN/acting-for",
            body = """[{"partyId":"$BATCH_COMPANY","partyType":"COMPANY","status":"ACTIVE"}]""",
        )
    }

    @Test
    fun `create and edit bind company and human over HTTP`() {
        BusinessApprovalStubs.stub("POST", BATCH_BASE, status = 201, body = """{"id":"$BATCH_ID","state":"DRAFT"}""")
        BusinessApprovalStubs.stub("GET", BATCH_BASE, body = """{"data":[{"id":"$BATCH_ID"}]}""")
        BusinessApprovalStubs.stub("GET", "$BATCH_BASE/$BATCH_ID", body = """{"id":"$BATCH_ID","state":"DRAFT"}""")
        BusinessApprovalStubs.stub("PUT", "$BATCH_BASE/$BATCH_ID/items", body = """{"id":"$BATCH_ID","revision":1}""")
        Given {
            header("X-Acting-For", BATCH_COMPANY)
            header("Idempotency-Key", "batch-http-test")
            contentType("application/json")
            body("""{"debtorAccountId":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","items":[]}""")
        } When { post("/customer/v1/business/payment-batches") } Then {
            statusCode(201)
            body("state", equalTo("DRAFT"))
        }
        Given { header("X-Acting-For", BATCH_COMPANY) } When {
            get("/customer/v1/business/payment-batches")
        } Then { statusCode(200) }
        Given { header("X-Acting-For", BATCH_COMPANY) } When {
            get("/customer/v1/business/payment-batches/$BATCH_ID")
        } Then { statusCode(200) }
        assertEquals(
            BATCH_COMPANY,
            BusinessApprovalStubs.requests("GET", BATCH_BASE).single().header("X-Customer-Party-Id"),
        )
        assertEquals(
            BATCH_COMPANY,
            BusinessApprovalStubs.requests("GET", "$BATCH_BASE/$BATCH_ID").single().header("X-Customer-Party-Id"),
        )

        val created = BusinessApprovalStubs.requests("POST", BATCH_BASE).single()
        assertEquals(BATCH_COMPANY, created.header("X-Customer-Party-Id"))
        assertEquals(BATCH_HUMAN, created.header("X-Actor-Party-Id"))
        assertEquals("batch-http-test", created.header("Idempotency-Key"))

        Given {
            header("X-Acting-For", BATCH_COMPANY)
            header("If-Match", "0")
            contentType("application/json")
            body("""{"items":[]}""")
        } When { put("/customer/v1/business/payment-batches/$BATCH_ID/items") } Then { statusCode(200) }
        val edited = BusinessApprovalStubs.requests("PUT", "$BATCH_BASE/$BATCH_ID/items").single()
        assertEquals(BATCH_COMPANY, edited.header("X-Customer-Party-Id"))
        assertEquals(BATCH_HUMAN, edited.header("X-Actor-Party-Id"))
        assertEquals("0", edited.header("If-Match"))
    }

    @Test
    fun `foreign acting-for cannot reach draft backend`() {
        Given {
            header("X-Acting-For", "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee")
        } When { get("/customer/v1/business/payment-batches") } Then { statusCode(403) }
        assertTrue(BusinessApprovalStubs.requests("GET", BATCH_BASE).isEmpty())
    }
}
