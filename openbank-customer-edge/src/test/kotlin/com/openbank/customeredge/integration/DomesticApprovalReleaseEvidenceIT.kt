// SPDX-License-Identifier: Apache-2.0
package com.openbank.customeredge.integration

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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicReference

private const val INITIATOR = "11111111-1111-4111-8111-111111111111"
private const val COSIGNER = "22222222-2222-4222-8222-222222222222"
private const val COMPANY = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
private const val ACCOUNT = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
private const val APPROVAL = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
private const val SCA = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
private const val PAYMENT = "00000000-0000-4000-8000-0000000000f9"
private const val KEY = "fixture-original-held-submission"
private const val PAYLOAD_SHA = "abababababababababababababababababababababababababababababababab"
private const val APPROVALS = "/api/v1/entities/$COMPANY/approval-requests"
private const val DETAIL = "$APPROVALS/$APPROVAL"
private const val RECEIPT_LOOKUP = "$APPROVALS/submissions/lookup"
private const val RAIL = "/api/v1/domestic-payments"
private const val APPROVAL_RELEASE = "$RAIL/approval-releases"
private const val DOMESTIC_CLAIM = "$DETAIL/domestic-release-claim"
private const val RAIL_LOOKUP = "$RAIL/submissions/lookup"
private const val CONSUME = "/api/v1/sca/challenges/$SCA/consume"

@QuarkusTest
@QuarkusTestResource(BusinessApprovalStubs::class, restrictToAnnotatedClass = true)
class DomesticApprovalReleaseEvidenceIT {
    private val json = ObjectMapper()
    private val originalDigest = sha256("openbank-domestic-approval-key-v1|$KEY")
    private val scope = sha256("openbank-domestic-submission-v1|$INITIATOR|$COMPANY|$ACCOUNT|")

    @BeforeEach
    fun setup() {
        BusinessApprovalStubs.reset()
        val mandate = """[{"partyId":"$COMPANY","partyType":"COMPANY","status":"ACTIVE"}]"""
        for (party in listOf(INITIATOR, COSIGNER)) {
            BusinessApprovalStubs.stub("GET", "/api/v1/parties/$party/acting-for", body = mandate)
        }
        BusinessApprovalStubs.stub(
            "GET",
            "/api/v1/accounts/$ACCOUNT",
            body = """{"id":"$ACCOUNT","partyId":"$COMPANY"}""",
        )
    }

    @ParameterizedTest
    @ValueSource(ints = [201, 400, 404, 405, 409, 502, 503, 504])
    @TestSecurity(user = "customer:$COSIGNER", roles = ["ROLE_CUSTOMER"])
    @OidcSecurity(claims = [Claim(key = "party_id", value = COSIGNER)])
    fun `an unproven bound rail result preserves the claim and omits a release verdict`(status: Int) {
        stubLastSignature()
        val body = when (status) {
            201 -> """{"id":"1-1-1-1-1"}"""
            409 -> """{"code":"IDEMPOTENCY_KEY_REUSED"}"""
            else -> """{"error":"unproven fixture result"}"""
        }
        BusinessApprovalStubs.stub("POST", APPROVAL_RELEASE, status = status, body = body)
        val signed = sign() Then {
            statusCode(200)
            body("status", equalTo("APPROVED"))
        }
        assertThat(signed.extract().jsonPath().getMap<String, Any?>("")).doesNotContainKey("release")
        val release = BusinessApprovalStubs.requests("POST", APPROVAL_RELEASE).single()
        assertThat(release.header("Idempotency-Key")).isEqualTo(APPROVAL)
        assertThat(release.header("X-OpenBank-Original-Submission-Key-Digest")).isEqualTo(originalDigest)
        assertThat(json.readTree(release.body).path("customerSubmissionScope").asText()).isEqualTo(scope)
        assertThat(BusinessApprovalStubs.requests("POST", DOMESTIC_CLAIM)).hasSize(1)
        assertThat(BusinessApprovalStubs.requests("POST", "$DETAIL/release-claim")).isEmpty()
        assertThat(BusinessApprovalStubs.requests("POST", RAIL)).isEmpty()
        assertThat(BusinessApprovalStubs.requests("POST", "$DETAIL/release-result")).isEmpty()
        assertThat(BusinessApprovalStubs.requests("POST", CONSUME)).isEmpty()
    }

