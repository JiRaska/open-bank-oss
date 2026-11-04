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
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

private const val PARTY_A = "00000000-0000-4000-8000-0000000000a1"
private const val PARTY_B = "00000000-0000-4000-8000-0000000000b2"
private const val HOLDING_ID = "00000000-0000-4000-8000-0000000000c3"
private const val HOLDING_PATH = "/api/v1/holdings/$HOLDING_ID"

/** The served edge route must hide a concrete A-owned holding from an authenticated B caller (#11966). */
@QuarkusTest
@QuarkusTestResource(StubUpstreamResource::class, restrictToAnnotatedClass = true)
class CustomerHoldingsCrossPartyIT {

    @BeforeEach
    fun stubOwnedHolding() {
        StubUpstreamResource.reset()
        StubUpstreamResource.stub(
            HOLDING_PATH,
            body = """
                {"holdingId":"$HOLDING_ID","ownerPartyId":"$PARTY_A","holdingType":"COLLECTIBLE",
                 "isLiability":false,"label":"Synthetic holding","amount":1000.00,"currency":"CZK",
                 "valuedAt":"2026-01-15","valuationSource":"CUSTOMER_DECLARED",
                 "ownershipShare":1,"attributableAmount":1000.00,"valuationAgeDays":1,
                 "documentIds":[],"status":"ACTIVE","createdAt":"2026-01-15T00:00:00Z",
                 "updatedAt":"2026-01-15T00:00:00Z"}
            """.trimIndent(),
        )
    }

    @Test
    @TestSecurity(user = "customer:$PARTY_A", roles = ["ROLE_CUSTOMER"])
    @OidcSecurity(claims = [Claim(key = "party_id", value = PARTY_A)])
    fun `owner can read the holding through the authenticated edge route`() {
        val response = given().get("/customer/v1/holdings/$HOLDING_ID")
        assertThat(response.statusCode).isEqualTo(200)
        assertThat(response.jsonPath().getString("holdingId")).isEqualTo(HOLDING_ID)
        assertThat(upstreamPartyHeader()).containsExactly(PARTY_A)
    }

    @Test
    @TestSecurity(user = "customer:$PARTY_B", roles = ["ROLE_CUSTOMER"])
    @OidcSecurity(claims = [Claim(key = "party_id", value = PARTY_B)])
    fun `another authenticated party gets 404 for that existing holding`() {
        val response = given().get("/customer/v1/holdings/$HOLDING_ID")
        assertThat(response.statusCode).isEqualTo(404)
        assertThat(response.body.asString()).doesNotContain(PARTY_A, "Synthetic holding")
        assertThat(upstreamPartyHeader()).containsExactly(PARTY_B)
    }

    private fun upstreamPartyHeader(): List<String> = StubUpstreamResource.requests(HOLDING_PATH).single().headers
        .entries.single { it.key.equals("X-Customer-Party-Id", ignoreCase = true) }.value
}
