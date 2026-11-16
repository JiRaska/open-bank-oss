// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.vop.e2e

import com.openbank.vop.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

/**
 * The payer journey over the real HTTP surface (ADR-0171, money path): a payments caller verifies
 * a payee name against a domestic IBAN through both name-lookup hops, seeing `match` for the
 * exact holder name, `close_match` (with the holder name disclosed — a close match is exactly the
 * case the payer needs the spelling for) and `no_match` (which must NEVER echo the holder name,
 * or the endpoint becomes an account-holder-name oracle). An IBAN the bank does not hold is
 * `no_data`, a non-domestic IBAN is `no_data` WITHOUT any outbound hop, and the surface fails
 * closed on a blank IBAN and on a caller without a VoP role.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(NameLookupHttpServerResource::class)
@TestProfile(VopVerifyPayeeJourneyE2E.JourneyProfile::class)
class VopVerifyPayeeJourneyE2E {

    /**
     * Own boot (own container DB + Valkey), mirroring the ITs' isolation. No OPA sidecar in
     * the test: advisory mode (`authz.enforce=false`), the same choice VopRateLimitHttpIT
     * documents — the journey asserts RBAC via `@RolesAllowed`, not the fine-grained PEP.
     */
    class JourneyProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "authz.enforce" to "false",
        )
    }

    @Test
    @Order(1)
    @TestSecurity(user = "vop-e2e-payments", roles = ["ROLE_PAYMENTS"])
    fun `1 - the exact holder name verifies as a match without disclosing it`() {
        verify(NameLookupHttpServerResource.KNOWN_IBAN, "Jiri Raska").then()
            .statusCode(200)
            .body("status", equalTo("match"))
            // ADR-0171 §6: matchedName is disclosed ONLY for close_match, never echoed on a
            // plain match — that asymmetry is what stops VoP being a name-disclosure oracle.
            .body("matchedName", nullValue())
            .body("verifiedAt", notNullValue())
    }

    @Test
    @Order(2)
    @TestSecurity(user = "vop-e2e-payments", roles = ["ROLE_PAYMENTS"])
    fun `2 - a near miss discloses the holder name so the payer can fix the spelling`() {
        verify(NameLookupHttpServerResource.KNOWN_IBAN, "Raška Jiří").then()
            .statusCode(200)
            .body("status", equalTo("close_match"))
            .body("matchedName", equalTo(NameLookupHttpServerResource.HOLDER_NAME))
    }

    @Test
    @Order(3)
    @TestSecurity(user = "vop-e2e-payments", roles = ["ROLE_PAYMENTS"])
    fun `3 - a different name is a no-match and never echoes the holder name`() {
        verify(NameLookupHttpServerResource.KNOWN_IBAN, "Petr Novák").then()
            .statusCode(200)
            .body("status", equalTo("no_match"))
            .body("matchedName", nullValue())
    }

    @Test
    @Order(4)
    @TestSecurity(user = "vop-e2e-payments", roles = ["ROLE_PAYMENTS"])
    fun `4 - an IBAN the bank does not hold is no data`() {
        verify(NameLookupHttpServerResource.UNKNOWN_IBAN, "Jiri Raska").then()
            .statusCode(200)
            .body("status", equalTo("no_data"))
            .body("reason", equalTo("account_not_found"))
            .body("matchedName", nullValue())
    }

    @Test
    @Order(5)
    @TestSecurity(user = "vop-e2e-payments", roles = ["ROLE_PAYMENTS"])
    fun `5 - a non-domestic IBAN is no scheme connectivity without any outbound hop`() {
        NameLookupHttpServerResource.outboundCalls.set(0)
        verify("DE89370400440532013000", "Max Mustermann").then()
            .statusCode(200)
            .body("status", equalTo("no_data"))
            .body("reason", equalTo("no_scheme_connectivity"))
        // The whole point of the routing decision: not one packet leaves for either hop.
        org.assertj.core.api.Assertions.assertThat(NameLookupHttpServerResource.outboundCalls.get())
            .isZero()
    }

    @Test
    @Order(6)
    @TestSecurity(user = "vop-e2e-payments", roles = ["ROLE_PAYMENTS"])
    fun `6 - the surface fails closed on a blank IBAN and a blank name`() {
        verify("CZ6508000000192000145399", "  ").then().statusCode(400)
        given().contentType("application/json")
            .body("""{"creditorIban":"  ","creditorName":"Jiri Raska"}""")
            .post("/api/v1/vop/verify").then()
            .statusCode(400)
    }

    @Test
    @Order(7)
    @TestSecurity(user = "vop-e2e-auditor", roles = ["ROLE_AUDITOR"])
    fun `7 - a caller without a VoP role is refused`() {
        verify(NameLookupHttpServerResource.KNOWN_IBAN, "Jiri Raska").then()
            .statusCode(403)
    }

    private fun verify(iban: String, name: String) = given().contentType("application/json")
        .body("""{"creditorIban":"$iban","creditorName":"$name"}""")
        .post("/api/v1/vop/verify")
}
