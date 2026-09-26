// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.integration

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
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
private const val CREDITOR = "CZ6508000000192000145399"
private const val CREDITOR_ID = "CZ12ZZZ12345678"
private const val ENTITY = "/api/v1/entities/$COMPANY"
private const val EVALUATE = "$ENTITY/signing/evaluate"
private const val CREATE = "$ENTITY/approval-requests"
private const val DETAIL = "$ENTITY/approval-requests/$APPROVAL"
private const val SO_UPSTREAM = "/api/v1/standing-orders"
private const val SDD_UPSTREAM = "/api/v1/sdd/mandates"
private const val CONSUME = "/api/v1/sca/challenges/$SCA/consume"
private const val OLD_ORDER = "ffffffff-ffff-4fff-8fff-ffffffffffff"

/**
 * #10281 gap: a standing order or an SDD mandate created under X-Acting-For is a recurring outflow
 * and must follow the entity's signing policy exactly like a payment. Measured by what reached
 * which upstream, over real HTTP, every upstream on a loopback stub.
 *
 * Sabotage (each goes red when its guard is removed):
 *  - delete the `holdForApproval` call in `createStandingOrder` -> the JOINT tests see the
 *    standing-order upstream called with one signature;
 *  - same in `createSddMandate` -> the SDD hold test;
 *  - `RELEASABLE_KINDS` back to `PAYMENT` only -> the release test sees no release;
 *  - evaluate the SDD mandate with a client amount -> the SDD test sees an `amount` in evaluate.
 */
@QuarkusTest
@QuarkusTestResource(BusinessApprovalStubs::class, restrictToAnnotatedClass = true)
class BusinessRecurringApprovalIT {

    private val json = ObjectMapper()

    @BeforeEach
    fun stubs() {
        BusinessApprovalStubs.reset()
        val mandate = """[{"partyId":"$COMPANY","partyType":"COMPANY","status":"ACTIVE"}]"""
        BusinessApprovalStubs.stub("GET", "/api/v1/parties/$INITIATOR/acting-for", body = mandate)
        BusinessApprovalStubs.stub("GET", "/api/v1/parties/$COSIGNER/acting-for", body = mandate)
        BusinessApprovalStubs.stub("GET", "/api/v1/parties/$COMPANY", body = """{"legalName":"Firma s.r.o."}""")
        BusinessApprovalStubs.stub("GET", "/api/v1/parties/$INITIATOR", body = """{"legalName":"Jana"}""")
        BusinessApprovalStubs.stub("GET", "/api/v1/parties/$COSIGNER", body = """{"legalName":"Petr"}""")
        BusinessApprovalStubs.stub(
            "GET",
            "/api/v1/accounts/$ACCOUNT",
            body = """{"id":"$ACCOUNT","partyId":"$COMPANY","accountNumber":"CZ5508000000001234567899"}""",
        )
    }

    private fun readJson(body: String): JsonNode = json.readTree(body)

    private fun stubHeld() {
        BusinessApprovalStubs.stub("POST", EVALUATE, body = """{"required":2,"eligibleSignerIds":[],"trusted":false}""")
        BusinessApprovalStubs.stub("POST", CONSUME, body = """{"status":"COMPLETED"}""")
        BusinessApprovalStubs.stub(
            "POST",
            CREATE,
            status = 201,
            body = """{"id":"$APPROVAL","required":2,"payloadSha256":"$SHA","expiresAt":"2026-09-24T10:00:00Z"}""",
        )
    }

    private fun postStandingOrder(sca: String? = SCA) = Given {
        contentType("application/json")
        header("X-Acting-For", COMPANY)
        sca?.let { header("X-SCA-Challenge-Id", it) }
        body(
            """{"debitAccountId":"$ACCOUNT","creditorIban":"$CREDITOR","creditorName":"Pronajímatel",""" +
                """"amountMinorUnits":150000,"currency":"CZK","frequency":"MONTHLY","paymentType":"DOMESTIC",""" +
                """"remittanceInfo":"Nájem"}""",
        )
    } When {
        post("/customer/v1/standing-orders")
    }

    private fun postSddMandate(sca: String? = SCA) = Given {
        contentType("application/json")
        header("X-Acting-For", COMPANY)
        sca?.let { header("X-SCA-Challenge-Id", it) }
        body("""{"accountId":"$ACCOUNT","creditorName":"Energie a.s.","creditorIdentifier":"$CREDITOR_ID"}""")
    } When {
        post("/customer/v1/sdd/mandates")
    }

