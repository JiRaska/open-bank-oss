// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonArrayLike
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.pension.infrastructure.fund.ContractValuationDto
import com.openbank.pension.infrastructure.fund.PensionFundRestClient
import com.openbank.pension.infrastructure.fund.StrategyDto
import com.openbank.pension.infrastructure.fund.UnitOrderDto
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.util.UUID

/** Consumer contract for the live pension-fund unit-register routes used by pension-service. */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-pension-fund-service", pactVersion = PactSpecVersion.V3)
class PensionFundPactConsumerTest {

    private val mapper = jacksonObjectMapper().findAndRegisterModules()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun holdingsPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(POSITIVE_STATE)
        .uponReceiving("GET the contract's valued pension fund holdings")
        .path(HOLDINGS_PATH)
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(JSON_RESPONSE)
        .body(
            """
            {"contractId":"$CONTRACT_ID","holdings":[{"fundId":"$FUND_ID","units":1000.000000,
             "navPerUnit":1.000000,"value":1000.00,"currency":"CZK"}]}
            """.trimIndent(),
        )
        .toPact()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun strategiesPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(POSITIVE_STATE)
        .uponReceiving("GET the active investment strategies pension-service can allocate against")
        .path(STRATEGIES_PATH)
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(JSON_RESPONSE)
        .body(
            newJsonArrayLike(1) { strategy ->
                strategy.uuid("id")
                strategy.stringType("name", "Pact strategy")
                strategy.stringType("status", "ACTIVE")
                strategy.eachLike("allocations") { allocation ->
                    allocation.uuid("fundId")
                    allocation.numberType("weight", 1.0)
                }
            }.build(),
        )
        .toPact()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun subscribeOrderPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(POSITIVE_STATE)
        .uponReceiving("POST a keyed SUBSCRIBE order for a pension contract")
        .path(ORDERS_PATH)
        .method("POST")
        .headers(JSON_REQUEST + ("Idempotency-Key" to ORDER_KEY))
        .body("""{"fundId":"$FUND_ID","type":"SUBSCRIBE","amount":250.00}""")
        .willRespondWith()
        .status(202)
        .headers(JSON_RESPONSE)
        .body(
            newJsonBody { order ->
                order.uuid("id")
                order.stringValue("status", "PENDING")
            }.build(),
        )
        .toPact()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun unauthenticatedHoldingsPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(NO_IDENTITY_STATE)
        .uponReceiving("GET pension holdings without a valid M2M identity")
        .path(HOLDINGS_PATH)
        .method("GET")
        .willRespondWith()
        .status(401)
        .toPact()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun orderWithoutIdempotencyKeyPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(POSITIVE_STATE)
        .uponReceiving("POST a pension subscription without its required idempotency key")
        .path(ORDERS_PATH)
        .method("POST")
        .headers(JSON_REQUEST)
        .body("""{"fundId":"$FUND_ID","type":"SUBSCRIBE","amount":250.00}""")
        .willRespondWith()
        .status(400)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "holdingsPact")
    fun `holdings response deserializes the valuation fields pension consumes`(server: MockServer) {
        assertThat(clientPath("holdings")).isEqualTo(HOLDINGS_PATH)
        val raw = given().baseUri(server.getUrl()).get(clientPath("holdings"))
            .then().statusCode(200).extract().asString()
        val valuation = mapper.readValue<ContractValuationDto>(raw)

        assertThat(valuation.contractId).isEqualTo(UUID.fromString(CONTRACT_ID))
        val holding = valuation.holdings.single()
        assertThat(holding.fundId).isEqualTo(UUID.fromString(FUND_ID))
        assertThat(holding.units).isEqualByComparingTo(BigDecimal("1000"))
        assertThat(holding.navPerUnit).isEqualByComparingTo(BigDecimal("1"))
        assertThat(holding.value).isEqualByComparingTo(BigDecimal("1000"))
        assertThat(holding.currency).isEqualTo("CZK")
    }

    @Test
    @PactTestFor(pactMethod = "strategiesPact")
    fun `strategy response deserializes the allocation pension consumes`(server: MockServer) {
        assertThat(clientPath("strategies")).isEqualTo(STRATEGIES_PATH)
        val raw = given().baseUri(server.getUrl()).get(clientPath("strategies"))
            .then().statusCode(200).extract().asString()
        val strategies = mapper.readValue<List<StrategyDto>>(raw)

        val strategy = strategies.single()
        assertThat(strategy.id.toString()).matches(UUID_PATTERN)
        assertThat(strategy.name).isEqualTo("Pact strategy")
        assertThat(strategy.status).isEqualTo("ACTIVE")
        assertThat(strategy.allocations.single().fundId.toString()).matches(UUID_PATTERN)
        assertThat(strategy.allocations.single().weight).isEqualByComparingTo("1.0")
    }

    @Test
    @PactTestFor(pactMethod = "subscribeOrderPact")
    fun `keyed subscription response deserializes the queued order`(server: MockServer) {
        assertThat(clientPath("placeOrder")).isEqualTo(ORDERS_PATH)
        val raw = given().baseUri(server.getUrl()).contentType("application/json")
            .header("Idempotency-Key", ORDER_KEY)
            .body("""{"fundId":"$FUND_ID","type":"SUBSCRIBE","amount":250.00}""")
            .post(clientPath("placeOrder"))
            .then().statusCode(202).extract().asString()
        val order = mapper.readValue<UnitOrderDto>(raw)

        assertThat(order.id.toString()).matches(UUID_PATTERN)
        assertThat(order.status).isEqualTo("PENDING")
    }

    @Test
    @PactTestFor(pactMethod = "unauthenticatedHoldingsPact")
    fun `holdings read without a service identity is refused`(server: MockServer) {
        given().baseUri(server.getUrl()).get(clientPath("holdings")).then().statusCode(401)
    }

    @Test
    @PactTestFor(pactMethod = "orderWithoutIdempotencyKeyPact")
    fun `subscription without an idempotency key is refused`(server: MockServer) {
        given().baseUri(server.getUrl()).contentType("application/json")
            .body("""{"fundId":"$FUND_ID","type":"SUBSCRIBE","amount":250.00}""")
            .post(clientPath("placeOrder"))
            .then().statusCode(400)
    }

    private fun clientPath(methodName: String): String {
        val base = PensionFundRestClient::class.java.getAnnotation(Path::class.java).value
        val method = PensionFundRestClient::class.java.methods.single { it.name == methodName }
        val suffix = method.getAnnotation(Path::class.java).value
        return (base + suffix).replace("{contractId}", CONTRACT_ID)
    }

    private companion object {
        const val CONSUMER = "openbank-pension-service"
        const val PROVIDER = "openbank-pension-fund-service"
        const val POSITIVE_STATE = "pension fund has an active fund and strategy for contract"
        const val NO_IDENTITY_STATE = "no valid M2M identity is presented"
        const val FUND_ID = "10000000-0000-4000-8000-000000000001"
        const val CONTRACT_ID = "10000000-0000-4000-8000-000000000002"
        const val UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        const val ORDER_KEY = "pension-pact-subscribe-001"
        const val HOLDINGS_PATH = "/api/v1/contracts/$CONTRACT_ID/holdings"
        const val ORDERS_PATH = "/api/v1/contracts/$CONTRACT_ID/orders"
        const val STRATEGIES_PATH = "/api/v1/strategies"
        val JSON_REQUEST = mapOf("Content-Type" to "application/json")
        val JSON_RESPONSE = mapOf("Content-Type" to "application/json")
    }
}
