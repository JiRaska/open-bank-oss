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
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

private const val INITIATOR = "11111111-1111-4111-8111-111111111111"
private const val COSIGNER = "22222222-2222-4222-8222-222222222222"
private const val OUTSIDER = "33333333-3333-4333-8333-333333333333"
private const val COMPANY = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
private const val ACCOUNT = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
private const val APPROVAL = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
private const val SCA = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
private const val SHA = "abababababababababababababababababababababababababababababababab"
private const val CREDITOR = "DE89370400440532013000"
private const val ENTITY = "/api/v1/entities/$COMPANY"
private const val EVALUATE = "$ENTITY/signing/evaluate"
private const val CREATE = "$ENTITY/approval-requests"
private const val DETAIL = "$ENTITY/approval-requests/$APPROVAL"
private const val SEPA_RAIL = "/api/v1/sepa-payments"
private const val CONSUME = "/api/v1/sca/challenges/$SCA/consume"

/**
 * #10281 business multi-signature payments over real HTTP, every upstream on a loopback stub.
 *
 * The properties, each measured by what reached which upstream rather than by a status code:
 *  - a payment under X-Acting-For whose policy needs 2 signatures is HELD: the initiator's SCA is
 *    consumed, an approval request is created carrying the frozen rail request, 202 — and the rail
 *    is never called; one that needs 1 goes to the rail exactly as before;
 *  - the signer is always the token's human; a signer's SCA is consumed with the approval id and
 *    payload hash; the initiator cannot co-sign; the last signature releases through a single-use
 *    claim with `Idempotency-Key = approvalId`; a lost claim never reaches the rail;
 *  - no mandate, no call: X-Acting-For is resolved fail-closed before delegation-service is asked.
 */
@QuarkusTest
@QuarkusTestResource(BusinessApprovalStubs::class, restrictToAnnotatedClass = true)
class BusinessPaymentApprovalIT {

    private val json = ObjectMapper()

    @BeforeEach
    fun stubs() {
        BusinessApprovalStubs.reset()
        val mandate = """[{"partyId":"$COMPANY","partyType":"COMPANY","status":"ACTIVE"}]"""
        BusinessApprovalStubs.stub("GET", "/api/v1/parties/$INITIATOR/acting-for", body = mandate)
        BusinessApprovalStubs.stub("GET", "/api/v1/parties/$COSIGNER/acting-for", body = mandate)
        BusinessApprovalStubs.stub("GET", "/api/v1/parties/$OUTSIDER/acting-for", body = "[]")
        BusinessApprovalStubs.stub("GET", "/api/v1/parties/$COMPANY", body = """{"legalName":"Firma s.r.o."}""")
        BusinessApprovalStubs.stub("GET", "/api/v1/parties/$INITIATOR", body = """{"legalName":"Jana"}""")
        BusinessApprovalStubs.stub("GET", "/api/v1/parties/$COSIGNER", body = """{"legalName":"Petr"}""")
        BusinessApprovalStubs.stub(
            "GET",
            "/api/v1/accounts/$ACCOUNT",
            body = """{"id":"$ACCOUNT","partyId":"$COMPANY","accountNumber":"CZ6508000000192000145399"}""",
        )
    }

    private fun sepaBody() =
        """{"debtorAccountId":"$ACCOUNT","amount":"80000.00","currency":"EUR","creditorIban":"$CREDITOR",""" +
            """"creditorName":"Dodavatel","reference":"INV-7"}"""

    private fun postSepa() = Given {
        contentType("application/json")
        header("X-Acting-For", COMPANY)
        header("X-SCA-Challenge-Id", SCA)
        header("Idempotency-Key", "idem-1")
        body(sepaBody())
    } When {
        post("/customer/v1/sepa-payments")
    }

