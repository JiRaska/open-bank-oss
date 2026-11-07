// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.e2e

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.customeredge.contract.StubUpstreamResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal

private const val OWNER = "00000000-0000-4000-8000-0000000000a1"
private const val STRANGER = "00000000-0000-4000-8000-0000000000b2"
private const val ACCOUNT = "00000000-0000-4000-8000-0000000000c3"
private const val PAYMENT = "00000000-0000-4000-8000-0000000000d4"
private const val CHALLENGE = "00000000-0000-4000-8000-0000000000e5"
private const val REFUSED_CHALLENGE = "00000000-0000-4000-8000-0000000000e6"

/**
 * End-to-end journey of a customer paying from the app, through the channel every app journey enters
 * by: **see my account and balance, try to pay without authenticating strongly, have a challenge
 * refused, pay with an approved one, and poll the payment; while somebody else's token gets nothing
 * from any of those routes.**
 *
 * Driven through the real HTTP surface with the real `CustomerEdgeResource`, the real JDK-based
 * `UpstreamClient` and the real SCA consume, against a socket-level stand-in for the upstreams. No
 * resource, client or guard is replaced.
 *
 * ### Service boundaries, stated and not pretended
 *
 * customer-edge owns no money and no table, so the payment does not settle here and nothing here
 * claims it does. The upstreams answer fixed fixtures, held to their real shape by this service's
 * pacts. The token is `@TestSecurity`, so JWT signature validation is not claimed. OPA is not in the
 * loop. What this BFF does own is decided here: **who may reach which upstream, and with what
 * instruction**. The observable is therefore the set of requests that actually crossed the socket.
 * A guard that answered 403 after forwarding the payment would still pass a status assertion, and
 * would have sent the money.
 */
@QuarkusTest
@QuarkusTestResource(StubUpstreamResource::class, restrictToAnnotatedClass = true)
class CustomerPaymentJourneyE2E {

    private val mapper = ObjectMapper()

    @BeforeEach
    fun upstreams() {
        StubUpstreamResource.reset()
        StubUpstreamResource.stub(
            ACCOUNTS,
            body = """{"data":[{"id":"$ACCOUNT","partyId":"$OWNER","accountNumber":"$DEBTOR_IBAN"}]}""",
        )
        StubUpstreamResource.stub(
            "$ACCOUNTS/$ACCOUNT",
            body = """{"id":"$ACCOUNT","partyId":"$OWNER","accountNumber":"$DEBTOR_IBAN","currency":"CZK"}""",
        )
        StubUpstreamResource.stub(BALANCE, body = """{"accountId":"$ACCOUNT","available":12500.00,"currency":"CZK"}""")
        StubUpstreamResource.stub("/api/v1/parties/$OWNER", body = """{"id":"$OWNER","legalName":"Jana Nova"}""")
        StubUpstreamResource.stub(consume(CHALLENGE), body = """{"consumed":true}""")
        StubUpstreamResource.stub(consume(REFUSED_CHALLENGE), status = 409, body = """{"error":"amount mismatch"}""")
        StubUpstreamResource.stub(
            PAYMENTS,
            status = 201,
            body = """{"id":"$PAYMENT","debtorAccountId":"$ACCOUNT","status":"RECEIVED"}""",
        )
        StubUpstreamResource.stub(
            "$PAYMENTS/$PAYMENT",
            body = """{"id":"$PAYMENT","debtorAccountId":"$ACCOUNT","status":"SETTLED"}""",
        )
        // No delegation fixture: account-service owns the account, and a stranger holds no grant.
    }