    // ── standing order ────────────────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "customer:$INITIATOR", roles = ["ROLE_CUSTOMER"])
    @OidcSecurity(claims = [Claim(key = "party_id", value = INITIATOR)])
    fun `a JOINT representative cannot set up a standing order alone - it is held, upstream never called`() {
        stubHeld()

        postStandingOrder() Then {
            statusCode(202)
            body("approvalId", equalTo(APPROVAL))
            body("status", equalTo("PENDING_APPROVAL"))
            body("required", equalTo(2))
            body("collected", equalTo(1))
            body("payloadSha256", equalTo(SHA))
            body("expiresAt", equalTo("2026-09-24T10:00:00Z"))
        }

        assertThat(BusinessApprovalStubs.requests("POST", SO_UPSTREAM)).isEmpty()
        // Banded by the PER-EXECUTION amount: 150000 minor units of CZK = 1500.00.
        val evaluated = readJson(BusinessApprovalStubs.requests("POST", EVALUATE).single().body)
        assertThat(evaluated.path("kind").asText()).isEqualTo("STANDING_ORDER")
        assertThat(evaluated.path("amount").asText()).isEqualTo("1500.00")
        assertThat(evaluated.path("currency").asText()).isEqualTo("CZK")
        val consumed = readJson(BusinessApprovalStubs.requests("POST", CONSUME).single().body)
        assertThat(consumed.path("partyId").asText()).isEqualTo(INITIATOR)
        assertThat(consumed.path("amount").asText()).isEqualTo("1500.00")
        assertThat(consumed.path("creditor").asText()).isEqualTo(CREDITOR)
        val created = readJson(BusinessApprovalStubs.requests("POST", CREATE).single().body)
        assertThat(created.path("kind").asText()).isEqualTo("STANDING_ORDER")
        assertThat(created.path("initiatorSignature").path("partyId").asText()).isEqualTo(INITIATOR)
        assertThat(created.path("payload").path("rail").asText()).isEqualTo("STANDING_ORDER")
        assertThat(created.path("payload").path("frequency").asText()).isEqualTo("MONTHLY")
        val frozen = created.path("payload").path("railRequest")
        assertThat(frozen.path("partyId").asText()).isEqualTo(COMPANY)
        assertThat(frozen.path("amountMinorUnits").asLong()).isEqualTo(150_000L)
        assertThat(frozen.path("idempotencyKey").asText()).isNotBlank()
    }

    @Test
    @TestSecurity(user = "customer:$INITIATOR", roles = ["ROLE_CUSTOMER"])
    @OidcSecurity(claims = [Claim(key = "party_id", value = INITIATOR)])
    fun `a held standing order without the initiator's SCA is refused and nothing is created`() {
        stubHeld()

        postStandingOrder(sca = null) Then {
            statusCode(403)
            body("code", equalTo("SCA_REQUIRED"))
        }
        assertThat(BusinessApprovalStubs.requests("POST", CREATE)).isEmpty()
        assertThat(BusinessApprovalStubs.requests("POST", SO_UPSTREAM)).isEmpty()
    }

    @Test
    @TestSecurity(user = "customer:$INITIATOR", roles = ["ROLE_CUSTOMER"])
    @OidcSecurity(claims = [Claim(key = "party_id", value = INITIATOR)])
    fun `one signature required - the standing order goes upstream once, as today, with no SCA spent`() {
        BusinessApprovalStubs.stub("POST", EVALUATE, body = """{"required":1,"trusted":false}""")
        BusinessApprovalStubs.stub("POST", SO_UPSTREAM, status = 201, body = """{"id":"so-1","status":"ACTIVE"}""")

        postStandingOrder(sca = null) Then {
            statusCode(201)
            body("id", equalTo("so-1"))
        }
        assertThat(BusinessApprovalStubs.requests("POST", SO_UPSTREAM)).hasSize(1)
        assertThat(BusinessApprovalStubs.requests("POST", CONSUME)).isEmpty()
        assertThat(BusinessApprovalStubs.requests("POST", CREATE)).isEmpty()
    }

    @Test
    @TestSecurity(user = "customer:$INITIATOR", roles = ["ROLE_CUSTOMER"])
    @OidcSecurity(claims = [Claim(key = "party_id", value = INITIATOR)])
    fun `a policy that cannot be evaluated refuses the standing order`() {
        BusinessApprovalStubs.stub("POST", EVALUATE, status = 503, body = """{"error":"down"}""")

        postStandingOrder() Then { statusCode(503) }
        assertThat(BusinessApprovalStubs.requests("POST", SO_UPSTREAM)).isEmpty()
        assertThat(BusinessApprovalStubs.requests("POST", CONSUME)).isEmpty()
    }

    private fun postEdit(replaces: String) = Given {
        contentType("application/json")
        header("X-Acting-For", COMPANY)
        header("X-SCA-Challenge-Id", SCA)
        body(
            """{"debitAccountId":"$ACCOUNT","creditorIban":"$CREDITOR","creditorName":"Pronajímatel",""" +
                """"amountMinorUnits":175000,"currency":"CZK","frequency":"MONTHLY","paymentType":"DOMESTIC",""" +
                """"replacesStandingOrderId":"$replaces"}""",
        )
    } When {
        post("/customer/v1/standing-orders")
    }