    private fun detailBody(status: String = "PENDING", signatures: String = """[{"partyId":"$INITIATOR"}]""") = """
        {"id":"$APPROVAL","entityPartyId":"$COMPANY","kind":"PAYMENT","status":"$status","required":2,
         "payloadSha256":"$SHA","initiatorPartyId":"$INITIATOR","eligibleSignerIds":["$INITIATOR","$COSIGNER"],
         "signatures":$signatures,"expiresAt":"2026-09-22T10:00:00Z",
         "payload":{"rail":"SEPA","amount":"80000.00","currency":"EUR","creditorIban":"$CREDITOR",
           "creditorName":"Dodavatel","railRequest":{"type":"SCT","creditorIban":"$CREDITOR","amount":80000.00}}}
    """.trimIndent()

    private fun sign() = Given {
        header("X-Acting-For", COMPANY)
        header("X-SCA-Challenge-Id", SCA)
    } When {
        post("/customer/v1/business/approvals/$APPROVAL/sign")
    }

    private fun stubLastSignature() {
        BusinessApprovalStubs.stub("GET", DETAIL, body = detailBody())
        BusinessApprovalStubs.stub("POST", CONSUME, body = """{"status":"COMPLETED","decidedByPartyId":"$COSIGNER"}""")
        BusinessApprovalStubs.stub(
            "POST",
            "$DETAIL/signatures",
            body = detailBody("APPROVED", """[{"partyId":"$INITIATOR"},{"partyId":"$COSIGNER"}]"""),
        )
    }

    private fun readJson(body: String): JsonNode = json.readTree(body)

    // ── hold ───────────────────────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "customer:$INITIATOR", roles = ["ROLE_CUSTOMER"])
    @OidcSecurity(claims = [Claim(key = "party_id", value = INITIATOR)])
    fun `two signatures required - initiator SCA consumed, approval created, 202, rail never called`() {
        BusinessApprovalStubs.stub("POST", EVALUATE, body = """{"required":2,"eligibleSignerIds":[],"trusted":false}""")
        BusinessApprovalStubs.stub("POST", CONSUME, body = """{"status":"COMPLETED"}""")
        BusinessApprovalStubs.stub(
            "POST",
            CREATE,
            status = 201,
            body = """{"id":"$APPROVAL","required":2,"payloadSha256":"$SHA","expiresAt":"2026-09-22T10:00:00Z"}""",
        )

        postSepa() Then {
            statusCode(202)
            body("approvalId", equalTo(APPROVAL))
            body("status", equalTo("PENDING_APPROVAL"))
            body("required", equalTo(2))
            body("collected", equalTo(1))
            body("payloadSha256", equalTo(SHA))
        }

        assertThat(BusinessApprovalStubs.requests("POST", SEPA_RAIL)).isEmpty()
        // Single consumer for the initiator: the edge consumes once; delegation-service only verifies.
        val consumed = readJson(BusinessApprovalStubs.requests("POST", CONSUME).single().body)
        assertThat(consumed.path("partyId").asText()).isEqualTo(INITIATOR)
        assertThat(consumed.path("creditor").asText()).isEqualTo(CREDITOR)
        val created = readJson(BusinessApprovalStubs.requests("POST", CREATE).single().body)
        assertThat(created.path("kind").asText()).isEqualTo("PAYMENT")
        assertThat(created.path("initiatorSignature").path("partyId").asText()).isEqualTo(INITIATOR)
        assertThat(created.path("initiatorSignature").path("scaChallengeId").asText()).isEqualTo(SCA)
        assertThat(created.path("payload").path("rail").asText()).isEqualTo("SEPA")
        assertThat(created.path("payload").path("railRequest").path("debtorName").asText()).isEqualTo("Firma s.r.o.")
    }