    @Test
    @TestSecurity(user = "customer:$OWNER", roles = ["ROLE_CUSTOMER"])
    @OidcSecurity(claims = [Claim(key = "party_id", value = OWNER)])
    fun `a customer sees the account, is stopped twice by SCA, then pays exactly what was approved`() {
        given().get("/customer/v1/accounts").then().statusCode(200).body("data[0].id", equalTo(ACCOUNT))
        assertThat(partyHeader(ACCOUNTS)).containsExactly(OWNER)
        given().get("/customer/v1/balances/$ACCOUNT").then().statusCode(200).body("currency", equalTo("CZK"))

        // A payment the rail cannot carry is refused before it can spend a one-use challenge.
        pay(challenge = CHALLENGE, currency = "EUR").then().statusCode(400)
            .body("code", equalTo("CURRENCY_NOT_ALLOWED"))
        assertThat(StubUpstreamResource.requests(consume(CHALLENGE))).isEmpty()

        pay(challenge = null).then().statusCode(403).body("code", equalTo("SCA_REQUIRED"))
        pay(challenge = REFUSED_CHALLENGE).then().statusCode(403).body("code", equalTo("SCA_REJECTED"))
        assertThat(StubUpstreamResource.requests(PAYMENTS))
            .describedAs("no instruction may leave the edge before SCA has passed")
            .isEmpty()

        pay(challenge = CHALLENGE, key = "journey-key-1").then().statusCode(201)
            .body("id", equalTo(PAYMENT))
            .body("status", equalTo("RECEIVED"))

        // SCA dynamic linking: sca-service is asked about THIS amount and THIS payee, as this human.
        val linked = mapper.readTree(StubUpstreamResource.requests(consume(CHALLENGE)).single().body)
        assertThat(linked.path("partyId").asText()).isEqualTo(OWNER)
        assertThat(linked.path("amount").asText()).isEqualTo("150.25")
        assertThat(linked.path("currency").asText()).isEqualTo("CZK")
        assertThat(linked.path("creditor").asText()).isEqualTo(CREDITOR)

        // The instruction the bank acts on is what the edge built, not what the app claimed.
        val sent = StubUpstreamResource.requests(PAYMENTS).single()
        val instruction = mapper.readTree(sent.body)
        assertThat(instruction.path("debtorAccountId").asText()).isEqualTo(ACCOUNT)
        assertThat(instruction.path("debtorAccountNumber").asText()).endsWith("2000145399")
        assertThat(instruction.path("debtorBankCode").asText()).isEqualTo("0800")
        assertThat(instruction.path("debtorName").asText()).isEqualTo("Jana Nova")
        assertThat(instruction.path("creditorAccountNumber").asText()).isEqualTo("123456789")
        assertThat(instruction.path("creditorBankCode").asText()).isEqualTo("0100")
        assertThat(instruction.path("amount").decimalValue()).isEqualByComparingTo(BigDecimal("150.25"))
        assertThat(header(sent, "Idempotency-Key")).containsExactly("journey-key-1")
        assertThat(header(sent, "X-Customer-Party-Id")).containsExactly(OWNER)

        // The app's success screen polls this: the upstream's settled state reaches the owner.
        given().get("/customer/v1/domestic-payments/$PAYMENT").then().statusCode(200).body("status", equalTo("SETTLED"))
    }

    @Test
    @TestSecurity(user = "customer:$STRANGER", roles = ["ROLE_CUSTOMER"])
    @OidcSecurity(claims = [Claim(key = "party_id", value = STRANGER)])
    fun `another customer's token reads nothing, spends no challenge and moves no money`() {
        given().get("/customer/v1/accounts/$ACCOUNT").then().statusCode(403)
        given().get("/customer/v1/balances/$ACCOUNT").then().statusCode(403)
        assertThat(StubUpstreamResource.requests(BALANCE)).describedAs("the balance was never fetched").isEmpty()

        pay(challenge = CHALLENGE).then().statusCode(403)
        assertThat(StubUpstreamResource.requests(consume(CHALLENGE))).isEmpty()
        assertThat(StubUpstreamResource.requests(PAYMENTS)).isEmpty()

        // Indistinguishable from an id that does not exist, so ids cannot be probed.
        val response = given().get("/customer/v1/domestic-payments/$PAYMENT")
        assertThat(response.statusCode).isEqualTo(404)
        assertThat(response.body.asString()).doesNotContain(ACCOUNT, "SETTLED")
    }

    private fun pay(challenge: String?, currency: String = "CZK", key: String = "journey-key-0") = given()
        .contentType(ContentType.JSON)
        .header("Idempotency-Key", key)
        .apply { challenge?.let { header("X-SCA-Challenge-Id", it) } }
        .body(
            """
            {"debtorAccountId":"$ACCOUNT","amount":"150.25","currency":"$currency",
             "creditorAccountNumber":"$CREDITOR","creditorName":"Acme","reference":"invoice 7"}
            """.trimIndent(),
        )
        .post("/customer/v1/domestic-payments")

    private fun partyHeader(path: String) = header(StubUpstreamResource.requests(path).single(), "X-Customer-Party-Id")

    private fun header(request: StubUpstreamResource.Companion.Request, name: String): List<String> =
        request.headers.entries.single { it.key.equals(name, ignoreCase = true) }.value

    private companion object {
        const val ACCOUNTS = "/api/v1/accounts"
        const val BALANCE = "/api/v1/balances/$ACCOUNT"
        const val PAYMENTS = "/api/v1/domestic-payments"
        const val DEBTOR_IBAN = "CZ6508000000192000145399"
        const val CREDITOR = "123456789/0100"

        fun consume(challenge: String) = "/api/v1/sca/challenges/$challenge/consume"
    }
}
