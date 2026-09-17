// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.customeredge.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.customeredge.contract.StubUpstreamResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

private const val AML_HUMAN = "7c1e2d3f-4a5b-4c6d-8e9f-0a1b2c3d4e5f"
private const val AML_COMPANY = "9d8c7b6a-5f4e-4d3c-8b2a-1f0e9d8c7b6a"
private const val AML_PATH = "/api/v1/parties/$AML_HUMAN/aml-profile"
private const val COMPANY_AML_PATH = "/api/v1/parties/$AML_COMPANY/aml-profile"

/**
 * `/customer/v1/me/aml-profile` over real HTTP, with every upstream on a loopback stub. The claims
 * are about what reaches party-service: the path and `X-Customer-Party-Id` are the TOKEN's party,
 * whatever the client puts in the body or in `X-Acting-For`.
 *
 * The acting-for control test is what makes "ignored" meaningful: the same header on a route that
 * DOES honour it is refused (the stub knows no mandate, so the switch fails closed with 403). If the
 * header were inert everywhere, the AML assertions would pass for the wrong reason.
 */
@QuarkusTest
@QuarkusTestResource(StubUpstreamResource::class, restrictToAnnotatedClass = true)
@TestSecurity(user = "customer:$AML_HUMAN", roles = ["ROLE_CUSTOMER"])
@OidcSecurity(claims = [Claim(key = "party_id", value = AML_HUMAN)])
class CustomerAmlProfileIT {

    @BeforeEach
    fun reset() = StubUpstreamResource.reset()

    private fun partyHeader(request: StubUpstreamResource.Companion.Request): List<String>? =
        request.headers.entries.firstOrNull { it.key.equals("X-Customer-Party-Id", ignoreCase = true) }?.value

    @Test
    fun `GET reads the caller's own profile even when X-Acting-For names a company`() {
        StubUpstreamResource.stub(AML_PATH, body = """{"partyId":"$AML_HUMAN","version":3}""")

        Given { header("X-Acting-For", AML_COMPANY) } When { get("/customer/v1/me/aml-profile") } Then {
            statusCode(200)
            body("version", equalTo(3))
        }

        val call = StubUpstreamResource.requests(AML_PATH).single()
        assertThat(partyHeader(call)).containsExactly(AML_HUMAN)
        assertThat(StubUpstreamResource.requests(COMPANY_AML_PATH)).isEmpty()
    }

    @Test
    fun `PUT declares for the token's party - a body partyId and X-Acting-For are ignored`() {
        StubUpstreamResource.stub(AML_PATH, body = """{"partyId":"$AML_HUMAN","version":1}""")

        Given {
            contentType("application/json")
            header("X-Acting-For", AML_COMPANY)
            body("""{"partyId":"$AML_COMPANY","truthful":true,"usPerson":false,"tin":{"SK":"1234567890"}}""")
        } When {
            put("/customer/v1/me/aml-profile")
        } Then {
            statusCode(200)
        }

        val call = StubUpstreamResource.requests(AML_PATH).single()
        assertThat(partyHeader(call)).containsExactly(AML_HUMAN)
        val forwarded = ObjectMapper().readTree(call.body)
        assertThat(forwarded.has("partyId")).isFalse()
        assertThat(forwarded.path("tin").path("SK").asText()).isEqualTo("1234567890")
        assertThat(call.body).doesNotContain(AML_COMPANY)
        assertThat(StubUpstreamResource.requests(COMPANY_AML_PATH)).isEmpty()
    }

    @Test
    fun `party-service's 400 is passed through with its message`() {
        StubUpstreamResource.stub(
            AML_PATH,
            status = 400,
            body = """{"code":"BAD_REQUEST","message":"truthful must be true"}""",
        )

        Given {
            contentType("application/json")
            body("""{"truthful":false}""")
        } When {
            put("/customer/v1/me/aml-profile")
        } Then {
            statusCode(400)
            body(containsString("truthful must be true"))
        }
    }

    @Test
    fun `a non-object body is a 400 at the edge and never reaches party-service`() {
        Given {
            contentType("application/json")
            body("[]")
        } When {
            put("/customer/v1/me/aml-profile")
        } Then {
            statusCode(400)
        }
        assertThat(StubUpstreamResource.requests(AML_PATH)).isEmpty()
    }

    @Test
    fun `control - the same X-Acting-For header IS honoured on a switching route, and refused there`() {
        Given { header("X-Acting-For", AML_COMPANY) } When { get("/customer/v1/payees") } Then {
            statusCode(403)
        }
    }
}