    @Test
    @TestSecurity(user = "customer:$INITIATOR", roles = ["ROLE_CUSTOMER"])
    @OidcSecurity(claims = [Claim(key = "party_id", value = INITIATOR)])
    fun `one signature required - the rail is called once, as today, and nothing is held`() {
        BusinessApprovalStubs.stub("POST", EVALUATE, body = """{"required":1,"trusted":true}""")
        BusinessApprovalStubs.stub("POST", CONSUME, body = """{"status":"COMPLETED"}""")
        BusinessApprovalStubs.stub("POST", SEPA_RAIL, status = 201, body = """{"id":"pay-1","status":"RECEIVED"}""")

        postSepa() Then {
            statusCode(201)
            body("id", equalTo("pay-1"))
        }

        val rail = BusinessApprovalStubs.requests("POST", SEPA_RAIL).single()
        assertThat(rail.header("Idempotency-Key")).isEqualTo("idem-1")
        assertThat(rail.header("X-Customer-Party-Id")).isEqualTo(COMPANY)
        assertThat(BusinessApprovalStubs.requests("POST", CREATE)).isEmpty()
    }

    @Test
    @TestSecurity(user = "customer:$INITIATOR", roles = ["ROLE_CUSTOMER"])
    @OidcSecurity(claims = [Claim(key = "party_id", value = INITIATOR)])
    fun `a policy that cannot be evaluated refuses the payment before any SCA is spent`() {
        BusinessApprovalStubs.stub("POST", EVALUATE, status = 503, body = """{"error":"down"}""")

        postSepa() Then { statusCode(503) }

        assertThat(BusinessApprovalStubs.requests("POST", CONSUME)).isEmpty()
        assertThat(BusinessApprovalStubs.requests("POST", SEPA_RAIL)).isEmpty()
    }

    // ── sign + release ─────────────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "customer:$COSIGNER", roles = ["ROLE_CUSTOMER"])
    @OidcSecurity(claims = [Claim(key = "party_id", value = COSIGNER)])
    fun `the last signature is the human's, dynamically linked, and releases once to the rail`() {
        stubLastSignature()
        BusinessApprovalStubs.stub(
            "POST",
            "$DETAIL/release-claim",
            body = """{"claimToken":"t1","payload":{"rail":"SEPA",""" +
                """"railRequest":{"type":"SCT","creditorIban":"$CREDITOR"}}}""",
        )
        BusinessApprovalStubs.stub("POST", SEPA_RAIL, status = 201, body = """{"id":"pay-9","status":"RECEIVED"}""")
        BusinessApprovalStubs.stub("POST", "$DETAIL/release-result", body = "{}")

        sign() Then {
            statusCode(200)
            body("status", equalTo("APPROVED"))
            body("release.status", equalTo("RELEASED"))
            body("release.paymentId", equalTo("pay-9"))
        }

        // Exactly one consumer per challenge: the edge never spends a co-signer's challenge;
        // it hands it to delegation-service, which consumes it with the approval linking.
        assertThat(BusinessApprovalStubs.requests("POST", CONSUME)).isEmpty()
        val signature = readJson(BusinessApprovalStubs.requests("POST", "$DETAIL/signatures").single().body)
        assertThat(signature.path("partyId").asText()).isEqualTo(COSIGNER)
        assertThat(signature.path("scaChallengeId").asText()).isEqualTo(SCA)
        val rail = BusinessApprovalStubs.requests("POST", SEPA_RAIL).single()
        assertThat(rail.header("Idempotency-Key")).isEqualTo(APPROVAL)
        assertThat(readJson(rail.body).path("creditorIban").asText()).isEqualTo(CREDITOR)
        val result = readJson(BusinessApprovalStubs.requests("POST", "$DETAIL/release-result").single().body)
        assertThat(result.path("ok").asBoolean()).isTrue()
        assertThat(result.path("releaseRef").asText()).isEqualTo("pay-9")
    }

    @Test
    @TestSecurity(user = "customer:$COSIGNER", roles = ["ROLE_CUSTOMER"])
    @OidcSecurity(claims = [Claim(key = "party_id", value = COSIGNER)])
    fun `a second release claim is refused upstream and never reaches the rail`() {
        stubLastSignature()
        BusinessApprovalStubs.stub("POST", "$DETAIL/release-claim", status = 409, body = """{"error":"claimed"}""")

        sign() Then {
            statusCode(200)
            body("release.status", equalTo("RELEASE_NOT_CLAIMED"))
        }
        assertThat(BusinessApprovalStubs.requests("POST", SEPA_RAIL)).isEmpty()
    }

