// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.finrep.application.port.out.RiskCapitalLookup
import com.openbank.finrep.application.port.out.RiskCapitalResult
import com.openbank.finrep.application.port.out.RiskExposureClass
import com.openbank.finrep.application.port.out.RiskHqlaLine
import com.openbank.finrep.application.port.out.RiskInflowLine
import com.openbank.finrep.application.port.out.RiskLiquidityLookup
import com.openbank.finrep.application.port.out.RiskLiquidityResult
import com.openbank.finrep.application.port.out.RiskOutflowLine
import com.openbank.finrep.domain.mapper.C0200Mapper
import com.openbank.finrep.domain.mapper.C7200Mapper
import com.openbank.finrep.domain.mapper.C7300Mapper
import com.openbank.finrep.domain.mapper.C7400Mapper
import com.openbank.finrep.infrastructure.client.CapitalResponse
import com.openbank.finrep.infrastructure.client.LiquidityResponse
import com.openbank.finrep.infrastructure.client.RiskEngineRestClient
import com.openbank.finrep.infrastructure.client.SnapshotRunListResponse
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import jakarta.ws.rs.QueryParam
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Consumer-driven contract for the risk engine's snapshot list, Pillar 1 capital result and LCR
 * liquid assets that COREP C 02.00 and C 72.00 read (ADR-0313 D6). The committed pact is replayed by the risk engine's
 * `RiskEnginePactProviderVerificationTest` (`@PactFolder`), the half that catches a wrong path.
 *
 * Paths are LITERALS on the interaction side and reflected off [RiskEngineRestClient] on the request
 * side: deriving both from the annotation would let them move together and pass against any path.
 * Only the fields finrep reads are pinned; the run id is supplied by the provider state, because the
 * provider mints it when the state creates the run.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-risk-engine", pactVersion = PactSpecVersion.V3)
class RiskEngineCapitalPactConsumerTest {

    private val json = jacksonObjectMapper().findAndRegisterModules()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

    @Pact(consumer = "openbank-finrep-service", provider = "openbank-risk-engine")
    fun listRunsPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(STATE)
        .uponReceiving("GET the most recent risk snapshot runs for a COREP report date")
        .path(RISK_SNAPSHOTS_PATH)
        .query("limit=$RUN_LIST_LIMIT")
        .method("GET")
        .willRespondWith()
        .status(200)
        .body(
            newJsonBody { o ->
                // Heterogeneous list (every run the engine holds), so types only: a pinned value
                // would claim every run is this one.
                o.eachLike("runs") { run ->
                    run.uuid("id", java.util.UUID.fromString(EXAMPLE_RUN_ID))
                    run.stringType("asOf", REPORTING_DATE)
                    run.stringType("recordedAt", "2026-07-01T06:00:00Z")
                    run.stringType("status", "TIED_OUT")
                }
            }.build(),
        )
        .toPact()

    @Pact(consumer = "openbank-finrep-service", provider = "openbank-risk-engine")
    fun capitalPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(STATE)
        .uponReceiving("GET the Pillar 1 credit-risk capital of a TIED_OUT run")
        .pathFromProviderState(
            "$RISK_SNAPSHOTS_PATH/\${runId}/capital",
            "$RISK_SNAPSHOTS_PATH/$EXAMPLE_RUN_ID/capital",
        )
        .method("GET")
        .willRespondWith()
        .status(200)
        .body(
            newJsonBody { o ->
                o.uuid("runId", java.util.UUID.fromString(EXAMPLE_RUN_ID))
                o.stringValue("asOf", REPORTING_DATE)
                o.stringType("parameterSetId", "bcbs-d424-sa")
                o.stringType("parameterSetVersion", "1")
                o.eachLike("currencies") { c -> currency(c) }
                o.`object`("total") { t -> currency(t) }
                o.minArrayLike("unclassified", 0, 1) { u -> u.stringType("glAccountCode", "9999") }
            }.build(),
        )
        .toPact()

