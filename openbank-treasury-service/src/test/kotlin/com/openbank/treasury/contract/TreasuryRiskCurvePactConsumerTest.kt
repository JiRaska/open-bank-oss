// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.contract

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
import com.openbank.treasury.domain.model.CurvePillar
import com.openbank.treasury.domain.model.CurveSetView
import com.openbank.treasury.domain.model.MarketCurve
import com.openbank.treasury.domain.model.QuotePricer
import com.openbank.treasury.infrastructure.quote.CurveSetListResponse
import com.openbank.treasury.infrastructure.quote.CurveSetResponse
import com.openbank.treasury.infrastructure.quote.RiskCurveRestClient
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import jakarta.ws.rs.QueryParam
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.time.LocalDate
import java.util.UUID

/**
 * Consumer-driven contract for the risk engine's curve-set reads the simulated counterparties'
 * quotes make (ADR-0315 D9, [RiskCurveRestClient]). The committed pact
 * (`pacts/openbank-treasury-service-openbank-risk-engine.json`) is replayed by the risk engine's
 * `RiskEnginePactProviderVerificationTest` (`@PactFolder`), the half that catches a wrong PATH —
 * a mock server answers any path it is asked (#2269).
 *
 * Paths are LITERALS on the interaction side; only the request actually sent is reflected off the
 * client's `@Path` ([listPath], [getPath]). Deriving both from the annotation would let them move
 * together and stay green against a route that does not exist. The curve-set id is supplied by the
 * provider state, because the provider mints it when the state uploads the set.
 *
 * IMPORTANT — regenerate on change: re-run
 * `./gradlew :openbank-treasury-service:test --tests "*TreasuryRiskCurvePactConsumerTest*"` and
 * commit the regenerated pact in the same PR; `pact-drift-check.yml` fails otherwise.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-risk-engine", pactVersion = PactSpecVersion.V3)
class TreasuryRiskCurvePactConsumerTest {

    private val json = jacksonObjectMapper().findAndRegisterModules()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

    @Pact(consumer = "openbank-treasury-service", provider = "openbank-risk-engine")
    fun latestCurveSetPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(STATE)
        .uponReceiving("GET the newest curve set summary for the simulated quotes")
        .path("/api/v1/risk/curve-sets")
        .query("limit=1")
        .method("GET")
        .willRespondWith()
        .status(200)
        .body(
            newJsonBody { o ->
                // Types only: the newest set is whichever the engine recorded last.
                o.eachLike("curveSets") { s ->
                    s.uuid("id", UUID.fromString(EXAMPLE_ID))
                    s.stringType("asOf", AS_OF)
                    s.stringType("provenance", "synthetic")
                }
            }.build(),
        )
        .toPact()

    @Pact(consumer = "openbank-treasury-service", provider = "openbank-risk-engine")
    fun curveSetPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(STATE)
        .uponReceiving("GET a curve set with its pillars for the simulated quotes")
        .pathFromProviderState("/api/v1/risk/curve-sets/\${curveSetId}", "/api/v1/risk/curve-sets/$EXAMPLE_ID")
        .method("GET")
        .willRespondWith()
        .status(200)
        .body(
            newJsonBody { o ->
                o.uuid("id", UUID.fromString(EXAMPLE_ID))
                o.stringType("asOf", AS_OF)
                o.stringType("provenance", "synthetic")
                // The quote reads the currency's RFR curve BY NAME (CZK → CZEONIA), so the index is
                // pinned by value, not merely typed: a renamed index is exactly the defect to catch.
                o.eachLike("curves") { c ->
                    c.stringValue("index", "CZEONIA")
                    c.stringType("currency", "CZK")
                    c.eachLike("pillars") { p ->
                        p.stringType("date", PILLAR_DATE)
                        p.decimalType("zeroRate", ZERO_RATE)
                    }
                }
            }.build(),
        )
        .toPact()

    /**
     * The negative half (ADR-0279): with no identity the risk engine must refuse the read. A
     * contract of successes alone stays green when the provider stops enforcing authentication.
     */
    @Pact(consumer = "openbank-treasury-service", provider = "openbank-risk-engine")
    fun anonymousListRefusedPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(NO_IDENTITY)
        .uponReceiving("GET the curve sets with no identity")
        .path("/api/v1/risk/curve-sets")
        .query("limit=1")
        .method("GET")
        .willRespondWith()
        .status(HTTP_UNAUTHORIZED)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "latestCurveSetPact")
    fun `the newest-set list carries the id the adapter reads next`(mockServer: MockServer) {
        assertThat(listPath).isEqualTo("/api/v1/risk/curve-sets")
        assertThat(limitParam).isEqualTo("limit")
        val body = given().baseUri(mockServer.getUrl()).queryParam(limitParam, 1)
            .get(listPath).then().statusCode(200).extract().asString()
        assertThat(
            json.readValue<CurveSetListResponse>(body).curveSets.single().id,
        ).isEqualTo(UUID.fromString(EXAMPLE_ID))
    }

    @Test
    @PactTestFor(pactMethod = "curveSetPact")
    fun `the curve set prices a quote`(mockServer: MockServer) {
        assertThat(getPath).isEqualTo("/api/v1/risk/curve-sets/{id}")
        val body = given().baseUri(mockServer.getUrl()).get(getPath.replace("{id}", EXAMPLE_ID))
            .then().statusCode(200).extract().asString()
        val set = json.readValue<CurveSetResponse>(body)
        val view = CurveSetView(
            set.id,
            LocalDate.parse(set.asOf),
            set.provenance,
            set.curves.map { c ->
                MarketCurve(c.index, c.currency, c.pillars.map { CurvePillar(LocalDate.parse(it.date), it.zeroRate) })
            },
        )
        val q = QuotePricer.quote(view, "SIMBK-A", "CZK", 30, 5)
        assertThat(q.curveIndex).isEqualTo("CZEONIA")
        assertThat(q.mid).isEqualByComparingTo("3.4570")
    }

    @Test
    @PactTestFor(pactMethod = "anonymousListRefusedPact")
    fun `an anonymous read of the curve sets is refused 401`(mockServer: MockServer) {
        given().baseUri(
            mockServer.getUrl(),
        ).queryParam(limitParam, 1).get(listPath).then().statusCode(HTTP_UNAUTHORIZED)
    }

    private companion object {
        const val STATE = "a curve set with a CZEONIA curve exists"
        const val NO_IDENTITY = "no valid identity is presented"
        const val HTTP_UNAUTHORIZED = 401
        const val EXAMPLE_ID = "0191c0de-0000-7000-8000-00000000c5e7"
        const val AS_OF = "2026-09-21"
        const val PILLAR_DATE = "2026-09-22"
        const val ZERO_RATE = 0.035

        val listPath: String = RiskCurveRestClient::class.java.getAnnotation(Path::class.java).value
        val getPath: String = listOf(
            listPath,
            RiskCurveRestClient::class.java.getDeclaredMethod(
                "get",
                String::class.java,
            ).getAnnotation(Path::class.java).value,
        )
            .joinToString("/") { it.trim('/') }.let { "/$it" }
        val limitParam: String = RiskCurveRestClient::class.java
            .getDeclaredMethod("list", Int::class.javaPrimitiveType)
            .parameterAnnotations[0].filterIsInstance<QueryParam>().single().value
    }
}