    @Test
    @TestSecurity(user = "customer:$COSIGNER", roles = ["ROLE_CUSTOMER"])
    @OidcSecurity(claims = [Claim(key = "party_id", value = COSIGNER)])
    fun `a rail refusal is reported as RELEASE_FAILED`() {
        stubLastSignature()
        BusinessApprovalStubs.stub(
            "POST",
            "$DETAIL/release-claim",
            body = """{"claimToken":"t1","payload":{"rail":"SEPA","railRequest":{"type":"SCT"}}}""",
        )
        BusinessApprovalStubs.stub("POST", SEPA_RAIL, status = 422, body = """{"error":"insufficient funds"}""")
        BusinessApprovalStubs.stub("POST", "$DETAIL/release-result", body = "{}")

        sign() Then {
            statusCode(200)
            body("release.status", equalTo("RELEASE_FAILED"))
        }
        val result = readJson(BusinessApprovalStubs.requests("POST", "$DETAIL/release-result").single().body)
        assertThat(result.path("ok").asBoolean()).isFalse()
    }

    @Test
    @TestSecurity(user = "customer:$INITIATOR", roles = ["ROLE_CUSTOMER"])
    @OidcSecurity(claims = [Claim(key = "party_id", value = INITIATOR)])
    fun `the initiator cannot co-sign their own payment - no SCA spent, nothing signed`() {
        BusinessApprovalStubs.stub("GET", DETAIL, body = detailBody())

        sign() Then {
            statusCode(409)
            body("code", equalTo("INITIATOR_CANNOT_COSIGN"))
        }
        assertThat(BusinessApprovalStubs.requests("POST", CONSUME)).isEmpty()
        assertThat(BusinessApprovalStubs.requests("POST", "$DETAIL/signatures")).isEmpty()
    }

    @Test
    @TestSecurity(user = "customer:$COSIGNER", roles = ["ROLE_CUSTOMER"])
    @OidcSecurity(claims = [Claim(key = "party_id", value = COSIGNER)])
    fun `a challenge delegation-service finds unlinked is refused and nothing is released`() {
        BusinessApprovalStubs.stub("GET", DETAIL, body = detailBody())
        BusinessApprovalStubs.stub("POST", "$DETAIL/signatures", status = 409, body = """{"code":"SCA_NOT_LINKED"}""")

        sign() Then {
            statusCode(409)
            body("code", equalTo("SCA_NOT_LINKED"))
        }
        assertThat(BusinessApprovalStubs.requests("POST", CONSUME)).isEmpty()
        assertThat(BusinessApprovalStubs.requests("POST", "$DETAIL/release-claim")).isEmpty()
    }

    @Test
    @TestSecurity(user = "customer:$OUTSIDER", roles = ["ROLE_CUSTOMER"])
    @OidcSecurity(claims = [Claim(key = "party_id", value = OUTSIDER)])
    fun `without an active mandate every business route is 403 and delegation-service is never asked`() {
        sign() Then { statusCode(403) }
        Given { header("X-Acting-For", COMPANY) } When { get("/customer/v1/business/approvals") } Then {
            statusCode(403)
        }
        Given { header("X-Acting-For", COMPANY) } When { get("/customer/v1/business/signing-policy") } Then {
            statusCode(403)
        }
        assertThat(BusinessApprovalStubs.requests("GET", DETAIL)).isEmpty()
        assertThat(BusinessApprovalStubs.requests("GET", "$ENTITY/signing-policy")).isEmpty()
    }

    // ── non-payment requests: the initiator's own SCA comes first (contract pin 1) ─────────────