    @Pact(consumer = "openbank-finrep-service", provider = "openbank-risk-engine")
    fun liquidityPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(LIQUIDITY_STATE)
        .uponReceiving("GET the LCR liquid assets of a TIED_OUT run")
        .pathFromProviderState(
            "$RISK_SNAPSHOTS_PATH/\${runId}/liquidity",
            "$RISK_SNAPSHOTS_PATH/$EXAMPLE_RUN_ID/liquidity",
        )
        .method("GET")
        .willRespondWith()
        .status(200)
        .body(
            newJsonBody { o ->
                o.uuid("runId", java.util.UUID.fromString(EXAMPLE_RUN_ID))
                o.stringValue("asOf", REPORTING_DATE)
                o.stringType("parameterSetId", "bcbs-d238-d295")
                o.stringType("parameterSetVersion", "2")
                o.eachLike("currencies") { c -> currencyLiquidity(c) }
                o.`object`("total") { t -> currencyLiquidity(t) }
                o.minArrayLike("unclassified", 0, 1) { u -> u.stringType("glAccountCode", "9999") }
            }.build(),
        )
        .toPact()

    /**
     * The negative half (ADR-0279): with no identity the risk engine must refuse the read. A
     * contract of successes alone stays green when the provider stops enforcing authentication.
     */
    @Pact(consumer = "openbank-finrep-service", provider = "openbank-risk-engine")
    fun anonymousListRefusedPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(NO_IDENTITY)
        .uponReceiving("GET the risk snapshot runs with no identity")
        .path(RISK_SNAPSHOTS_PATH)
        .query("limit=$RUN_LIST_LIMIT")
        .method("GET")
        .willRespondWith()
        .status(HTTP_UNAUTHORIZED)
        .toPact()

    private fun currency(c: au.com.dius.pact.consumer.dsl.LambdaDslObject) {
        c.stringType("currency", "CZK")
        c.eachLike("classes") { k ->
            k.stringType("exposureClass", "cash")
            k.decimalType("ead", 1500.00)
            k.decimalType("rwa", 0.00)
        }
        c.decimalType("totalRwa", 0.00)
    }

    private fun currencyLiquidity(c: au.com.dius.pact.consumer.dsl.LambdaDslObject) {
        c.stringType("currency", "CZK")
        c.`object`("lcr") { lcr ->
            lcr.`object`("hqla") { h ->
                // A tied book may hold no liquid asset at all, so the list may be empty.
                h.minArrayLike("lines", 0, 1) { l ->
                    l.stringType("level", "L1")
                    l.decimalType("marketValue", 1000.00)
                    l.numberType("haircut", 0)
                    l.decimalType("afterHaircut", 1000.00)
                }
                h.decimalType("level1", 1000.00)
                h.decimalType("level2a", 0.00)
                h.decimalType("level2b", 0.00)
            }
            // C 73.00 reads the outflow lines and the engine's own total they must tie to. A tied book
            // may hold no liability with an outflow, so the list may be empty.
            lcr.minArrayLike("outflows", 0, 1) { o ->
                o.stringType("factorKey", "lcr-retail-less-stable-runoff")
                o.decimalType("amount", 2000.00)
                o.numberType("factor", 0.10)
                o.decimalType("weighted", 200.00)
            }
            lcr.decimalType("totalOutflows", 200.00)
            // C 74.00 reads the inflow lines, the engine's uncapped total they must tie to, and the
            // 75 % cap as the engine applied it (cap amount, capped total, binding). A tied book may
            // hold no asset with an inflow, so the list may be empty.
            lcr.minArrayLike("inflows", 0, 1) { i ->
                i.stringType("factorKey", "lcr-retail-loan-inflow")
                i.decimalType("amount", 100.00)
                i.numberType("factor", 0.50)
                i.decimalType("weighted", 50.00)
            }
            lcr.decimalType("totalInflows", 50.00)
            lcr.decimalType("inflowCap", 150.00)
            lcr.decimalType("cappedInflows", 50.00)
            lcr.booleanType("inflowCapBinding", false)
        }
    }

    @Test
    @PactTestFor(pactMethod = "listRunsPact")
    fun `the run list carries what the adapter filters on`(mockServer: MockServer) {
        assertThat(runsPath).isEqualTo(RISK_SNAPSHOTS_PATH)
        val body = given().baseUri(mockServer.getUrl()).queryParam(limitParam, RUN_LIST_LIMIT)
            .get(runsPath).then().statusCode(200).extract().asString()
        val run = json.readValue<SnapshotRunListResponse>(body).runs.single()
        assertThat(run.status).isEqualTo("TIED_OUT")
        assertThat(LocalDate.parse(run.asOf)).isEqualTo(LocalDate.parse(REPORTING_DATE))
    }

