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
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

private const val INITIATOR = "11111111-1111-4111-8111-111111111111"
private const val COSIGNER = "22222222-2222-4222-8222-222222222222"
private const val COMPANY = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
private const val ACCOUNT = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
private const val APPROVAL = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
private const val SCA = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
private const val SHA = "abababababababababababababababababababababababababababababababab"
private const val DETAIL = "/api/v1/entities/$COMPANY/approval-requests/$APPROVAL"
private const val DOMESTIC_CLAIM = "$DETAIL/domestic-release-claim"
private const val DOMESTIC_RAIL = "/api/v1/domestic-payments"
private const val BOUND_DOMESTIC_RAIL = "$DOMESTIC_RAIL/approval-releases"

@QuarkusTest
@QuarkusTestResource(BusinessApprovalStubs::class, restrictToAnnotatedClass = true)
@TestSecurity(user = "customer:$COSIGNER", roles = ["ROLE_CUSTOMER"])
@OidcSecurity(claims = [Claim(key = "party_id", value = COSIGNER)])
class BusinessDomesticReleaseIT {
    @BeforeEach
    fun stubLastSignature() {
        BusinessApprovalStubs.reset()
        BusinessApprovalStubs.stub(
            "GET",
            "/api/v1/parties/$COSIGNER/acting-for",
            body = """[{"partyId":"$COMPANY","partyType":"COMPANY","status":"ACTIVE"}]""",
        )
        BusinessApprovalStubs.stub(
            "GET",
            DETAIL,
            body = """{"id":"$APPROVAL","kind":"PAYMENT","status":"PENDING","initiatorPartyId":"$INITIATOR",
                "payloadSha256":"$SHA","eligibleSignerIds":["$INITIATOR","$COSIGNER"]}""",
        )
        BusinessApprovalStubs.stub(
            "POST",
            "$DETAIL/signatures",
            body = """{"id":"$APPROVAL","kind":"PAYMENT","status":"APPROVED","payload":{"rail":"DOMESTIC"}}""",
        )
    }

    private fun sign() = Given {
        header("X-Acting-For", COMPANY)
        header("X-SCA-Challenge-Id", SCA)
    } When {
        post("/customer/v1/business/approvals/$APPROVAL/sign")
    }

    @Test
    fun `domestic release sends original key digest outside the frozen rail body`() {
        val frozen = """{"debtorAccountId":"$ACCOUNT","amount":400,"currency":"CZK"}"""
        BusinessApprovalStubs.stub(
            "POST",
            DOMESTIC_CLAIM,
            body = """{"claimToken":"t1","originalSubmissionKeyDigest":"$SHA",
                "payload":{"rail":"DOMESTIC","railRequest":$frozen}}""",
        )
        val paymentId = "00000000-0000-4000-8000-0000000000f9"
        BusinessApprovalStubs.stub("POST", BOUND_DOMESTIC_RAIL, status = 201, body = """{"id":"$paymentId"}""")
        BusinessApprovalStubs.stub("POST", "$DETAIL/release-result", body = "{}")

        sign() Then {
            statusCode(200)
            body("release.status", equalTo("RELEASED"))
            body("release.paymentId", equalTo(paymentId))
        }

        assertThat(BusinessApprovalStubs.requests("POST", DOMESTIC_CLAIM)).hasSize(1)
        assertThat(BusinessApprovalStubs.requests("POST", "$DETAIL/release-claim")).isEmpty()
        val rail = BusinessApprovalStubs.requests("POST", BOUND_DOMESTIC_RAIL).single()
        assertThat(rail.header("Idempotency-Key")).isEqualTo(APPROVAL)
        assertThat(rail.header("X-OpenBank-Original-Submission-Key-Digest")).isEqualTo(SHA)
        assertThat(rail.body).isEqualTo(frozen)
        assertThat(BusinessApprovalStubs.requests("POST", DOMESTIC_RAIL)).isEmpty()
    }

    @Test
    fun `malformed domestic release digest blocks dispatch`() {
        BusinessApprovalStubs.stub(
            "POST",
            DOMESTIC_CLAIM,
            body = """{"claimToken":"t1","originalSubmissionKeyDigest":"wrong",
                "payload":{"rail":"DOMESTIC","railRequest":{"amount":400}}}""",
        )
        BusinessApprovalStubs.stub("POST", "$DETAIL/release-result", body = "{}")

        sign() Then {
            statusCode(200)
            body("release.status", equalTo("RELEASE_FAILED"))
        }
        assertThat(BusinessApprovalStubs.requests("POST", DOMESTIC_RAIL)).isEmpty()
        assertThat(BusinessApprovalStubs.requests("POST", BOUND_DOMESTIC_RAIL)).isEmpty()
    }

    @Test
    fun `older signing provider cannot trigger a fallback release claim`() {
        BusinessApprovalStubs.stub("POST", DOMESTIC_CLAIM, status = 404, body = "{}")

        sign() Then {
            statusCode(200)
            body("release.status", equalTo("RELEASE_NOT_CLAIMED"))
        }
        assertThat(BusinessApprovalStubs.requests("POST", "$DETAIL/release-claim")).isEmpty()
        assertThat(BusinessApprovalStubs.requests("POST", DOMESTIC_RAIL)).isEmpty()
        assertThat(BusinessApprovalStubs.requests("POST", BOUND_DOMESTIC_RAIL)).isEmpty()
    }
}