    private fun policyChangeBody(status: String, signatures: String = "[]") = """
        {"id":"$APPROVAL","entityPartyId":"$COMPANY","kind":"POLICY_CHANGE","status":"$status","required":2,
         "payloadSha256":"$SHA","initiatorPartyId":"$INITIATOR","eligibleSignerIds":["$INITIATOR","$COSIGNER"],
         "signatures":$signatures,"expiresAt":"2026-09-19T10:15:00Z",
         "payload":{"rules":[{"requiredSignatures":2}]}}
    """.trimIndent()

    @Test
    @TestSecurity(user = "customer:$INITIATOR", roles = ["ROLE_CUSTOMER"])
    @OidcSecurity(claims = [Claim(key = "party_id", value = INITIATOR)])
    fun `a policy change is created AWAITING_INITIATOR and the initiator signs it with their own linked SCA`() {
        BusinessApprovalStubs.stub(
            "PUT",
            "$ENTITY/signing-policy",
            status = 201,
            body = policyChangeBody("AWAITING_INITIATOR"),
        )

        Given {
            contentType("application/json")
            header("X-Acting-For", COMPANY)
            body("""{"rules":[{"requiredSignatures":2}]}""")
        } When { put("/customer/v1/business/signing-policy") } Then {
            statusCode(202)
            body("approvalId", equalTo(APPROVAL))
            body("status", equalTo("AWAITING_INITIATOR"))
            body("payloadSha256", equalTo(SHA))
            body("collected", equalTo(0))
        }
        val proposed = readJson(BusinessApprovalStubs.requests("PUT", "$ENTITY/signing-policy").single().body)
        assertThat(proposed.path("initiatorPartyId").asText()).isEqualTo(INITIATOR)

        BusinessApprovalStubs.stub("GET", DETAIL, body = policyChangeBody("AWAITING_INITIATOR"))
        Given { header("X-Acting-For", COMPANY) } When {
            post("/customer/v1/business/approvals/$APPROVAL/sign")
        } Then {
            statusCode(403)
            body("code", equalTo("SCA_REQUIRED"))
        }
        assertThat(BusinessApprovalStubs.requests("POST", "$DETAIL/signatures")).isEmpty()

        BusinessApprovalStubs.stub("POST", CONSUME, body = """{"status":"COMPLETED","decidedByPartyId":"$INITIATOR"}""")
        BusinessApprovalStubs.stub(
            "POST",
            "$DETAIL/signatures",
            body = policyChangeBody("PENDING", """[{"partyId":"$INITIATOR"}]"""),
        )
        sign() Then {
            statusCode(200)
            body("status", equalTo("PENDING"))
        }
        assertThat(BusinessApprovalStubs.requests("POST", CONSUME)).isEmpty()
        val signature = readJson(BusinessApprovalStubs.requests("POST", "$DETAIL/signatures").single().body)
        assertThat(signature.path("partyId").asText()).isEqualTo(INITIATOR)
        assertThat(signature.path("scaChallengeId").asText()).isEqualTo(SCA)
        assertThat(BusinessApprovalStubs.requests("POST", "$DETAIL/release-claim")).isEmpty()
    }

    @Test
    @TestSecurity(user = "customer:$COSIGNER", roles = ["ROLE_CUSTOMER"])
    @OidcSecurity(claims = [Claim(key = "party_id", value = COSIGNER)])
    fun `a policy change without the initiator's SCA takes no co-signature`() {
        BusinessApprovalStubs.stub("GET", DETAIL, body = policyChangeBody("AWAITING_INITIATOR"))

        sign() Then {
            statusCode(409)
            body("code", equalTo("AWAITING_INITIATOR"))
        }
        assertThat(BusinessApprovalStubs.requests("POST", CONSUME)).isEmpty()
        assertThat(BusinessApprovalStubs.requests("POST", "$DETAIL/signatures")).isEmpty()
    }