    @Test
    @PactTestFor(pactMethod = "anonymousListRefusedPact")
    fun `an anonymous read of the run list is refused 401`(mockServer: MockServer) {
        given().baseUri(mockServer.getUrl()).queryParam(limitParam, RUN_LIST_LIMIT)
            .get(runsPath).then().statusCode(HTTP_UNAUTHORIZED)
    }

    @Test
    @PactTestFor(pactMethod = "capitalPact")
    fun `the capital result feeds a C 02_00 render`(mockServer: MockServer) {
        assertThat(capitalPath).isEqualTo("$RISK_SNAPSHOTS_PATH/{id}/capital")
        val body = given().baseUri(mockServer.getUrl()).get(capitalPath.replace("{id}", EXAMPLE_RUN_ID))
            .then().statusCode(200).extract().asString()
        val c = json.readValue<CapitalResponse>(body)
        val total = checkNotNull(c.total)
        val template = C0200Mapper.map(
            RiskCapitalLookup.found(
                RiskCapitalResult(
                    runId = c.runId,
                    asOf = LocalDate.parse(c.asOf),
                    parameterSetId = c.parameterSetId,
                    parameterSetVersion = c.parameterSetVersion,
                    currency = total.currency,
                    classes = total.classes.map { RiskExposureClass(it.exposureClass, it.ead, it.rwa) },
                    totalRwa = total.totalRwa,
                    currencyCount = c.currencies.size,
                    unclassifiedBalances = c.unclassified.size,
                ),
            ),
            LocalDate.parse(REPORTING_DATE),
        )
        assertThat(total.classes.single().exposureClass).isEqualTo("cash")
        // The example carries one unclassified balance, so the render must refuse the credit rows
        // and say why: proves `unclassified` is read, not merely parsed.
        val other = template.cells.single { it.rowRef == "r0211" }
        assertThat(other.isDataGap).isTrue()
        assertThat(other.gapReason).contains("unclassified in risk-engine snapshot ${c.runId}")
    }

    @Test
    @PactTestFor(pactMethod = "liquidityPact")
    fun `the liquidity result feeds a C 72_00 render`(mockServer: MockServer) {
        assertThat(liquidityPath).isEqualTo("$RISK_SNAPSHOTS_PATH/{id}/liquidity")
        val body = given().baseUri(mockServer.getUrl()).get(liquidityPath.replace("{id}", EXAMPLE_RUN_ID))
            .then().statusCode(200).extract().asString()
        val l = json.readValue<LiquidityResponse>(body)
        val total = checkNotNull(l.total)
        val hqla = total.lcr.hqla
        val template = C7200Mapper.map(
            RiskLiquidityLookup.found(
                RiskLiquidityResult(
                    runId = l.runId,
                    asOf = LocalDate.parse(l.asOf),
                    parameterSetId = l.parameterSetId,
                    parameterSetVersion = l.parameterSetVersion,
                    currency = total.currency,
                    lines = hqla.lines.map { RiskHqlaLine(it.level, it.marketValue, it.haircut, it.afterHaircut) },
                    level1 = hqla.level1,
                    level2a = hqla.level2a,
                    level2b = hqla.level2b,
                    currencyCount = l.currencies.size,
                    unclassifiedBalances = l.unclassified.size,
                ),
            ),
            LocalDate.parse(REPORTING_DATE),
        )
        assertThat(hqla.lines.single().level).isEqualTo("L1")
        assertThat(hqla.level1).isEqualByComparingTo("1000.00")
        // The example carries one unclassified balance, so the render must refuse the liquid-asset
        // totals and say why: proves `unclassified` is read, not merely parsed.
        val totalRow = template.cells.single { it.rowRef == "r0010" && it.colRef == "c0040" }
        assertThat(totalRow.isDataGap).isTrue()
        assertThat(totalRow.gapReason).contains("unclassified in risk-engine snapshot ${l.runId}")
    }

