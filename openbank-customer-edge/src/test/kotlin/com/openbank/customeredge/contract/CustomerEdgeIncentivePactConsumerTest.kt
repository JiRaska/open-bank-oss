// SPDX-License-Identifier: Apache-2.0
package com.openbank.customeredge.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.customeredge.infrastructure.rest.CustomerEdgeResource
import com.openbank.customeredge.infrastructure.rest.PartyMergeResolver
import com.openbank.customeredge.infrastructure.rest.PaymentSessionStore
import com.openbank.customeredge.infrastructure.rest.UpstreamClient
import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.jwt.JsonWebToken
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.net.InetSocketAddress
import java.time.Clock

/** Consumer proof for the authenticated edge -> Incentive reservation contract. */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-incentive-service", pactVersion = PactSpecVersion.V3)
class CustomerEdgeIncentivePactConsumerTest {
    private lateinit var trustedStub: HttpServer
    private var accountStatus = 201
    private var accountBody = ACCOUNT_OPENED

    @BeforeEach
    fun startTrustedStub() {
        trustedStub = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/protocol/openid-connect/token") { exchange ->
                respond(exchange, 200, """{"access_token":"pact-token","expires_in":300}""")
            }
            createContext("/api/v1/products/$PRODUCT_ID") { exchange ->
                respond(exchange, 200, TERM_DEPOSIT_PRODUCT)
            }
            createContext("/api/v1/campaigns/interactions/$INTERACTION_REF/attribution") { exchange ->
                respond(exchange, 200, ATTRIBUTION)
            }
            createContext("/api/v1/parties/$PARTY_ID") { exchange ->
                respond(exchange, 200, """{"status":"ACTIVE","legalName":"Ada Customer"}""")
            }
            createContext("/api/v1/accounts") { exchange ->
                respond(exchange, accountStatus, accountBody)
            }
            start()
        }
    }

    @AfterEach
    fun stopTrustedStub() = trustedStub.stop(0)

    @Pact(consumer = "openbank-customer-edge", provider = "openbank-incentive-service")
    fun reserveAttributedIncentive(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("a published offer contains the pact promo code for the pact product")
        .uponReceiving("POST an attributed customer reservation with trusted party identity")
        .path("/api/v1/customer-incentives/offers/$OFFER_ID/reservations")
        .method("POST")
        .headers(
            mapOf(
                "Content-Type" to "application/json",
                "X-Customer-Party-Id" to PARTY_ID,
                "Idempotency-Key" to IDEMPOTENCY_KEY,
            ),
        )
        .body(
            newJsonBody { body ->
                body.stringValue("code", "WELCOME10")
                body.stringValue("productRef", PRODUCT_ID)
                body.stringValue("attributionRef", INTERACTION_REF)
            }.build(),
        )
        .willRespondWith()
        .status(201)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { body ->
                body.uuid("id", java.util.UUID.fromString(RESERVATION_ID))
                body.`object`("offerRef") { ref ->
                    ref.uuid("id", java.util.UUID.fromString(OFFER_ID))
                    ref.stringValue("name", "WELCOME")
                    ref.integerType("version", 1)
                }
                body.stringValue("productRef", PRODUCT_ID)
                body.uuid("attributionRef", java.util.UUID.fromString(INTERACTION_REF))
                body.stringType("reservedAt", "2026-08-27T00:00:00Z")
                body.stringType("expiresAt", "2026-08-27T00:15:00Z")
                body.stringValue("status", "RESERVED")
            }.build(),
        )
        .toPact()

    @Pact(consumer = "openbank-customer-edge", provider = "openbank-incentive-service")
    fun commitAttributedIncentive(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("an attributed reservation is reserved for qualifying commit")
        .uponReceiving("POST a trusted qualifying account outcome")
        .path("/api/v1/customer-incentives/reservations/$RESERVATION_ID/commit")
        .method("POST")
        .headers(
            mapOf(
                "Content-Type" to "application/json",
                "X-Customer-Party-Id" to PARTY_ID,
                "Idempotency-Key" to OPEN_IDEMPOTENCY_KEY,
            ),
        )
        .body(
            newJsonBody { body ->
                body.stringValue("productRef", PRODUCT_ID)
                body.stringValue("qualifiedAt", QUALIFIED_AT)
            }.build(),
        )
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(customerReservationBody("COMMITTED"))
        .toPact()

    @Pact(consumer = "openbank-customer-edge", provider = "openbank-incentive-service")
    fun releaseAttributedIncentive(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("an attributed reservation is reserved for deterministic release")
        .uponReceiving("POST a trusted deterministic qualifying rejection")
        .path("/api/v1/customer-incentives/reservations/$RESERVATION_ID/release")
        .method("POST")
        .headers(
            mapOf(
                "Content-Type" to "application/json",
                "X-Customer-Party-Id" to PARTY_ID,
                "Idempotency-Key" to OPEN_IDEMPOTENCY_KEY,
            ),
        )
        .body(newJsonBody { body -> body.stringValue("productRef", PRODUCT_ID) }.build())
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(customerReservationBody("RELEASED"))
        .toPact()

    @Test
    @PactTestFor(pactMethod = "reserveAttributedIncentive")
    fun `real edge client reserves against the provider contract`(mockServer: MockServer) {
        val resource = resource(mockServer)

        val response = resource.claimIncentive(
            """{"interactionRef":"$INTERACTION_REF","code":"WELCOME10","productId":"$PRODUCT_ID"}""",
            IDEMPOTENCY_KEY,
        )

        assertThat(response.status).isEqualTo(201)
        assertThat(ObjectMapper().readTree(response.entity.toString()).path("status").asText()).isEqualTo("RESERVED")
    }

    @Test
    @PactTestFor(pactMethod = "commitAttributedIncentive")
    fun `real qualifying account flow commits against the provider contract`(mockServer: MockServer) {
        accountStatus = 201
        accountBody = ACCOUNT_OPENED
        val response = resource(mockServer).openTermDeposit(
            """{"productId":"$PRODUCT_ID","incentiveReservationId":"$RESERVATION_ID"}""",
            OPEN_IDEMPOTENCY_KEY,
        )

        assertThat(response.status).isEqualTo(201)
        assertThat(
            ObjectMapper().readTree(response.entity.toString()).path("incentiveReservation").path("status").asText(),
        ).isEqualTo("COMMITTED")
    }

    @Test
    @PactTestFor(pactMethod = "releaseAttributedIncentive")
    fun `real deterministic rejection releases against the provider contract`(mockServer: MockServer) {
        accountStatus = 422
        accountBody = """{"error":"qualifying action rejected"}"""
        val response = resource(mockServer).openTermDeposit(
            """{"productId":"$PRODUCT_ID","incentiveReservationId":"$RESERVATION_ID"}""",
            OPEN_IDEMPOTENCY_KEY,
        )

        assertThat(response.status).isEqualTo(422)
    }

    private fun resource(mockServer: MockServer): CustomerEdgeResource {
        val base = "http://127.0.0.1:${trustedStub.address.port}"
        val upstream = UpstreamClient().apply {
            tokenEndpointBase = base
            clientId = "openbank-customer-edge"
            clientSecret = "pact"
            tlsTrustCertificateFile = java.util.Optional.empty()
            allowedHostSuffixes = ".svc,127.0.0.1,localhost"
        }
        return CustomerEdgeResource(
            upstream,
            mockk(relaxed = true),
            PaymentSessionStore(),
            mockk(relaxed = true),
            mockk(relaxed = true),
            Clock.systemUTC(),
        ).apply {
            objectMapper = ObjectMapper()
            jwt = mockk<JsonWebToken> {
                every { getClaim<String>("party_id") } returns PARTY_ID
                every { subject } returns PARTY_ID
            }
            partyMergeResolver = mockk<PartyMergeResolver> {
                every { resolve(any()) } answers { firstArg() }
            }
            productCatalogUrl = base
            partyServiceUrl = base
            accountServiceUrl = base
            campaignServiceUrl = base
            incentiveServiceUrl = mockServer.getUrl()
        }
    }

    private companion object {
        const val PARTY_ID = "11111111-1111-4111-8111-111111111111"
        const val INTERACTION_REF = "22222222-2222-4222-8222-222222222222"
        const val PRODUCT_ID = "33333333-3333-4333-8333-333333333333"
        const val OFFER_ID = "44444444-4444-4444-8444-444444444444"
        const val RESERVATION_ID = "55555555-5555-4555-8555-555555555555"
        const val IDEMPOTENCY_KEY = "claim-pact-once"
        const val OPEN_IDEMPOTENCY_KEY = "open-pact-once"
        const val QUALIFIED_AT = "2026-08-27T03:00:00Z"
        const val ACCOUNT_OPENED =
            """{"id":"77777777-7777-4777-8777-777777777777","openedAt":"$QUALIFIED_AT"}"""
        val TERM_DEPOSIT_PRODUCT = """
            {"id":"$PRODUCT_ID","code":"TERM_6M","name":"Term deposit","type":"TERM_DEPOSIT",
            "currency":"CZK","status":"ACTIVE","isPublic":true,
            "termDepositConfig":{"termMonths":6,"interestRateAnnual":5.8},"termsAndConditions":[]}
        """.trimIndent()
        val ATTRIBUTION = """
            {"campaignId":"66666666-6666-4666-8666-666666666666","stepOrder":0,"channel":"PUSH",
            "incentiveOfferRef":{"id":"$OFFER_ID","name":"WELCOME","version":1}}
        """.trimIndent()

        fun customerReservationBody(status: String) = newJsonBody { body ->
            body.uuid("id", java.util.UUID.fromString(RESERVATION_ID))
            body.`object`("offerRef") { ref ->
                ref.uuid("id", java.util.UUID.fromString(OFFER_ID))
                ref.stringValue("name", "WELCOME")
                ref.integerType("version", 1)
            }
            body.stringValue("productRef", PRODUCT_ID)
            body.uuid("attributionRef", java.util.UUID.fromString(INTERACTION_REF))
            body.stringType("reservedAt", "2026-08-27T00:00:00Z")
            body.stringType("expiresAt", "2026-08-28T00:00:00Z")
            body.stringValue("status", status)
        }.build()

        fun respond(exchange: com.sun.net.httpserver.HttpExchange, status: Int, body: String) {
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }
}
