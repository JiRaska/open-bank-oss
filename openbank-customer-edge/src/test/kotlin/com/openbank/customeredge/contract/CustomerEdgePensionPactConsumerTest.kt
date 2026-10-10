// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonArrayMinLike
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.LambdaDslObject
import au.com.dius.pact.consumer.dsl.PactDslRequestWithoutPath
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.dsl.PactDslWithState
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.openbank.customeredge.infrastructure.rest.CustomerPartyResolver
import com.openbank.customeredge.infrastructure.rest.CustomerPensionChangeResource
import com.openbank.customeredge.infrastructure.rest.CustomerPensionResource
import com.openbank.customeredge.infrastructure.rest.UpstreamClient
import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.net.InetSocketAddress
import java.util.Optional
import java.util.UUID

/**
 * Consumer contract for the calls customer-edge makes to pension-service (ADR-0334 S6) against
 * its API 1.2.0 (the final integration). The real [CustomerPensionResource] and [UpstreamClient] drive
 * every interaction, and every expected path is a LITERAL. Every POST records the
 * `Idempotency-Key` pension-service requires, and the application records the residency the edge
 * derives from the party record (party-service is stubbed here; it is a separate provider).
 *
 * By-id routes are two interactions, because the edge reads the contract with the party header to
 * prove ownership before acting. The 404 for an unknown contract is what that guard relies on, and
 * the 401 records that pension-service refuses a caller with no M2M identity (ADR-0279).
 *
 * Provider replay: `PensionPactProviderVerificationTest` and
 * `PensionNegativeAuthProviderVerificationTest` in openbank-pension-service (`@PactFolder`, every PR).
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-pension-service", pactVersion = PactSpecVersion.V3)
class CustomerEdgePensionPactConsumerTest {
    private lateinit var tokenStub: HttpServer

    @BeforeEach
    fun startTokenStub() {
        tokenStub = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/protocol/openid-connect/token") { exchange ->
                val bytes = """{"access_token":"pact-token","expires_in":300}""".toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            // sca-service is a separate provider; here it approves every consume so the pension
            // interactions are what the pact records.
            createContext("/api/v1/sca/challenges/") { exchange ->
                val bytes = """{"status":"COMPLETED"}""".toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            // party-service is a separate provider: the record the application's eligibility facts
            // are derived from.
            createContext("/api/v1/parties/") { exchange ->
                val bytes = """{"id":"$PARTY_ID","dateOfBirth":"1985-05-05","address":{"countryCode":"CZ"}}"""
                    .toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
    }

    @AfterEach
    fun stopTokenStub() = tokenStub.stop(0)

    @Pact(consumer = "openbank-customer-edge", provider = "openbank-pension-service")
    fun startApplication(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(NO_CONTRACT_STATE)
        .uponReceiving("POST an onboarding application with the party's birth date and residency")
        .path("/api/v2/pension/onboarding/applications")
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json", "X-Customer-Party-Id" to PARTY_ID))
        .matchHeader("Idempotency-Key", ".+", "pact-key")
        .body(
            newJsonBody { b ->
                b.stringValue("productLine", "DPS")
                b.stringValue("jurisdiction", "CZ")
                b.stringValue("providerEntityId", PROVIDER_ID)
                b.stringValue("providerType", "PENSION_COMPANY")
                b.`object`("schedule") { s ->
                    s.numberValue("amount", 1000)
                    s.stringValue("currency", "CZK")
                    s.stringValue("frequency", "MONTHLY")
                    s.nullValue("employerAmount")
                }
                b.stringValue("birthDate", "1985-05-05")
                b.stringValue("residencyCountry", "CZ")
            }.build(),
        )
        .willRespondWith()
        .status(201)
        .body(
            newJsonBody { a ->
                a.uuid("applicationId")
                a.stringType("kind", "NEW_CONTRACT")
                a.stringType("status", "STARTED")
                a.stringMatcher("productLine", "DPS|DIP", "DPS")
            }.build(),
        )
        .toPact()

    @Pact(consumer = "openbank-customer-edge", provider = "openbank-pension-service")
    fun listContracts(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(ACTIVE_STATE)
        .uponReceiving("GET the customer party's own pension contracts")
        .path("/api/v2/pension/contracts")
        .method("GET")
        .headers(mapOf("X-Customer-Party-Id" to PARTY_ID))
        .willRespondWith()
        .status(200)
        .body(newJsonArrayMinLike(1) { a -> a.`object` { c -> contract(c, "ACTIVE") } }.build())
        .toPact()

    /** The overview: ownership read, then the participant valuation pension-service serves. */
    @Pact(consumer = "openbank-customer-edge", provider = "openbank-pension-service")
    fun readContract(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(UNITS_STATE).readById("ACTIVE")
        .given(UNITS_STATE)
        .uponReceiving("GET the participant valuation of the contract")
        .path("/api/v2/pension/contracts/$CONTRACT_ID/valuation")
        .method("GET")
        .headers(mapOf("X-Customer-Party-Id" to PARTY_ID))
        .willRespondWith()
        .status(200)
        .body(
            newJsonBody { v ->
                v.stringValue("contractId", CONTRACT_ID)
                v.stringMatcher("status", "VALUED|NO_HOLDINGS|NAV_NOT_PUBLISHED|CURRENCY_MISMATCH", "VALUED")
                v.stringType("currency", "CZK")
                v.numberType("totalValue", 125)
                v.date("asOf", "yyyy-MM-dd")
                v.minArrayLike("holdings", 1) { h ->
                    h.uuid("fundId")
                    h.numberType("units", 100)
                    h.stringMatcher("navStatus", "PUBLISHED|NOT_PUBLISHED", "PUBLISHED")
                    h.numberType("navPerUnit", 1.25)
                    h.numberType("value", 125)
                    h.stringType("currency", "CZK")
                }
                v.array("pendingOrders") { }
            }.build(),
        )
        .toPact()

    /** Unit transactions: ownership read, then pension-service's priced transaction page. */
    @Pact(consumer = "openbank-customer-edge", provider = "openbank-pension-service")
    fun readTransactions(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(UNITS_STATE).readById("ACTIVE")
        .given(UNITS_STATE)
        .uponReceiving("GET the contract's unit transactions")
        .path("/api/v2/pension/contracts/$CONTRACT_ID/transactions")
        .query("page=0&size=20")
        .method("GET")
        .headers(mapOf("X-Customer-Party-Id" to PARTY_ID))
        .willRespondWith()
        .status(200)
        .body(
            newJsonBody { p ->
                p.minArrayLike("items", 1) { t ->
                    t.uuid("id")
                    t.uuid("fundId")
                    t.stringMatcher("type", "SUBSCRIBE|REDEEM|SWITCH_OUT|SWITCH_IN|FEE", "SUBSCRIBE")
                    t.numberType("units", 10)
                    t.numberType("amount", 12.5)
                    t.numberType("navPerUnit", 1.25)
                    t.stringType("pricedAt", "2026-09-01T16:00:00Z")
                }
                p.integerType("page", 0)
                p.integerType("size", 20)
                p.integerType("total", 1)
            }.build(),
        )
        .toPact()

    /**
     * A strategy change is document-bound in API 1.2.0: the edge forwards the challenge (never
     * spends it) with the effective date it pinned. With no assessment on file pension-service's
     * suitability gate refuses anything but the most conservative strategy before any SCA check —
     * the edge must surface that code, not a generic refusal.
     */
    @Pact(consumer = "openbank-customer-edge", provider = "openbank-pension-service")
    fun electStrategy(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(ACTIVE_STATE).readById("ACTIVE")
        .given(ACTIVE_STATE)
        .uponReceiving("PUT a signed strategy change on a contract with no assessment on file")
        .path("/api/v2/pension/contracts/$CONTRACT_ID/strategy")
        .method("PUT")
        .headers(mapOf("Content-Type" to "application/json", "X-Customer-Party-Id" to PARTY_ID))
        .matchHeader("Idempotency-Key", ".+", "pact-key")
        .body(
            newJsonBody { b ->
                b.stringValue("strategyCode", "DYNAMIC")
                b.stringValue("effectiveFrom", FUTURE_DATE)
                b.array("acknowledgedWarnings") { }
                b.nullValue("language")
                b.stringValue("scaChallengeId", CHALLENGE_ID)
            }.build(),
        )
        .willRespondWith()
        .status(STRATEGY_REFUSAL)
        .body(newJsonBody { e -> e.stringValue("code", "STRATEGY_NOT_PERMITTED") }.build())
        .toPact()

    @Pact(consumer = "openbank-customer-edge", provider = "openbank-pension-service")
    fun readSchedule(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(ACTIVE_STATE).readById("ACTIVE")
        .given(ACTIVE_STATE)
        .uponReceiving("GET the contribution schedule of the contract")
        .path("/api/v2/pension/contracts/$CONTRACT_ID/contribution-schedule")
        .method("GET")
        .headers(mapOf("X-Customer-Party-Id" to PARTY_ID))
        .willRespondWith()
        .status(200)
        .toPact()

    @Pact(consumer = "openbank-customer-edge", provider = "openbank-pension-service")
    fun previewSchedule(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(ACTIVE_STATE).readById("ACTIVE")
        .given(ACTIVE_STATE)
        .uponReceiving("POST a contribution schedule preview, which issues the document SCA signs")
        .path("/api/v2/pension/contracts/$CONTRACT_ID/contribution-schedule/preview")
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json", "X-Customer-Party-Id" to PARTY_ID))
        .matchHeader("Idempotency-Key", ".+", "pact-key")
        .body(
            newJsonBody { b ->
                b.numberValue("amount", 1500)
                b.stringValue("frequency", "MONTHLY")
                b.numberValue("dayOfMonth", 15)
                b.nullValue("startDate")
                b.booleanValue("acknowledgeIncentiveReduction", false)
            }.build(),
        )
        .willRespondWith()
        .status(200)
        .body(newJsonBody { v -> v.stringMatcher("documentSha256", "[0-9a-fA-F]{64}", "a".repeat(64)) }.build())
        .toPact()

    @Pact(consumer = "openbank-customer-edge", provider = "openbank-pension-service")
    fun readBeneficiaries(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(ACTIVE_STATE).readById("ACTIVE")
        .given(ACTIVE_STATE)
        .uponReceiving("GET the beneficiary designation of the contract")
        .path("/api/v2/pension/contracts/$CONTRACT_ID/beneficiaries")
        .method("GET")
        .headers(mapOf("X-Customer-Party-Id" to PARTY_ID))
        .willRespondWith()
        .status(200)
        .body(newJsonBody { v -> v.array("current") { } }.build())
        .toPact()

    @Pact(consumer = "openbank-customer-edge", provider = "openbank-pension-service")
    fun suspend(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(ACTIVE_STATE).readById("ACTIVE")
        .given(ACTIVE_STATE)
        .uponReceiving("POST suspend contributions on the contract")
        .path("/api/v2/pension/contracts/$CONTRACT_ID/suspend")
        .method("POST")
        .headers(mapOf("X-Customer-Party-Id" to PARTY_ID))
        .matchHeader("Idempotency-Key", ".+", "pact-key")
        .willRespondWith()
        .status(200)
        .body(newJsonBody { c -> contract(c, "SUSPENDED") }.build())
        .toPact()

    @Pact(consumer = "openbank-customer-edge", provider = "openbank-pension-service")
    fun unknownContract(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(NO_CONTRACT_STATE)
        .uponReceiving("GET a pension contract the customer party does not hold")
        .path("/api/v2/pension/contracts/$UNKNOWN_ID")
        .method("GET")
        .headers(mapOf("X-Customer-Party-Id" to PARTY_ID))
        .willRespondWith()
        .status(404)
        .toPact()

    @Pact(consumer = "openbank-customer-edge", provider = "openbank-pension-service")
    fun missingIdentity(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(NEGATIVE_AUTH_STATE)
        .uponReceiving("GET a pension contract with no M2M identity")
        .path("/api/v2/pension/contracts/$CONTRACT_ID")
        .method("GET")
        .headers(mapOf("X-Customer-Party-Id" to PARTY_ID))
        .willRespondWith()
        .status(401)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "startApplication")
    fun `opening an application matches the provider`(mockServer: MockServer) {
        val response = resource(mockServer).startApplication(
            """{"productLine":"DPS","jurisdiction":"CZ","providerEntityId":"$PROVIDER_ID",
               "providerType":"PENSION_COMPANY","birthDate":"2010-01-01","residencyCountry":"SK",
               "schedule":{"amount":"1000","currency":"CZK","frequency":"MONTHLY"}}""",
            "pact-key",
        )

        assertThat(response.status).isEqualTo(201)
    }

    @Test
    @PactTestFor(pactMethod = "listContracts")
    fun `listing own contracts matches the provider`(mockServer: MockServer) {
        val response = resource(mockServer).contracts()

        assertThat(response.status).isEqualTo(200)
        assertThat(response.entity as String).contains(CONTRACT_ID)
    }

    @Test
    @PactTestFor(pactMethod = "readContract")
    fun `reading an owned contract matches the provider`(mockServer: MockServer) {
        val response = resource(mockServer).contract(CONTRACT_ID)
        assertThat(response.status).isEqualTo(200)
        assertThat(response.entity as String).contains("\"valuation\":{").contains("\"status\":\"VALUED\"")
    }

    @Test
    @PactTestFor(pactMethod = "readTransactions")
    fun `reading the unit transactions matches the provider`(mockServer: MockServer) {
        val response = resource(mockServer).transactions(CONTRACT_ID, 0, 20)
        assertThat(response.status).isEqualTo(200)
        assertThat(response.entity as String).contains("\"type\":\"SUBSCRIBE\"")
    }

    @Test
    @PactTestFor(pactMethod = "electStrategy")
    fun `a signed strategy change matches the provider`(mockServer: MockServer) {
        val response = resource(mockServer).strategy(
            CONTRACT_ID,
            """{"strategyCode":"DYNAMIC","effectiveFrom":"$FUTURE_DATE"}""",
            "pact-key",
            CHALLENGE_ID,
        )

        assertThat(response.status).isEqualTo(STRATEGY_REFUSAL)
        assertThat(response.entity as String).contains("STRATEGY_NOT_PERMITTED")
    }

    @Test
    @PactTestFor(pactMethod = "readSchedule")
    fun `reading the contribution schedule matches the provider`(mockServer: MockServer) {
        assertThat(changes(mockServer).schedule(CONTRACT_ID).status).isEqualTo(200)
    }

    @Test
    @PactTestFor(pactMethod = "previewSchedule")
    fun `a schedule preview matches the provider`(mockServer: MockServer) {
        val response = changes(mockServer).previewSchedule(
            CONTRACT_ID,
            """{"amount":"1500","frequency":"MONTHLY","dayOfMonth":15}""",
            "pact-key",
        )

        assertThat(response.status).isEqualTo(200)
    }

    @Test
    @PactTestFor(pactMethod = "readBeneficiaries")
    fun `reading the beneficiaries matches the provider`(mockServer: MockServer) {
        assertThat(changes(mockServer).beneficiaries(CONTRACT_ID).status).isEqualTo(200)
    }

    @Test
    @PactTestFor(pactMethod = "suspend")
    fun `pausing contributions matches the provider`(mockServer: MockServer) {
        assertThat(resource(mockServer).pause(CONTRACT_ID, "pact-key", CHALLENGE_ID).status).isEqualTo(200)
    }

    @Test
    @PactTestFor(pactMethod = "unknownContract")
    fun `an unknown contract is NOT_FOUND at the provider and at the edge`(mockServer: MockServer) {
        assertThat(resource(mockServer).contract(UNKNOWN_ID).status).isEqualTo(404)
    }

    @Test
    @PactTestFor(pactMethod = "missingIdentity")
    fun `the provider answers 401 UNAUTHORIZED to a caller with no identity`(mockServer: MockServer) {
        val connection = java.net.URI("${mockServer.getUrl()}/api/v2/pension/contracts/$CONTRACT_ID").toURL()
            .openConnection() as java.net.HttpURLConnection
        connection.setRequestProperty("X-Customer-Party-Id", PARTY_ID)

        assertThat(connection.responseCode).isEqualTo(401)
    }

    private fun resource(mockServer: MockServer): CustomerPensionResource {
        val upstream = UpstreamClient().apply {
            tokenEndpointBase = "http://127.0.0.1:${tokenStub.address.port}"
            clientId = "openbank-customer-edge"
            clientSecret = "pact"
            tlsTrustCertificateFile = Optional.empty()
        }
        val parties = mockk<CustomerPartyResolver> { every { resolve(null) } returns UUID.fromString(PARTY_ID) }
        return CustomerPensionResource(upstream, parties).apply {
            pensionServiceUrl = mockServer.getUrl()
            catalogUrl = "http://127.0.0.1:9"
            scaServiceUrl = "http://127.0.0.1:${tokenStub.address.port}"
            partyServiceUrl = "http://127.0.0.1:${tokenStub.address.port}"
        }
    }

    private fun changes(mockServer: MockServer): CustomerPensionChangeResource {
        val upstream = UpstreamClient().apply {
            tokenEndpointBase = "http://127.0.0.1:${tokenStub.address.port}"
            clientId = "openbank-customer-edge"
            clientSecret = "pact"
            tlsTrustCertificateFile = Optional.empty()
        }
        val parties = mockk<CustomerPartyResolver> { every { resolve(null) } returns UUID.fromString(PARTY_ID) }
        return CustomerPensionChangeResource(upstream, parties).apply { pensionServiceUrl = mockServer.getUrl() }
    }

    private fun PactDslWithState.readById(status: String) =
        uponReceiving("GET the pension contract to prove the customer holds it").readById(status)

    private fun PactDslRequestWithoutPath.readById(status: String) = path("/api/v2/pension/contracts/$CONTRACT_ID")
        .method("GET")
        .headers(mapOf("X-Customer-Party-Id" to PARTY_ID))
        .willRespondWith()
        .status(200)
        .body(newJsonBody { c -> contract(c, status) }.build())

    private companion object {
        const val PARTY_ID = "11111111-1111-4111-8111-111111111111"
        const val CONTRACT_ID = "44444444-4444-4444-8444-444444444444"
        // pension-service refuses any provider but its configured legal entity; this is the
        // synthetic fixture identity its test, dev and sandbox profiles share (application.yaml).
        const val PROVIDER_ID = "00000000-0000-4000-8000-000000000001"
        const val UNKNOWN_ID = "99999999-9999-4999-8999-999999999999"
        const val CHALLENGE_ID = "77777777-7777-4777-8777-777777777777"
        const val FUTURE_DATE = "2099-01-01"

        /** pension-service's answer to a non-conservative strategy with no assessment on file. */
        const val STRATEGY_REFUSAL = 403
        const val ACTIVE_STATE = "the customer party holds an active pension contract"
        const val UNITS_STATE = "the customer party's active pension contract holds priced fund units"
        const val NO_CONTRACT_STATE = "the customer party holds no pension contract"
        const val NEGATIVE_AUTH_STATE = "no valid M2M identity is presented"
        const val STATUSES =
            "DRAFT|PENDING_ACTIVATION|ACTIVE|SUSPENDED|TERMINATING|PAID_OUT|TRANSFERRED_OUT|CLOSED"

        /** The fields the edge reads. `participantPartyId` is what the ownership guard compares. */
        fun contract(c: LambdaDslObject, status: String, anyId: Boolean = false) {
            if (anyId) c.uuid("contractId") else c.uuid("contractId", UUID.fromString(CONTRACT_ID))
            c.uuid("participantPartyId", UUID.fromString(PARTY_ID))
            c.stringMatcher("productLine", "DPS|DIP", "DPS")
            c.stringType("jurisdiction", "CZ")
            c.integerType("packVersion", 1)
            c.stringType("providerType", "PENSION_COMPANY")
            c.stringMatcher("status", STATUSES, status)
            c.`object`("schedule") { s ->
                s.numberType("amount", 1000)
                s.stringMatcher("currency", "[A-Z]{3}", "CZK")
                s.stringType("frequency", "MONTHLY")
            }
            c.stringType("createdAt", "2026-10-01T10:00:00Z")
            c.stringType("updatedAt", "2026-10-01T10:00:00Z")
        }
    }
}