    @Test
    @PactTestFor(pactMethod = "liquidityPact")
    fun `the same liquidity result feeds a C 73_00 render`(mockServer: MockServer) {
        assertThat(liquidityPath).isEqualTo("$RISK_SNAPSHOTS_PATH/{id}/liquidity")
        val body = given().baseUri(mockServer.getUrl()).get(liquidityPath.replace("{id}", EXAMPLE_RUN_ID))
            .then().statusCode(200).extract().asString()
        val l = json.readValue<LiquidityResponse>(body)
        val lcr = checkNotNull(l.total).lcr
        val result = RiskLiquidityResult(
            runId = l.runId,
            asOf = LocalDate.parse(l.asOf),
            parameterSetId = l.parameterSetId,
            parameterSetVersion = l.parameterSetVersion,
            currency = checkNotNull(l.total).currency,
            lines = emptyList(),
            level1 = lcr.hqla.level1,
            level2a = lcr.hqla.level2a,
            level2b = lcr.hqla.level2b,
            currencyCount = l.currencies.size,
            unclassifiedBalances = l.unclassified.size,
            outflows = lcr.outflows.map { RiskOutflowLine(it.factorKey, it.amount, it.factor, it.weighted) },
            totalOutflows = lcr.totalOutflows,
        )
        val outflow = lcr.outflows.single()
        assertThat(outflow.factorKey).isEqualTo("lcr-retail-less-stable-runoff")
        assertThat(outflow.amount).isEqualByComparingTo("2000.00")
        assertThat(outflow.factor).isEqualByComparingTo("0.10")
        assertThat(outflow.weighted).isEqualByComparingTo("200.00")
        assertThat(lcr.totalOutflows).isEqualByComparingTo("200.00")
        // With the unclassified balance cleared the render ties and states the outflow, proving the
        // four outflow fields and the total are read, not merely parsed ...
        val tied = C7300Mapper.map(
            RiskLiquidityLookup.found(result.copy(unclassifiedBalances = 0)),
            LocalDate.parse(REPORTING_DATE),
        )
        assertThat(
            tied.cells.single {
                it.rowRef == "r0130" && it.colRef == "c0060"
            }.value,
        ).isEqualByComparingTo("200.00")
        assertThat(tied.cells.single { it.rowRef == "r0010" && it.colRef == "c0060" }.isDataGap).isFalse()
        // ... and the example as served (one unclassified balance) must refuse the totals and say why.
        val served = C7300Mapper.map(RiskLiquidityLookup.found(result), LocalDate.parse(REPORTING_DATE))
        val totalRow = served.cells.single { it.rowRef == "r0010" && it.colRef == "c0060" }
        assertThat(totalRow.isDataGap).isTrue()
        assertThat(totalRow.gapReason).contains("unclassified in risk-engine snapshot ${l.runId}")
    }