    @Test
    @TestSecurity(user = "customer:$INITIATOR", roles = ["ROLE_CUSTOMER"])
    @OidcSecurity(claims = [Claim(key = "party_id", value = INITIATOR)])
    fun `a held edit freezes the replaced order id and touches neither order until release`() {
        stubHeld()
        BusinessApprovalStubs.stub(
            "GET",
            "$SO_UPSTREAM/$OLD_ORDER",
            body = """{"id":"$OLD_ORDER","partyId":"$COMPANY"}""",
        )

        postEdit(OLD_ORDER) Then { statusCode(202) }

        val created = readJson(BusinessApprovalStubs.requests("POST", CREATE).single().body)
        assertThat(created.path("payload").path("replacesStandingOrderId").asText()).isEqualTo(OLD_ORDER)
        assertThat(created.path("payload").path("railRequest").path("replacesStandingOrderId").asText())
            .isEqualTo(OLD_ORDER)
        // Rejection or expiry never releases, so the old order is exactly as it was: no create,
        // no cancel, nothing sent to standing-order-service beyond the ownership read.
        assertThat(BusinessApprovalStubs.requests("POST", SO_UPSTREAM)).isEmpty()
        assertThat(BusinessApprovalStubs.requests("DELETE", "$SO_UPSTREAM/$OLD_ORDER")).isEmpty()
    }

    @Test
    @TestSecurity(user = "customer:$INITIATOR", roles = ["ROLE_CUSTOMER"])
    @OidcSecurity(claims = [Claim(key = "party_id", value = INITIATOR)])
    fun `an edit naming another party's order is refused before any signing`() {
        stubHeld()
        BusinessApprovalStubs.stub(
            "GET",
            "$SO_UPSTREAM/$OLD_ORDER",
            body = """{"id":"$OLD_ORDER","partyId":"$COSIGNER"}""",
        )

        postEdit(OLD_ORDER) Then { statusCode(403) }
        assertThat(BusinessApprovalStubs.requests("POST", EVALUATE)).isEmpty()
        assertThat(BusinessApprovalStubs.requests("POST", CONSUME)).isEmpty()
    }

    // ── SDD mandate ───────────────────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "customer:$INITIATOR", roles = ["ROLE_CUSTOMER"])
    @OidcSecurity(claims = [Claim(key = "party_id", value = INITIATOR)])
    fun `a JOINT representative cannot authorise an SDD mandate alone - strictest rule, upstream never called`() {
        stubHeld()

        postSddMandate() Then {
            statusCode(202)
            body("status", equalTo("PENDING_APPROVAL"))
            body("collected", equalTo(1))
        }

        assertThat(BusinessApprovalStubs.requests("POST", SDD_UPSTREAM)).isEmpty()
        val evaluated = readJson(BusinessApprovalStubs.requests("POST", EVALUATE).single().body)
        assertThat(evaluated.path("kind").asText()).isEqualTo("SDD_MANDATE")
        // No amount: sdd-service stores no maximum, so delegation-service applies the strictest rule.
        assertThat(evaluated.has("amount")).isFalse()
        val consumed = readJson(BusinessApprovalStubs.requests("POST", CONSUME).single().body)
        assertThat(consumed.path("creditor").asText()).isEqualTo(CREDITOR_ID)
        assertThat(consumed.has("amount")).isFalse()
        val created = readJson(BusinessApprovalStubs.requests("POST", CREATE).single().body)
        assertThat(created.path("kind").asText()).isEqualTo("SDD_MANDATE")
        assertThat(created.path("payload").path("creditorIdentifier").asText()).isEqualTo(CREDITOR_ID)
        assertThat(created.path("payload").path("mandateReference").asText()).startsWith("UMR-")
        assertThat(created.path("payload").path("railRequest").path("debtorIban").asText())
            .isEqualTo("CZ5508000000001234567899")
    }

    @Test
    @TestSecurity(user = "customer:$INITIATOR", roles = ["ROLE_CUSTOMER"])
    @OidcSecurity(claims = [Claim(key = "party_id", value = INITIATOR)])
    fun `one signature required - the SDD mandate goes upstream once, as today`() {
        BusinessApprovalStubs.stub("POST", EVALUATE, body = """{"required":1,"trusted":false}""")
        BusinessApprovalStubs.stub("POST", SDD_UPSTREAM, status = 201, body = """{"id":"m-1","status":"ACTIVE"}""")

        postSddMandate(sca = null) Then { statusCode(201) }
        assertThat(BusinessApprovalStubs.requests("POST", SDD_UPSTREAM)).hasSize(1)
        assertThat(BusinessApprovalStubs.requests("POST", CREATE)).isEmpty()
        assertThat(BusinessApprovalStubs.requests("POST", CONSUME)).isEmpty()
    }

