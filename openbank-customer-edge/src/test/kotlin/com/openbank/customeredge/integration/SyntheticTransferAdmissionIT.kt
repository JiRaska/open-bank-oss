// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.libs.synthetic.SyntheticTaint
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

@QuarkusTest
@QuarkusTestResource(value = SyntheticTransferAdmissionIT.FixtureResource::class, restrictToAnnotatedClass = true)
class SyntheticTransferAdmissionIT {

    class FixtureResource : QuarkusTestResourceLifecycleManager {
        private val upstreams = BusinessApprovalStubs()

        override fun start(): Map<String, String> {
            val base = upstreams.start().getValue("openbank.edge.party-service-url")
            return mapOf(
                "openbank.upstream.token-url" to base,
                "openbank.edge.party-service-url" to base,
                "openbank.edge.account-service-url" to base,
                "openbank.edge.transaction-service-url" to base,
                "openbank.synthetic.trusted-principals" to CANARY_PRINCIPAL,
                "openbank.synthetic-transfer.party-id" to PARTY_ID,
                "openbank.synthetic-transfer.source-account-id" to SOURCE_ID,
                "openbank.synthetic-transfer.target-account-id" to TARGET_ID,
            )
        }

        override fun stop() = upstreams.stop()
    }

    @Test
    @TestSecurity(user = CANARY_PRINCIPAL, roles = ["ROLE_CUSTOMER"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-synthetic-transfer"),
            Claim(key = "preferred_username", value = CANARY_PRINCIPAL),
            Claim(key = "sub", value = "synthetic-transfer-edge-it"),
            Claim(key = "party_id", value = PARTY_ID),
        ],
    )
    fun `only the trusted synthetic fixture reaches the synthetic transaction route`() {
        BusinessApprovalStubs.reset()
        BusinessApprovalStubs.stub("GET", PARTY_PATH, body = party("SYNTHETIC"))
        for (account in listOf(SOURCE_ID, TARGET_ID)) {
            BusinessApprovalStubs.stub(
                "GET",
                "/api/v1/accounts/$account",
                body = """{"id":"$account","partyId":"$PARTY_ID","status":"ACTIVE","currencyCode":"CZK"}""",
            )
        }
        BusinessApprovalStubs.stub("POST", TX_PATH, 201, """{"id":"$TRANSACTION_ID"}""")

        val calendar = given().header(SyntheticTaint.KAFKA_HEADER, SyntheticTaint.TRUE_VALUE).get(EDGE_PATH)
        assertThat(calendar.statusCode).isEqualTo(200)
        assertThat(calendar.getHeader("Cache-Control")).contains("no-store")
        assertThat(calendar.jsonPath().getString("runDate"))
            .isEqualTo(LocalDate.now(AccountingClock.BANK_ZONE).toString())
        assertThat(given().get(EDGE_PATH).statusCode).isEqualTo(403)
        assertThat(submit(tainted = true)).isEqualTo(201)
        assertThat(submit(tainted = false)).isEqualTo(403)
        assertThat(submit(tainted = true, actingFor = PARTY_ID)).isEqualTo(403)
        BusinessApprovalStubs.stub("GET", PARTY_PATH, body = party("REAL"))
        assertThat(submit(tainted = true)).isEqualTo(403)
        BusinessApprovalStubs.stub("GET", PARTY_PATH, body = party("SYNTHETIC"))
        BusinessApprovalStubs.stub(
            "GET",
            "/api/v1/accounts/$TARGET_ID",
            body = """{"id":"$TARGET_ID","partyId":"$PARTY_ID","status":"CLOSED","currencyCode":"CZK"}""",
        )
        assertThat(submit(tainted = true)).isEqualTo(403)

        val forwarded = BusinessApprovalStubs.requests("POST", TX_PATH).single()
        assertThat(forwarded.header(SyntheticTaint.KAFKA_HEADER)).isEqualTo(SyntheticTaint.TRUE_VALUE)
        val body = ObjectMapper().readTree(forwarded.body)
        assertThat(body.path("partyId").asText()).isEqualTo(PARTY_ID)
        assertThat(body.path("sourceAccountId").asText()).isEqualTo(SOURCE_ID)
        assertThat(body.path("targetAccountId").asText()).isEqualTo(TARGET_ID)
        assertThat(body.path("direction").asText()).isEqualTo("OUTBOUND")
        assertThat(forwarded.header("Idempotency-Key"))
            .isEqualTo("synthetic-transfer:$PARTY_ID:${body.path("runDate").asText()}:OUTBOUND")
        assertThat(BusinessApprovalStubs.requests("POST", "/api/v1/transactions")).isEmpty()
    }

    private fun party(classification: String): String =
        """{"id":"$PARTY_ID","classification":"$classification","status":"ACTIVE"}"""

    private fun submit(tainted: Boolean, actingFor: String? = null): Int = given().contentType("application/json")
        .body("""{"runDate":"${LocalDate.now(AccountingClock.BANK_ZONE)}","direction":"OUTBOUND"}""")
        .apply { if (tainted) header(SyntheticTaint.KAFKA_HEADER, SyntheticTaint.TRUE_VALUE) }
        .apply { if (actingFor != null) header("X-Acting-For", actingFor) }
        .post(EDGE_PATH).statusCode

    private companion object {
        const val CANARY_PRINCIPAL = "service-account-openbank-synthetic-transfer"
        const val PARTY_ID = "00000000-0000-4000-8000-000000000011"
        const val SOURCE_ID = "00000000-0000-4000-8000-000000000012"
        const val TARGET_ID = "00000000-0000-4000-8000-000000000013"
        const val TRANSACTION_ID = "00000000-0000-4000-8000-000000000014"
        const val PARTY_PATH = "/api/v1/parties/$PARTY_ID"
        const val EDGE_PATH = "/customer/v1/synthetic/transfers"
        const val TX_PATH = "/api/v1/transactions/synthetic-transfer"
    }
}
