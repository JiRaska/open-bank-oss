// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonArrayMinLike
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.pension.infrastructure.fund.ContractValuationDto
import com.openbank.pension.infrastructure.fund.FundRegister
import com.openbank.pension.infrastructure.fund.OrderRequestDto
import com.openbank.pension.infrastructure.fund.PensionFundOrders
import com.openbank.pension.infrastructure.fund.PensionFundRestClient
import com.openbank.pension.infrastructure.fund.StrategyDto
import com.openbank.pension.infrastructure.fund.UnitOrderDto
import com.openbank.pension.infrastructure.fund.UnitTransactionDto
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.time.Clock
import java.util.UUID

/**
 * Consumer contract: pension-service -> pension-fund-service, EVERY call FundAdministrationPort
 * makes through [PensionFundRestClient] (ADR-0334, #12350): holdings (priced, and a fund with NO
 * published NAV), unit transactions, strategies, SUBSCRIBE and REDEEM order placement, plus a
 * recorded 401 for each verb under [NEGATIVE_AUTH_STATE].
 *
 * Replayed by pension-fund-service's `PensionFundPactFolderProviderVerificationTest` (states are
 * seeded there) and `PensionFundNegativeAuthProviderVerificationTest` (no identity). The responses
 * are read back through the adapter's own decoding ([PensionFundOrders]), so a renamed field in the
 * client DTO reddens THIS test, while a wrong path reddens only the provider replay — which is why
 * each expected path below is a LITERAL and only the outgoing request is reflected off `@Path`.
 *
 * One class for the whole pair: `pact.writer.overwrite=true` makes each class rewrite the file.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-pension-fund-service", pactVersion = PactSpecVersion.V3)
class PensionFundPactConsumerTest {

    private val mapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    // --- holdings ---------------------------------------------------------------------------

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun pricedHoldingsPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(PRICED_STATE)
        .uponReceiving("GET the unit holdings of a pension contract priced at a published NAV")
        .path("/api/v1/contracts/$PRICED_CONTRACT/holdings")
        .method("GET")
        .willRespondWith()
        .status(200)
        .matchHeader("Content-Type", "application/json.*", "application/json")
        .body(
            newJsonBody { o ->
                o.uuid("contractId", UUID.fromString(PRICED_CONTRACT))
                o.minArrayLike("holdings", 1) { h ->
                    h.uuid("fundId", UUID.fromString(PRICED_FUND))
                    h.numberType("units", BigDecimal("100.000000"))
                    h.numberType("navPerUnit", BigDecimal("1.250000"))
                    h.date("navDate", "yyyy-MM-dd")
                    h.numberType("value", BigDecimal("125.00"))
                    h.stringValue("currency", "CZK")
                }
                o.minArrayLike("pendingOrders", 1) { p ->
                    p.uuid("id")
                    p.uuid("fundId", UUID.fromString(PRICED_FUND))
                    p.stringMatcher("type", "SUBSCRIBE|REDEEM|SWITCH_OUT|SWITCH_IN", "SUBSCRIBE")
                    p.stringValue("status", "PENDING")
                    p.stringType("placedAt", "2026-10-01T08:00:00Z")
                }
            }.build(),
        )
        .toPact()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun unpublishedNavHoldingsPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(UNPUBLISHED_STATE)
        .uponReceiving("GET the unit holdings of a pension contract whose fund has no published NAV yet")
        .path("/api/v1/contracts/$UNPUBLISHED_CONTRACT/holdings")
        .method("GET")
        .willRespondWith()
        .status(200)
        .matchHeader("Content-Type", "application/json.*", "application/json")
        .body(
            newJsonBody { o ->
                o.uuid("contractId", UUID.fromString(UNPUBLISHED_CONTRACT))
                o.minArrayLike("holdings", 1) { h ->
                    h.uuid("fundId", UUID.fromString(UNPUBLISHED_FUND))
                    h.numberType("units", BigDecimal("40.000000"))
                    h.nullValue("navPerUnit")
                    h.nullValue("navDate")
                    h.nullValue("value")
                    h.stringValue("currency", "CZK")
                }
            }.build(),
        )
        .toPact()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun holdingsWithoutIdentityPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(NEGATIVE_AUTH_STATE)
        .uponReceiving("GET pension contract holdings with no M2M identity")
        .path("/api/v1/contracts/$PRICED_CONTRACT/holdings")
        .method("GET")
        .willRespondWith()
        .status(401)
        .toPact()

    // --- transactions -----------------------------------------------------------------------

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun transactionsPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(PRICED_STATE)
        .uponReceiving("GET the priced unit transactions of a pension contract")
        .path("/api/v1/contracts/$PRICED_CONTRACT/transactions")
        .method("GET")
        .willRespondWith()
        .status(200)
        .matchHeader("Content-Type", "application/json.*", "application/json")
        .body(
            newJsonArrayMinLike(1) { a ->
                a.`object` { t ->
                    t.uuid("id")
                    t.uuid("contractId", UUID.fromString(PRICED_CONTRACT))
                    t.uuid("fundId", UUID.fromString(PRICED_FUND))
                    t.stringMatcher("type", "SUBSCRIBE|REDEEM|SWITCH_OUT|SWITCH_IN|FEE", "SUBSCRIBE")
                    t.numberType("units", BigDecimal("100.000000"))
                    t.numberType("amount", BigDecimal("125.00"))
                    t.uuid("navId")
                    t.numberType("navPerUnit", BigDecimal("1.250000"))
                    t.stringType("pricedAt", "2026-10-01T16:00:00Z")
                }
            }.build(),
        )
        .toPact()

    // --- strategies -------------------------------------------------------------------------

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun strategiesPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(PRICED_STATE)
        .uponReceiving("GET the fund strategies a pension contract may elect")
        .path("/api/v1/strategies")
        .method("GET")
        .willRespondWith()
        .status(200)
        .matchHeader("Content-Type", "application/json.*", "application/json")
        .body(
            newJsonArrayMinLike(1) { a ->
                a.`object` { s ->
                    s.uuid("id")
                    s.stringType("name", "BALANCED")
                    s.stringMatcher("status", "ACTIVE|CLOSED", "ACTIVE")
                    s.minArrayLike("allocations", 1) { al ->
                        al.uuid("fundId", UUID.fromString(PRICED_FUND))
                        al.numberType("weight", BigDecimal("1"))
                    }
                }
            }.build(),
        )
        .toPact()

    // --- orders -----------------------------------------------------------------------------

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun subscribePact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(PRICED_STATE)
        .uponReceiving("POST a SUBSCRIBE unit order for a pension contract with an Idempotency-Key")
        .path("/api/v1/contracts/$ORDER_CONTRACT/orders")
        .method("POST")
        .matchHeader("Idempotency-Key", "[0-9a-f]{64}", "a".repeat(64))
        .matchHeader("Content-Type", "application/json.*", "application/json")
        .body(
            newJsonBody { o ->
                o.stringValue("fundId", PRICED_FUND)
                o.stringValue("type", "SUBSCRIBE")
                o.numberValue("amount", BigDecimal("1000.00"))
            }.build(),
        )
        .willRespondWith()
        .status(202)
        .matchHeader("Content-Type", "application/json.*", "application/json")
        .body(
            newJsonBody { o ->
                o.uuid("id")
                o.stringValue("status", "PENDING")
            }.build(),
        )
        .toPact()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun redeemPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(PRICED_STATE)
        .uponReceiving("POST a REDEEM unit order in units for a pension contract with an Idempotency-Key")
        .path("/api/v1/contracts/$PRICED_CONTRACT/orders")
        .method("POST")
        .matchHeader("Idempotency-Key", "[0-9a-f]{64}", "b".repeat(64))
        .matchHeader("Content-Type", "application/json.*", "application/json")
        .body(
            newJsonBody { o ->
                o.stringValue("fundId", PRICED_FUND)
                o.stringValue("type", "REDEEM")
                o.numberValue("units", BigDecimal("10.000000"))
            }.build(),
        )
        .willRespondWith()
        .status(202)
        .matchHeader("Content-Type", "application/json.*", "application/json")
        .body(
            newJsonBody { o ->
                o.uuid("id")
                o.stringValue("status", "PENDING")
            }.build(),
        )
        .toPact()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun orderWithoutIdentityPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(NEGATIVE_AUTH_STATE)
        .uponReceiving("POST a pension unit order with no M2M identity")
        .path("/api/v1/contracts/$ORDER_CONTRACT/orders")
        .method("POST")
        .matchHeader("Idempotency-Key", "[0-9a-f]{64}", "c".repeat(64))
        .matchHeader("Content-Type", "application/json.*", "application/json")
        .body(
            newJsonBody { o ->
                o.stringValue("fundId", PRICED_FUND)
                o.stringValue("type", "SUBSCRIBE")
                o.numberValue("amount", BigDecimal("1000.00"))
            }.build(),
        )
        .willRespondWith()
        .status(401)
        .toPact()

    // --- tests ------------------------------------------------------------------------------

    @Test
    @PactTestFor(pactMethod = "pricedHoldingsPact")
    fun `priced holdings decode into a holding with value and a pending order`(mock: MockServer) {
        assertThat(clientPath("holdings", PRICED_CONTRACT)).isEqualTo("/api/v1/contracts/$PRICED_CONTRACT/holdings")
        val view = runBlocking { adapter(mock).holdings(UUID.fromString(PRICED_CONTRACT)) }
        val holding = view.holdings.single()
        assertThat(holding.fundId).isEqualTo(UUID.fromString(PRICED_FUND))
        assertThat(holding.value).isEqualByComparingTo("125.00")
        assertThat(holding.navDate).isNotNull()
        assertThat(view.pendingOrders.single().type).isEqualTo("SUBSCRIBE")
    }

    @Test
    @PactTestFor(pactMethod = "unpublishedNavHoldingsPact")
    fun `a fund with no published NAV decodes with a null value, never a guessed one`(mock: MockServer) {
        val view = runBlocking { adapter(mock).holdings(UUID.fromString(UNPUBLISHED_CONTRACT)) }
        val holding = view.holdings.single()
        assertThat(holding.units).isEqualByComparingTo("40")
        assertThat(holding.value).isNull()
        assertThat(holding.navPerUnit).isNull()
    }

    @Test
    @PactTestFor(pactMethod = "holdingsWithoutIdentityPact")
    fun `holdings without an identity are 401`(mock: MockServer) {
        given().baseUri(mock.getUrl()).get(clientPath("holdings", PRICED_CONTRACT)).then().statusCode(401)
    }

    @Test
    @PactTestFor(pactMethod = "transactionsPact")
    fun `transactions decode through the adapter`(mock: MockServer) {
        assertThat(clientPath("transactions", PRICED_CONTRACT))
            .isEqualTo("/api/v1/contracts/$PRICED_CONTRACT/transactions")
        val txs = runBlocking { adapter(mock).transactions(UUID.fromString(PRICED_CONTRACT)) }
        assertThat(txs.single().navPerUnit).isEqualByComparingTo("1.25")
        assertThat(txs.single().navId).isNotNull()
    }

    @Test
    @PactTestFor(pactMethod = "strategiesPact")
    fun `strategies decode with their allocation`(mock: MockServer) {
        assertThat(clientPath("strategies", null)).isEqualTo("/api/v1/strategies")
        val strategies = runBlocking { register(mock).strategies() }
        assertThat(strategies.single().allocations.single().fundId).isEqualTo(UUID.fromString(PRICED_FUND))
    }

    @Test
    @PactTestFor(pactMethod = "subscribePact")
    fun `a subscription order is accepted pending`(mock: MockServer) {
        assertThat(clientPath("placeOrder", ORDER_CONTRACT)).isEqualTo("/api/v1/contracts/$ORDER_CONTRACT/orders")
        val order = runBlocking {
            register(mock).placeOrder(
                UUID.fromString(ORDER_CONTRACT),
                SUBSCRIBE_KEY,
                OrderRequestDto(UUID.fromString(PRICED_FUND), "SUBSCRIBE", amount = BigDecimal("1000.00")),
            )
        }
        assertThat(order.status).isEqualTo("PENDING")
    }

    @Test
    @PactTestFor(pactMethod = "redeemPact")
    fun `a redemption order in units is accepted pending`(mock: MockServer) {
        val order = runBlocking {
            register(mock).placeOrder(
                UUID.fromString(PRICED_CONTRACT),
                REDEEM_KEY,
                OrderRequestDto(UUID.fromString(PRICED_FUND), "REDEEM", units = BigDecimal("10.000000")),
            )
        }
        assertThat(order.status).isEqualTo("PENDING")
    }

    @Test
    @PactTestFor(pactMethod = "orderWithoutIdentityPact")
    fun `an order without an identity is 401`(mock: MockServer) {
        given().baseUri(mock.getUrl()).contentType("application/json").header("Idempotency-Key", SUBSCRIBE_KEY)
            .body("""{"fundId":"$PRICED_FUND","type":"SUBSCRIBE","amount":1000.00}""")
            .post(clientPath("placeOrder", ORDER_CONTRACT)).then().statusCode(401)
    }

    // --- helpers ----------------------------------------------------------------------------

    /** A [FundRegister] over plain HTTP to the mock, decoding exactly the DTOs the REST client uses. */
    private fun register(mock: MockServer) = object : FundRegister {
        override suspend fun holdings(contractId: UUID): ContractValuationDto =
            mapper.readValue(get(mock, clientPath("holdings", contractId.toString())))

        override suspend fun placeOrder(
            contractId: UUID,
            idempotencyKey: String,
            order: OrderRequestDto,
        ): UnitOrderDto {
            val body = mapper.writer().writeValueAsString(
                buildMap {
                    put("fundId", order.fundId.toString())
                    put("type", order.type)
                    order.amount?.let { put("amount", it) }
                    order.units?.let { put("units", it) }
                },
            )
            val raw = given().baseUri(mock.getUrl()).contentType("application/json")
                .header("Idempotency-Key", idempotencyKey).body(body)
                .post(clientPath("placeOrder", contractId.toString())).then().statusCode(202).extract().asString()
            return mapper.readValue(raw)
        }

        override suspend fun strategies(): List<StrategyDto> =
            mapper.readValue(get(mock, clientPath("strategies", null)))

        override suspend fun transactions(contractId: UUID): List<UnitTransactionDto> =
            mapper.readValue(get(mock, clientPath("transactions", contractId.toString())))
    }

    private fun adapter(mock: MockServer) = PensionFundOrders(register(mock), { null }, Clock.systemUTC())

    private fun get(mock: MockServer, path: String): String =
        given().baseUri(mock.getUrl()).get(path).then().statusCode(200).extract().asString()

    /** The OUTGOING request path, reflected off the client's annotations (expectations stay literal). */
    private fun clientPath(method: String, contractId: String?): String {
        val base = PensionFundRestClient::class.java.getAnnotation(Path::class.java).value
        val sub = PensionFundRestClient::class.java.methods.single { it.name == method }
            .getAnnotation(Path::class.java).value
        return (base + sub).replace("{contractId}", contractId ?: "")
    }

    private companion object {
        const val CONSUMER = "openbank-pension-service"
        const val PROVIDER = "openbank-pension-fund-service"

        /** Seeded by pension-fund-service's provider replay: a fund with a PUBLISHED NAV, a holding,
         *  a priced transaction, a pending order and an ACTIVE strategy over that fund. */
        const val PRICED_STATE = "a pension contract holds units priced at a published NAV"

        /** A holding in a fund that has never published a NAV. */
        const val UNPUBLISHED_STATE = "a pension contract holds units in a fund with no published NAV"

        /** The fleet's missing-identity state; replayed by the provider's NegativeAuth twin. */
        const val NEGATIVE_AUTH_STATE = "no valid M2M identity is presented"

        // Fixed ids the provider states seed (PensionFundPactStates in pension-fund-service).
        const val PRICED_CONTRACT = "7a1e0000-0000-4000-8000-0000000000c1"
        const val UNPUBLISHED_CONTRACT = "7a1e0000-0000-4000-8000-0000000000c2"
        const val ORDER_CONTRACT = "7a1e0000-0000-4000-8000-0000000000c3"
        const val PRICED_FUND = "7a1e0000-0000-4000-8000-0000000000f1"
        const val UNPUBLISHED_FUND = "7a1e0000-0000-4000-8000-0000000000f2"

        val SUBSCRIBE_KEY = PensionFundOrders.legKey("contribution:pact", UUID.fromString(PRICED_FUND))
        val REDEEM_KEY = PensionFundOrders.legKey("redeem:pact", UUID.fromString(PRICED_FUND))
    }
}
