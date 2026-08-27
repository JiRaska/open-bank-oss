// SPDX-License-Identifier: Apache-2.0
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@QuarkusTest
@QuarkusTestResource(StubUpstreamResource::class, restrictToAnnotatedClass = true)
@TestSecurity(user = "customer:$CLAIM_PARTY_ID", roles = ["ROLE_CUSTOMER"])
@OidcSecurity(claims = [Claim(key = "party_id", value = CLAIM_PARTY_ID)])
class CustomerIncentiveClaimIT {
    @BeforeEach
    fun resetUpstreams() = StubUpstreamResource.reset()

    @Test
    fun `authenticated customer claim derives party and reaches trusted incentive route`() {
        stubProductAndAttribution()
        StubUpstreamResource.stub(
            INCENTIVE_PATH,
            status = 201,
            body = RESERVATION_JSON,
        )

        Given {
            contentType("application/json")
            header("Idempotency-Key", "claim-http-once")
            body(CLAIM_JSON)
        } When {
            post("/customer/v1/incentives/claims")
        } Then {
            statusCode(201)
            body(containsString("RESERVED"))
        }

        val request = StubUpstreamResource.requests(INCENTIVE_PATH).single()
        assertThat(request.headers.entries.single { it.key.equals("X-Customer-Party-Id", true) }.value)
            .containsExactly(CLAIM_PARTY_ID)
        assertThat(request.headers.entries.single { it.key.equals("Idempotency-Key", true) }.value)
            .containsExactly("claim-http-once")
        val json = ObjectMapper().readTree(request.body)
        assertThat(json.path("attributionRef").asText()).isEqualTo(INTERACTION_ID)
        assertThat(json.path("productRef").asText()).isEqualTo(PRODUCT_ID)
        assertThat(json.has("partyRef")).isFalse()
    }

    @Test
    fun `foreign interaction fails closed before incentive service`() {
        StubUpstreamResource.stub(PRODUCT_PATH, body = PRODUCT_JSON)
        StubUpstreamResource.stub(ATTRIBUTION_PATH, status = 404, body = "{\"error\":\"not found\"}")

        Given {
            contentType("application/json")
            header("Idempotency-Key", "claim-foreign")
            body(CLAIM_JSON)
        } When {
            post("/customer/v1/incentives/claims")
        } Then {
            statusCode(400)
        }

        assertThat(StubUpstreamResource.requests(INCENTIVE_PATH)).isEmpty()
    }

    @Test
    fun `invalid public bounds fail before upstream calls`() {
        Given {
            contentType("application/json")
            header("Idempotency-Key", "x".repeat(256))
            body(CLAIM_JSON)
        } When {
            post("/customer/v1/incentives/claims")
        } Then {
            statusCode(400)
            body(containsString("Idempotency-Key"))
        }

        Given {
            contentType("application/json")
            header("Idempotency-Key", "claim-numeric-code")
            body("""{"interactionRef":"$INTERACTION_ID","code":12345678,"productId":"$PRODUCT_ID"}""")
        } When {
            post("/customer/v1/incentives/claims")
        } Then {
            statusCode(400)
            body(containsString("code must be a string"))
        }

        assertThat(StubUpstreamResource.requests(PRODUCT_PATH)).isEmpty()
    }

    @Test
    fun `authoritative account opening commits the claimed reservation over real http`() {
        StubUpstreamResource.stub(PRODUCT_PATH, body = PRODUCT_JSON)
        StubUpstreamResource.stub(
            PARTY_PATH,
            body = """{"status":"ACTIVE","legalName":"Ada Customer"}""",
        )
        StubUpstreamResource.stub(
            ACCOUNT_PATH,
            status = 201,
            body = """{"id":"77777777-7777-4777-8777-777777777777","openedAt":"$OPENED_AT"}""",
        )
        StubUpstreamResource.stub(
            COMMIT_PATH,
            body = RESERVATION_JSON.replace("RESERVED", "COMMITTED"),
        )

        Given {
            contentType("application/json")
            header("Idempotency-Key", "open-term-with-reward")
            body("""{"productId":"$PRODUCT_ID","incentiveReservationId":"$RESERVATION_ID"}""")
        } When {
            post("/customer/v1/term-deposits")
        } Then {
            statusCode(201)
            body("incentiveReservation.status", org.hamcrest.Matchers.equalTo("COMMITTED"))
        }

        val commit = ObjectMapper().readTree(StubUpstreamResource.requests(COMMIT_PATH).single().body)
        assertThat(commit.path("productRef").asText()).isEqualTo(PRODUCT_ID)
        assertThat(commit.path("qualifiedAt").asText()).isEqualTo(OPENED_AT)
    }

    private fun stubProductAndAttribution() {
        StubUpstreamResource.stub(PRODUCT_PATH, body = PRODUCT_JSON)
        StubUpstreamResource.stub(ATTRIBUTION_PATH, body = ATTRIBUTION_JSON)
    }
}

@QuarkusTest
class CustomerIncentiveClaimUnauthenticatedIT {
    @Test
    fun `claim route rejects an unauthenticated caller`() {
        Given {
            contentType("application/json")
            header("Idempotency-Key", "claim-unauthenticated")
            body(CLAIM_JSON)
        } When {
            post("/customer/v1/incentives/claims")
        } Then {
            statusCode(401)
        }
    }
}

private const val CLAIM_PARTY_ID = "11111111-1111-4111-8111-111111111111"
private const val INTERACTION_ID = "22222222-2222-4222-8222-222222222222"
private const val PRODUCT_ID = "33333333-3333-4333-8333-333333333333"
private const val OFFER_ID = "44444444-4444-4444-8444-444444444444"
private const val RESERVATION_ID = "55555555-5555-4555-8555-555555555555"
private const val PRODUCT_PATH = "/api/v1/products/$PRODUCT_ID"
private const val ATTRIBUTION_PATH = "/api/v1/campaigns/interactions/$INTERACTION_ID/attribution"
private const val INCENTIVE_PATH = "/api/v1/customer-incentives/offers/$OFFER_ID/reservations"
private const val PARTY_PATH = "/api/v1/parties/$CLAIM_PARTY_ID"
private const val ACCOUNT_PATH = "/api/v1/accounts"
private const val COMMIT_PATH = "/api/v1/customer-incentives/reservations/$RESERVATION_ID/commit"
private const val OPENED_AT = "2026-08-27T03:00:00Z"
private const val CLAIM_JSON =
    """{"interactionRef":"$INTERACTION_ID","code":"WELCOME10","productId":"$PRODUCT_ID"}"""
private const val PRODUCT_JSON =
    """{"id":"$PRODUCT_ID","code":"TD-12M","name":"12 month deposit","type":"TERM_DEPOSIT","status":"ACTIVE","isPublic":true,"currency":"CZK","termDepositConfig":{"termMonths":12,"interestRateAnnual":3.5},"termsAndConditions":[]}"""
private const val ATTRIBUTION_JSON =
    """{"campaignId":"66666666-6666-4666-8666-666666666666","stepOrder":0,"channel":"PUSH","incentiveOfferRef":{"id":"$OFFER_ID","name":"WELCOME","version":1}}"""
private const val RESERVATION_JSON =
    """{"id":"$RESERVATION_ID","offerRef":{"id":"$OFFER_ID","name":"WELCOME","version":1},"productRef":"$PRODUCT_ID","attributionRef":"$INTERACTION_ID","reservedAt":"2026-08-27T00:00:00Z","expiresAt":"2026-08-27T00:15:00Z","status":"RESERVED"}"""
