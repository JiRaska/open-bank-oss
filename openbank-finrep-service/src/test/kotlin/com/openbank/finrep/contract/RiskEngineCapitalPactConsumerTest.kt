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
import com.openbank.finrep.domain.mapper.C0200Mapper
import com.openbank.finrep.infrastructure.client.CapitalResponse
import com.openbank.finrep.infrastructure.client.RiskEngineRestClient
import com.openbank.finrep.infrastructure.client.SnapshotRunListResponse
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import jakarta.ws.rs.QueryParam
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.time.LocalDate

/**
 * Consumer-driven contract for the risk engine's snapshot list and Pillar 1 capital result that
 * COREP C 02.00 reads (ADR-0313 D6). The committed pact is replayed by the risk engine's
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

    private fun currency(c: au.com.dius.pact.consumer.dsl.LambdaDslObject) {
        c.stringType("currency", "CZK")
        c.eachLike("classes") { k ->
            k.stringType("exposureClass", "cash")
            k.decimalType("ead", 1500.00)
            k.decimalType("rwa", 0.00)
        }
        c.decimalType("totalRwa", 0.00)
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

    private companion object {
        const val STATE = "a TIED_OUT risk snapshot exists at the report date"
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
        val limitParam: String = listMethod.parameterAnnotations[0].filterIsInstance<QueryParam>().single().value
    }
}