    // ── read routes ────────────────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "customer:$COSIGNER", roles = ["ROLE_CUSTOMER"])
    @OidcSecurity(claims = [Claim(key = "party_id", value = COSIGNER)])
    fun `detail names the signers and says the co-signer can sign`() {
        BusinessApprovalStubs.stub("GET", DETAIL, body = detailBody())

        Given { header("X-Acting-For", COMPANY) } When { get("/customer/v1/business/approvals/$APPROVAL") } Then {
            statusCode(200)
            body("canSign", equalTo(true))
            body("collected", equalTo(1))
            body("signers.name", hasItem("Jana"))
            body("signers.find { it.partyId == '$INITIATOR' }.status", equalTo("SIGNED"))
        }
    }

    @Test
    @TestSecurity(user = "customer:$COSIGNER", roles = ["ROLE_CUSTOMER"])
    @OidcSecurity(claims = [Claim(key = "party_id", value = COSIGNER)])
    fun `the signing policy comes with a plain-language summary`() {
        BusinessApprovalStubs.stub(
            "GET",
            "$ENTITY/signing-policy",
            body = """{"entityPartyId":"$COMPANY","version":0,"updatedByApprovalId":null,"rules":[
                {"maxAmount":{"amount":"50000","currency":"CZK"},"requiredSignatures":1},
                {"minAmount":{"amount":"50000","currency":"CZK"},"requiredSignatures":2}]}""",
        )

        Given { header("X-Acting-For", COMPANY) } When { get("/customer/v1/business/signing-policy") } Then {
            statusCode(200)
            body("summary[0]", equalTo("Platby do 50 000 Kč podepíše kdokoli z vás sám."))
            body("summary[1]", equalTo("Platby nad 50 000 Kč potřebují 2 podpisy."))
            body("isDerivedFromRegister", equalTo(true))
        }
        Given {
            header("X-Acting-For", COMPANY)
            header("Accept-Language", "en-GB")
        } When { get("/customer/v1/business/signing-policy") } Then {
            body("summary[1]", equalTo("Payments over CZK 50,000 need 2 signatures."))
        }
    }

    @Test
    @TestSecurity(user = "customer:$INITIATOR", roles = ["ROLE_CUSTOMER"])
    @OidcSecurity(claims = [Claim(key = "party_id", value = INITIATOR)])
    fun `adding a trusted payee creates an approval request initiated by the human`() {
        BusinessApprovalStubs.stub("POST", "$ENTITY/trusted-payees", status = 201, body = """{"id":"$APPROVAL"}""")

        Given {
            contentType("application/json")
            header("X-Acting-For", COMPANY)
            body("""{"iban":"cz65 0800 0000 1920 0014 5399","name":"Dodavatel s.r.o.","initiatorPartyId":"$COMPANY"}""")
        } When { post("/customer/v1/business/trusted-payees") } Then {
            statusCode(202)
        }
        val sent = readJson(BusinessApprovalStubs.requests("POST", "$ENTITY/trusted-payees").single().body)
        assertThat(sent.path("initiatorPartyId").asText()).isEqualTo(INITIATOR)
        assertThat(sent.path("iban").asText()).isEqualTo("CZ6508000000192000145399")

        Given {
            contentType("application/json")
            header("X-Acting-For", COMPANY)
            body("""{"iban":"nope","name":"X"}""")
        } When { post("/customer/v1/business/trusted-payees") } Then {
            statusCode(400)
        }
    }

    @Test
    @TestSecurity(user = "customer:$COSIGNER", roles = ["ROLE_CUSTOMER"])
    @OidcSecurity(claims = [Claim(key = "party_id", value = COSIGNER)])
    fun `my pending approvals are the human's across entities, whatever X-Acting-For says`() {
        val path = "/api/v1/parties/$COSIGNER/approval-requests/pending"
        BusinessApprovalStubs.stub("GET", path, body = """[{"entityPartyId":"$COMPANY","count":2}]""")

        Given { header("X-Acting-For", COMPANY) } When { get("/customer/v1/me/approvals/pending") } Then {
            statusCode(200)
            body("data[0].count", equalTo(2))
        }
        assertThat(
            BusinessApprovalStubs.requests("GET", path).single().header("X-Customer-Party-Id"),
        ).isEqualTo(COSIGNER)
    }
}