    @Test
    @PactTestFor(pactMethod = "liquidityPact")
    fun `the same liquidity result feeds a C 74_00 render`(mockServer: MockServer) {
        assertThat(liquidityPath).isEqualTo("$RISK_SNAPSHOTS_PATH/{id}/liquidity")
        val body = given().baseUri(mockServer.getUrl()).get(liquidityPath.replace("{id}", EXAMPLE_RUN_ID))
            .then().statusCode(200).extract().asString()
        val l = json.readValue<LiquidityResponse>(body)
        val lcr = checkNotNull(l.total).lcr
        val result = RiskLiquidityResult(
            runId = l.runId,
            asOf = LocalDate.parse(l.asOf),
            parameterSetId = l.parameterSetId,
            parameterSetVersion = l.parameterSetVersion,
            currency = checkNotNull(l.total).currency,
            lines = emptyList(),
            level1 = lcr.hqla.level1,
            level2a = lcr.hqla.level2a,
            level2b = lcr.hqla.level2b,
            currencyCount = l.currencies.size,
            unclassifiedBalances = l.unclassified.size,
            outflows = lcr.outflows.map { RiskOutflowLine(it.factorKey, it.amount, it.factor, it.weighted) },
            totalOutflows = lcr.totalOutflows,
            inflows = lcr.inflows.map { RiskInflowLine(it.factorKey, it.amount, it.factor, it.weighted) },
            totalInflows = lcr.totalInflows,
            inflowCap = lcr.inflowCap,
            cappedInflows = lcr.cappedInflows,
            inflowCapBinding = lcr.inflowCapBinding,
        )
        val inflow = lcr.inflows.single()
        assertThat(inflow.factorKey).isEqualTo("lcr-retail-loan-inflow")
        assertThat(inflow.amount).isEqualByComparingTo("100.00")
        assertThat(inflow.factor).isEqualByComparingTo("0.50")
        assertThat(inflow.weighted).isEqualByComparingTo("50.00")
        assertThat(lcr.totalInflows).isEqualByComparingTo("50.00")
        assertThat(lcr.inflowCap).isEqualByComparingTo("150.00")
        assertThat(lcr.cappedInflows).isEqualByComparingTo("50.00")
        assertThat(lcr.inflowCapBinding).isFalse()
        // With the unclassified balance cleared the render ties, states the inflow and validates the
        // cap, proving every inflow field is read, not merely parsed ...
        val tied = C7400Mapper.map(
            RiskLiquidityLookup.found(result.copy(unclassifiedBalances = 0)),
            LocalDate.parse(REPORTING_DATE),
        )
        assertThat(tied.cells.single { it.rowRef == "r0030" && it.colRef == "c0140" }.value)
            .isEqualByComparingTo("50.00")
        assertThat(tied.cells.single { it.rowRef == "r0010" && it.colRef == "c0140" }.isDataGap).isFalse()
        // The cap fields are read to validate capped == min(uncapped, cap); a capped figure that is not
        // fails the render (the capped total itself belongs to C 76.00, not a C 74.00 row).
        assertThatThrownBy {
            C7400Mapper.map(
                RiskLiquidityLookup.found(result.copy(unclassifiedBalances = 0, cappedInflows = BigDecimal("49.00"))),
                LocalDate.parse(REPORTING_DATE),
            )
        }.isInstanceOf(IllegalStateException::class.java).hasMessageContaining("capped inflows are 49.00")
        // ... and the example as served (one unclassified balance) must refuse the totals and say why.
        val served = C7400Mapper.map(RiskLiquidityLookup.found(result), LocalDate.parse(REPORTING_DATE))
        val totalRow = served.cells.single { it.rowRef == "r0010" && it.colRef == "c0140" }
        assertThat(totalRow.isDataGap).isTrue()
        assertThat(totalRow.gapReason).contains("unclassified in risk-engine snapshot ${l.runId}")
    }

    private companion object {
        const val LIQUIDITY_STATE = "a TIED_OUT risk snapshot with an LCR result exists at the report date"
        const val STATE = "a TIED_OUT risk snapshot exists at the report date"
        const val NO_IDENTITY = "no valid identity is presented"
        const val HTTP_UNAUTHORIZED = 401
        const val RISK_SNAPSHOTS_PATH = "/api/v1/risk/snapshots"
        const val REPORTING_DATE = "2026-06-30"
        const val EXAMPLE_RUN_ID = "0190a4c0-0000-7000-8000-00000000c020"
        const val RUN_LIST_LIMIT = 100

        private val listMethod =
            RiskEngineRestClient::class.java.getDeclaredMethod("listRuns", Int::class.javaPrimitiveType)
        private val capitalMethod =
            RiskEngineRestClient::class.java.getDeclaredMethod("capital", String::class.java)

        val runsPath: String = RiskEngineRestClient::class.java.getAnnotation(Path::class.java).value
        val capitalPath: String = listOf(runsPath, capitalMethod.getAnnotation(Path::class.java).value)
            .joinToString("/") { it.trim('/') }.let { "/$it" }
        private val liquidityMethod =
            RiskEngineRestClient::class.java.getDeclaredMethod("liquidity", String::class.java)
        val liquidityPath: String = listOf(runsPath, liquidityMethod.getAnnotation(Path::class.java).value)
            .joinToString("/") { it.trim('/') }.let { "/$it" }
        val limitParam: String = listMethod.parameterAnnotations[0].filterIsInstance<QueryParam>().single().value
    }
}