    @ParameterizedTest
    @CsvSource("400, CURRENCY_NOT_ALLOWED", "403, FORBIDDEN")
    @TestSecurity(user = "customer:$COSIGNER", roles = ["ROLE_CUSTOMER"])
    @OidcSecurity(claims = [Claim(key = "party_id", value = COSIGNER)])
    fun `a typed pre-dispatch rejection is reported as a definitive failure`(status: Int, code: String) {
        stubLastSignature()
        BusinessApprovalStubs.stub(
            "POST",
            APPROVAL_RELEASE,
            status = status,
            body = """{"code":"$code","detail":"fixture"}""",
        )
        BusinessApprovalStubs.stub("POST", "$DETAIL/release-result", body = "{}")
        sign() Then {
            statusCode(200)
            body("release.status", equalTo("RELEASE_FAILED"))
            body("release.railStatus", equalTo(status))
        }
        val report = BusinessApprovalStubs.requests("POST", "$DETAIL/release-result").single().body
        assertThat(json.readTree(report).path("ok").asBoolean()).isFalse()
        assertThat(report).contains(code).doesNotContain("fixture")
    }

    @Test
    @TestSecurity(user = "customer:$INITIATOR", roles = ["ROLE_CUSTOMER"])
    @OidcSecurity(claims = [Claim(key = "party_id", value = INITIATOR)])
    fun `read-only recovery accepts only an exact persisted bound rail receipt`() {
        stubClaimedApprovalReceipt()
        val railResponse = AtomicReference("""{"outcome":"UNKNOWN"}""")
        BusinessApprovalStubs.stub("POST", RAIL_LOOKUP) { 200 to railResponse.get() }
        lookup("wrong-original-key") Then {
            statusCode(200)
            body("outcome", equalTo("UNKNOWN"))
        }
        assertThat(BusinessApprovalStubs.requests("POST", RAIL_LOOKUP)).isEmpty()

        assertApprovalStillAwaitingRail()
        railResponse.set("""{"outcome":"FOUND","paymentId":"$PAYMENT","status":"RECEIVED"}""")
        assertApprovalStillAwaitingRail()
        railResponse.set(railReceipt(PAYLOAD_SHA))
        assertApprovalStillAwaitingRail()
        railResponse.set(railReceipt(originalDigest))
        val recovered = lookup(KEY) Then {
            statusCode(200)
            header("Cache-Control", equalTo("no-store"))
            body("approvalId", equalTo(APPROVAL))
            body("approvalStatus", equalTo("RELEASED"))
            body("paymentId", equalTo(PAYMENT))
        }
        assertThat(recovered.extract().asString()).doesNotContain(originalDigest)
        assertThat(BusinessApprovalStubs.requests("POST", RAIL_LOOKUP)).hasSize(4).allSatisfy { request ->
            assertThat(request.header("Idempotency-Key")).isEqualTo(APPROVAL)
            val body = json.readTree(request.body)
            assertThat(body.path("debtorAccountId").asText()).isEqualTo(ACCOUNT)
            assertThat(body.path("customerSubmissionScope").asText()).isEqualTo(scope)
            assertThat(body.path("originalSubmissionKeyDigest").asText()).isEqualTo(originalDigest)
            assertThat(request.body).doesNotContain(KEY)
        }
        assertThat(BusinessApprovalStubs.requests("POST", RAIL)).isEmpty()
        assertThat(BusinessApprovalStubs.requests("POST", APPROVAL_RELEASE)).isEmpty()
        assertThat(BusinessApprovalStubs.requests("POST", DOMESTIC_CLAIM)).isEmpty()
        assertThat(BusinessApprovalStubs.requests("POST", "$DETAIL/release-claim")).isEmpty()
        assertThat(BusinessApprovalStubs.requests("POST", "$DETAIL/release-result")).isEmpty()
        assertThat(BusinessApprovalStubs.requests("POST", CONSUME)).isEmpty()
    }

