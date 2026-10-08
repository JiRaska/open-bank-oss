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
import io.restassured.RestAssured
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@QuarkusTest
@QuarkusTestResource(StubUpstreamResource::class, restrictToAnnotatedClass = true)
@TestSecurity(user = "customer:$PARTY", roles = ["ROLE_CUSTOMER"])
@OidcSecurity(claims = [Claim(key = "party_id", value = PARTY)])
class SepaReceiptLookupIT {
    @BeforeEach
    fun reset() = StubUpstreamResource.reset()

    @Test
    fun `own account forwards an enriched body and authenticated party then returns the rail state`() {
        stubOwnAccount()
        StubUpstreamResource.stub(RAIL_PATH, body = """{"state":"UNKNOWN"}""")

        val response = lookup()

        assertThat(response.statusCode).isEqualTo(200)
        assertThat(response.jsonPath().getString("state")).isEqualTo("UNKNOWN")
        val request = StubUpstreamResource.requests(RAIL_PATH).single()
        assertThat(request.path).doesNotContain("receipt-key")
        assertThat(request.headers.entries.single { it.key.equals("X-Customer-Party-Id", true) }.value)
            .containsExactly(PARTY)
        val json = ObjectMapper().readTree(request.body)
        assertThat(json.path("idempotencyKey").asText()).isEqualTo("receipt-key")
        assertThat(json.path("payment").path("debtorAccountId").asText()).isEqualTo(ACCOUNT)
        assertThat(json.path("payment").path("debtorIban").asText()).isEqualTo("CZ6508000000192000145399")
        assertThat(json.path("payment").path("debtorName").asText()).isEqualTo("Alice Example")
        assertThat(json.path("payment").path("type").asText()).isEqualTo("SCT")
    }

    @Test
    fun `foreign account is denied before any receipt lookup`() {
        StubUpstreamResource.stub(
            ACCOUNT_PATH,
            body = """{"id":"$ACCOUNT","partyId":"33333333-3333-3333-3333-333333333333",
                "accountNumber":"CZ6508000000192000145399"}""",
        )

        assertThat(lookup().statusCode).isEqualTo(403)
        assertThat(StubUpstreamResource.requests(RAIL_PATH)).isEmpty()
    }

    @Test
    fun `missing key is rejected before account or rail calls`() {
        val response = lookup(requestBody.replace("receipt-key", ""))
        assertThat(response.statusCode).isEqualTo(400)
        assertThat(StubUpstreamResource.requests(ACCOUNT_PATH)).isEmpty()
        assertThat(StubUpstreamResource.requests(RAIL_PATH)).isEmpty()
    }

    private fun stubOwnAccount() {
        StubUpstreamResource.stub(
            ACCOUNT_PATH,
            body = """{"id":"$ACCOUNT","partyId":"$PARTY","accountNumber":"CZ6508000000192000145399"}""",
        )
        StubUpstreamResource.stub(PARTY_PATH, body = """{"id":"$PARTY","legalName":"Alice Example"}""")
    }

    private fun lookup(body: String = requestBody) = RestAssured.given()
        .contentType("application/json")
        .body(body)
        .post("/customer/v1/sepa-payments/receipts/lookup")
}

private const val PARTY = "11111111-1111-1111-1111-111111111111"
private const val ACCOUNT = "22222222-2222-2222-2222-222222222222"
private const val ACCOUNT_PATH = "/api/v1/accounts/$ACCOUNT"
private const val PARTY_PATH = "/api/v1/parties/$PARTY"
private const val RAIL_PATH = "/api/v1/sepa-payments/receipts/lookup"
private val requestBody = """
    {"idempotencyKey":"receipt-key","payment":{
      "debtorAccountId":"$ACCOUNT","amount":"12.34","currency":"EUR",
      "creditorIban":"DE89370400440532013000","creditorName":"Berlin Utility",
      "reference":"Invoice"}}
""".trimIndent()