    // ── sign + release ────────────────────────────────────────────────────────────────────────

    private fun detailBody(status: String, signatures: String) = """
        {"id":"$APPROVAL","entityPartyId":"$COMPANY","kind":"STANDING_ORDER","status":"$status","required":2,
         "payloadSha256":"$SHA","initiatorPartyId":"$INITIATOR","eligibleSignerIds":["$INITIATOR","$COSIGNER"],
         "signatures":$signatures,"expiresAt":"2026-09-24T10:00:00Z",
         "payload":{"rail":"STANDING_ORDER","amount":"1500.00","currency":"CZK","creditorIban":"$CREDITOR",
           "creditorName":"Pronajímatel","railRequest":{"partyId":"$COMPANY","amountMinorUnits":150000}}}
    """.trimIndent()

    private fun stubLastSignature() {
        BusinessApprovalStubs.stub("GET", DETAIL, body = detailBody("PENDING", """[{"partyId":"$INITIATOR"}]"""))
        BusinessApprovalStubs.stub(
            "POST",
            "$DETAIL/signatures",
            body = detailBody("APPROVED", """[{"partyId":"$INITIATOR"},{"partyId":"$COSIGNER"}]"""),
        )
    }

    private fun sign() = Given {
        header("X-Acting-For", COMPANY)
        header("X-SCA-Challenge-Id", SCA)
    } When {
        post("/customer/v1/business/approvals/$APPROVAL/sign")
    }

    @Test
    @TestSecurity(user = "customer:$COSIGNER", roles = ["ROLE_CUSTOMER"])
    @OidcSecurity(claims = [Claim(key = "party_id", value = COSIGNER)])
    fun `the last signature releases the frozen standing order once, keyed by the approval id`() {
        stubLastSignature()
        BusinessApprovalStubs.stub(
            "POST",
            "$DETAIL/release-claim",
            body = """{"claimToken":"t1","payload":{"rail":"STANDING_ORDER",""" +
                """"railRequest":{"partyId":"$COMPANY","amountMinorUnits":150000}}}""",
        )
        BusinessApprovalStubs.stub("POST", SO_UPSTREAM, status = 201, body = """{"id":"so-9","status":"ACTIVE"}""")
        BusinessApprovalStubs.stub("POST", "$DETAIL/release-result", body = "{}")

        sign() Then {
            statusCode(200)
            body("release.status", equalTo("RELEASED"))
            body("release.paymentId", equalTo("so-9"))
        }
        val upstream = BusinessApprovalStubs.requests("POST", SO_UPSTREAM).single()
        assertThat(upstream.header("Idempotency-Key")).isEqualTo(APPROVAL)
        assertThat(upstream.header("X-Customer-Party-Id")).isEqualTo(COMPANY)
        assertThat(readJson(upstream.body).path("amountMinorUnits").asLong()).isEqualTo(150_000L)
        val result = readJson(BusinessApprovalStubs.requests("POST", "$DETAIL/release-result").single().body)
        assertThat(result.path("releaseRef").asText()).isEqualTo("so-9")
    }

    @Test
    @TestSecurity(user = "customer:$COSIGNER", roles = ["ROLE_CUSTOMER"])
    @OidcSecurity(claims = [Claim(key = "party_id", value = COSIGNER)])
    fun `a second release claim never reaches the standing-order service`() {
        stubLastSignature()
        BusinessApprovalStubs.stub("POST", "$DETAIL/release-claim", status = 409, body = """{"error":"claimed"}""")

        sign() Then { body("release.status", equalTo("RELEASE_NOT_CLAIMED")) }
        assertThat(BusinessApprovalStubs.requests("POST", SO_UPSTREAM)).isEmpty()
    }

    @Test
    @TestSecurity(user = "customer:$INITIATOR", roles = ["ROLE_CUSTOMER"])
    @OidcSecurity(claims = [Claim(key = "party_id", value = INITIATOR)])
    fun `the initiator cannot co-sign their own standing order`() {
        BusinessApprovalStubs.stub("GET", DETAIL, body = detailBody("PENDING", """[{"partyId":"$INITIATOR"}]"""))

        sign() Then {
            statusCode(409)
            body("code", equalTo("INITIATOR_CANNOT_COSIGN"))
        }
        assertThat(BusinessApprovalStubs.requests("POST", "$DETAIL/signatures")).isEmpty()
        assertThat(BusinessApprovalStubs.requests("POST", SO_UPSTREAM)).isEmpty()
    }
}