    @Test
    @TestSecurity(user = "customer:$INITIATOR", roles = ["ROLE_CUSTOMER"])
    @OidcSecurity(claims = [Claim(key = "party_id", value = INITIATOR)])
    fun `company approval detail and list do not turn a bare release claim into a completed payment`() {
        val claimed = approval("RELEASED")
        BusinessApprovalStubs.stub("GET", DETAIL, body = claimed)
        BusinessApprovalStubs.stub("GET", APPROVALS, body = """{"data":[$claimed]}""")
        val detail = getApproval() Then {
            statusCode(200)
            body("status", equalTo("APPROVED"))
            body("canSign", equalTo(false))
        }
        assertThat(detail.extract().jsonPath().getMap<String, Any?>("")).doesNotContainKeys("releasedAt", "releaseRef")
        Given { header("X-Acting-For", COMPANY) } When {
            get("/customer/v1/business/approvals?status=DONE")
        } Then {
            statusCode(200)
            body("data[0].status", equalTo("APPROVED"))
        }

        BusinessApprovalStubs.stub("GET", DETAIL, body = approval("RELEASED", releaseRef = PAYMENT))
        getApproval() Then {
            statusCode(200)
            body("status", equalTo("RELEASED"))
            body("releaseRef", equalTo(PAYMENT))
        }
        BusinessApprovalStubs.stub("GET", DETAIL, body = approval("RELEASED", rail = "SEPA"))
        getApproval() Then {
            statusCode(200)
            body("status", equalTo("RELEASED"))
        }
    }

    private fun stubLastSignature() {
        BusinessApprovalStubs.stub("GET", DETAIL, body = approval("PENDING"))
        BusinessApprovalStubs.stub("POST", "$DETAIL/signatures", body = approval("APPROVED"))
        BusinessApprovalStubs.stub(
            "POST",
            DOMESTIC_CLAIM,
            body = """{"claimToken":"00000000-0000-4000-8000-0000000000ee",
                "originalSubmissionKeyDigest":"$originalDigest","payload":{"rail":"DOMESTIC",
                "railRequest":{"debtorAccountId":"$ACCOUNT","customerSubmissionScope":"$scope"}}}""",
        )
    }

    private fun stubClaimedApprovalReceipt() {
        val expected = json.createObjectNode()
            .put("submissionKeyDigest", originalDigest)
            .put("submissionCorrelation", sha256("openbank-domestic-approval-v1|$scope|$KEY"))
            .put("debtorAccountId", ACCOUNT)
            .put("initiatorPartyId", INITIATOR)
        BusinessApprovalStubs.stub("POST", RECEIPT_LOOKUP) { body ->
            if (json.readTree(body) == expected) {
                200 to """{"approvalId":"$APPROVAL","status":"RELEASED"}"""
            } else {
                404 to """{"code":"NOT_FOUND"}"""
            }
        }
    }

    private fun assertApprovalStillAwaitingRail() {
        val result = lookup(KEY) Then {
            statusCode(200)
            header("Cache-Control", equalTo("no-store"))
            body("outcome", equalTo("FOUND"))
            body("approvalId", equalTo(APPROVAL))
            body("approvalStatus", equalTo("APPROVED"))
        }
        assertThat(result.extract().jsonPath().getMap<String, Any?>("")).doesNotContainKey("paymentId")
    }

    private fun lookup(key: String) = Given {
        contentType("application/json")
        header("X-Acting-For", COMPANY)
        header("Idempotency-Key", key)
        body("""{"debtorAccountId":"$ACCOUNT","amount":"400"}""")
    } When { post("/customer/v1/domestic-payments/submissions/approval-lookup") }

    private fun sign() = Given {
        header("X-Acting-For", COMPANY)
        header("X-SCA-Challenge-Id", SCA)
    } When { post("/customer/v1/business/approvals/$APPROVAL/sign") }

    private fun getApproval() = Given { header("X-Acting-For", COMPANY) } When {
        get("/customer/v1/business/approvals/$APPROVAL")
    }

    private fun railReceipt(digest: String) =
        """{"outcome":"FOUND","paymentId":"$PAYMENT","status":"RECEIVED","originalSubmissionKeyDigest":"$digest"}"""

    private fun approval(status: String, releaseRef: String? = null, rail: String = "DOMESTIC"): String {
        val ref = releaseRef?.let { "\"$it\"" } ?: "null"
        return """{"id":"$APPROVAL","entityPartyId":"$COMPANY","kind":"PAYMENT","status":"$status","required":2,
            "payloadSha256":"$PAYLOAD_SHA","initiatorPartyId":"$INITIATOR","eligibleSignerIds":["$INITIATOR","$COSIGNER"],
            "signatures":[{"partyId":"$INITIATOR"}],"releaseRef":$ref,"releasedAt":"2026-11-01T10:00:00Z",
            "payload":{"rail":"$rail","amount":"400","currency":"CZK"}}"""
    }

    private fun sha256(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))
}
