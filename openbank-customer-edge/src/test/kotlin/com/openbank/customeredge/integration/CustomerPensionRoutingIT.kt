// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.customeredge.integration

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
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

private const val PARTICIPANT = "3b9f6c1e-2d4a-4e8b-9c7d-5a1f0e2b3c4d"
private const val STRANGER = "8e7d6c5b-4a39-4281-9f0e-d1c2b3a4f5e6"
private const val OWN = "11111111-2222-4333-8444-555555555555"
private const val FOREIGN = "66666666-7777-4888-9999-aaaaaaaaaaaa"
private const val API = "/api/v2/pension"

/**
 * The customer pension routes over real HTTP, with pension-service and sca-service on a loopback
 * stub. A unit test that calls the resource class cannot tell a served route from an unserved one;
 * these requests go through RESTEasy, the security layer and the real [UpstreamClient].
 *
 * What is asserted is what reaches pension-service: the token's party in `X-Customer-Party-Id`,
 * the edge's own bearer token, the caller's `Idempotency-Key` — and, for a contract held by another
 * party, nothing past the ownership read.
 */
@QuarkusTest
@QuarkusTestResource(StubUpstreamResource::class, restrictToAnnotatedClass = true)
@TestSecurity(user = "customer:$PARTICIPANT", roles = ["ROLE_CUSTOMER"])
@OidcSecurity(claims = [Claim(key = "party_id", value = PARTICIPANT)])
class CustomerPensionRoutingIT {

    @BeforeEach
    fun reset() = StubUpstreamResource.reset()

    private fun contract(id: String, owner: String) = """
        {"contractId":"$id","participantPartyId":"$owner","productLine":"DPS","jurisdiction":"CZ",
         "packVersion":1,"providerEntityId":"00000000-0000-4000-8000-000000000001",
         "providerType":"PENSION_COMPANY","status":"ACTIVE",
         "schedule":{"amount":1000,"currency":"CZK","frequency":"MONTHLY"},
         "currentStrategy":{"strategyCode":"BALANCED","effectiveFrom":"2026-01-01","electedAt":"2026-01-01T10:00:00Z"},
         "strategyHistory":[],"beneficiaries":[],"startDate":"2026-01-01",
         "createdAt":"2026-01-01T10:00:00Z","updatedAt":"2026-01-01T10:00:00Z"}
    """.trimIndent()

    /** The resource consumes JSON, so even a body-less POST is sent as `{}` with its media type. */
    private fun io.restassured.specification.RequestSpecification.json() = contentType("application/json").body("{}")

    private fun header(request: StubUpstreamResource.Companion.Request, name: String): List<String>? =
        request.headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    @Test
    fun `the overview reads pension-service as the edge for the token's own party`() {
        StubUpstreamResource.stub("$API/contracts/$OWN", body = contract(OWN, PARTICIPANT))
        StubUpstreamResource.stub("$API/contracts/$OWN/valuation", status = 503, body = "{}")

        When { get("/customer/v1/pension/contracts/$OWN") } Then {
            statusCode(200)
            body("contractId", equalTo(OWN))
            body("participantPartyId", nullValue())
            body("valuation", nullValue())
        }

        val read = StubUpstreamResource.requests("$API/contracts/$OWN").single()
        assertThat(header(read, "X-Customer-Party-Id")).containsExactly(PARTICIPANT)
        assertThat(header(read, "Authorization")).containsExactly("Bearer stub-token")
    }

    @Test
    fun `another party's contract is 404 and nothing past the ownership read reaches pension-service`() {
        StubUpstreamResource.stub("$API/contracts/$FOREIGN", body = contract(FOREIGN, STRANGER))

        When { get("/customer/v1/pension/contracts/$FOREIGN") } Then { statusCode(404) }
        Given { json(); header("Idempotency-Key", "k-foreign") } When {
            post("/customer/v1/pension/contracts/$FOREIGN/termination/quote")
        } Then { statusCode(404) }

        assertThat(StubUpstreamResource.requests("$API/contracts/$FOREIGN/valuation")).isEmpty()
        assertThat(StubUpstreamResource.requests("$API/contracts/$FOREIGN/exit/termination/quote")).isEmpty()
    }

    @Test
    fun `pension-service's own 404 for a foreign contract is the same 404`() {
        StubUpstreamResource.stub("$API/contracts/$FOREIGN", status = 404, body = """{"error":"not found"}""")

        When { get("/customer/v1/pension/contracts/$FOREIGN/transactions") } Then {
            statusCode(404)
            body("error", equalTo("pension contract not found"))
        }
        assertThat(StubUpstreamResource.requests("$API/contracts/$FOREIGN/transactions")).isEmpty()
    }

    @Test
    fun `a termination quote is refused without an Idempotency-Key and forwards the caller's key`() {
        StubUpstreamResource.stub("$API/contracts/$OWN", body = contract(OWN, PARTICIPANT))
        StubUpstreamResource.stub(
            "$API/contracts/$OWN/exit/termination/quote",
            status = 201,
            body = """{"noticeId":"n-1","signingHash":"abc"}""",
        )

        Given { json() } When { post("/customer/v1/pension/contracts/$OWN/termination/quote") } Then { statusCode(400) }
        assertThat(StubUpstreamResource.requests("$API/contracts/$OWN/exit/termination/quote")).isEmpty()

        Given { json(); header("Idempotency-Key", "k-quote-1") } When {
            post("/customer/v1/pension/contracts/$OWN/termination/quote")
        } Then {
            statusCode(201)
            body("noticeId", equalTo("n-1"))
        }
        val quote = StubUpstreamResource.requests("$API/contracts/$OWN/exit/termination/quote").single()
        assertThat(header(quote, "Idempotency-Key")).containsExactly("k-quote-1")
        assertThat(header(quote, "X-Customer-Party-Id")).containsExactly(PARTICIPANT)
    }

    @Test
    fun `pausing without SCA answers SCA_REQUIRED with the edge-bound linking and never suspends`() {
        StubUpstreamResource.stub("$API/contracts/$OWN", body = contract(OWN, PARTICIPANT))

        Given { json(); header("Idempotency-Key", "k-pause-1") } When {
            post("/customer/v1/pension/contracts/$OWN/pause")
        } Then {
            statusCode(403)
            body("code", equalTo("SCA_REQUIRED"))
            body("scaLinking.approvalRequestId", equalTo("pension.suspend:$OWN"))
            body("scaLinking.purpose", equalTo("APPROVAL"))
        }
        assertThat(StubUpstreamResource.requests("$API/contracts/$OWN/suspend")).isEmpty()
    }
}
