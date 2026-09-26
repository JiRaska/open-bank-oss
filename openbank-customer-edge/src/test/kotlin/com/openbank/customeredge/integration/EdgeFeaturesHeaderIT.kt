// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.integration

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

private const val PERSON = "11111111-1111-4111-8111-111111111111"
private const val ACCOUNT = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
private const val OTHER = "ffffffff-ffff-4fff-8fff-ffffffffffff"
private const val SO = "/api/v1/standing-orders"
private const val HEADER = "X-Edge-Features"

private fun soBody(extra: String = "") =
    """{"debitAccountId":"$ACCOUNT","creditorIban":"CZ6508000000192000145399","creditorName":"Pronajimatel",""" +
        """"amountMinorUnits":150000,"currency":"CZK","frequency":"MONTHLY","paymentType":"DOMESTIC"$extra}"""

private fun stubPersonal() {
    BusinessApprovalStubs.reset()
    BusinessApprovalStubs.stub(
        "GET",
        "/api/v1/accounts/$ACCOUNT",
        body = """{"id":"$ACCOUNT","partyId":"$PERSON","accountNumber":"CZ5508000000001234567899"}""",
    )
    BusinessApprovalStubs.stub("GET", SO + "/party/$PERSON", body = "[]")
    BusinessApprovalStubs.stub("POST", SO, status = 201, body = """{"id":"so-1"}""")
}

/**
 * `X-Edge-Features` (#10281): the app reads it from the standing-order list it already loads to know
 * whether the one-call edit (`replacesStandingOrderId`) is live in this build. Present with the exact
 * value on the list and on create/replace responses, refusals included; absent elsewhere.
 */
@QuarkusTest
@QuarkusTestResource(BusinessApprovalStubs::class, restrictToAnnotatedClass = true)
@TestSecurity(user = "customer:$PERSON", roles = ["ROLE_CUSTOMER"])
@OidcSecurity(claims = [Claim(key = "party_id", value = PERSON)])
class EdgeFeaturesHeaderIT {

    @BeforeEach
    fun stubs() = stubPersonal()

    @Test
    fun `the list and the create responses carry exactly the live flag`() {
        Given { header("Accept", "application/json") } When { get("/customer/v1/standing-orders") } Then {
            statusCode(200)
            header(HEADER, equalTo("standingorders.replace"))
        }
        Given {
            contentType("application/json")
            body(soBody())
        } When { post("/customer/v1/standing-orders") } Then {
            statusCode(201)
            header(HEADER, equalTo("standingorders.replace"))
        }
    }

    @Test
    fun `a refused replace still carries the flag, and a foreign order is never touched`() {
        BusinessApprovalStubs.stub("GET", "$SO/$OTHER", body = """{"id":"$OTHER","partyId":"someone-else"}""")
        Given {
            contentType("application/json")
            body(soBody(""","replacesStandingOrderId":"$OTHER""""))
        } When { post("/customer/v1/standing-orders") } Then {
            statusCode(403)
            header(HEADER, equalTo("standingorders.replace"))
        }
        assertThat(BusinessApprovalStubs.requests("POST", SO)).isEmpty()
    }

    @Test
    fun `other routes carry no flag`() {
        BusinessApprovalStubs.stub("GET", "$SO/$OTHER", body = """{"id":"$OTHER","partyId":"$PERSON"}""")
        BusinessApprovalStubs.stub("POST", "$SO/$OTHER/pause", body = "{}")
        Given { header("Accept", "application/json") } When { post("/customer/v1/standing-orders/$OTHER/pause") } Then {
            header(HEADER, nullValue())
        }
    }
}

class ReplaceOffProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> =
        mapOf("openbank.edge.features.standingorders-replace" to "false")
}

/** Not live in this build: no header at all, and the edge refuses to send `replacesStandingOrderId` upstream. */
@QuarkusTest
@TestProfile(ReplaceOffProfile::class)
@QuarkusTestResource(BusinessApprovalStubs::class, restrictToAnnotatedClass = true)
@TestSecurity(user = "customer:$PERSON", roles = ["ROLE_CUSTOMER"])
@OidcSecurity(claims = [Claim(key = "party_id", value = PERSON)])
class EdgeFeaturesOffIT {

    @BeforeEach
    fun stubs() = stubPersonal()

    @Test
    fun `no header on the list or create, and replace is refused before anything goes upstream`() {
        Given { header("Accept", "application/json") } When { get("/customer/v1/standing-orders") } Then {
            statusCode(200)
            header(HEADER, nullValue())
        }
        Given {
            contentType("application/json")
            body(soBody())
        } When { post("/customer/v1/standing-orders") } Then {
            statusCode(201)
            header(HEADER, nullValue())
        }
        BusinessApprovalStubs.reset()
        stubPersonal()
        Given {
            contentType("application/json")
            body(soBody(""","replacesStandingOrderId":"$OTHER""""))
        } When { post("/customer/v1/standing-orders") } Then {
            statusCode(501)
            header(HEADER, nullValue())
        }
        assertThat(BusinessApprovalStubs.requests("POST", SO)).isEmpty()
    }
}
