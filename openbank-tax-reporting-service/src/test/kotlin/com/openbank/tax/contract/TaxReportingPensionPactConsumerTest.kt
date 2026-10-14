// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.contract

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
import com.openbank.tax.application.port.out.ReturnDataUnavailableException
import com.openbank.tax.domain.returns.Periodicity
import com.openbank.tax.domain.returns.ReportingPeriod
import com.openbank.tax.infrastructure.returns.CatalogueParser
import com.openbank.tax.infrastructure.returns.pension.FundPeriodFiguresDto
import com.openbank.tax.infrastructure.returns.pension.ParticipantAggregatesDto
import com.openbank.tax.infrastructure.returns.pension.ParticipantSnapshotDto
import com.openbank.tax.infrastructure.returns.pension.ParticipantSnapshotReceiptDto
import com.openbank.tax.infrastructure.returns.pension.PensionReportingClient
import com.openbank.tax.infrastructure.returns.pension.PensionReportingSources
import com.openbank.tax.infrastructure.returns.pension.PensionReturnDataAdapter
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID

/**
 * Consumer contract: tax-reporting-service -> pension-service
 * `GET /api/v2/pension/reporting/participant-aggregates` (#12425). Replayed by pension-service's
 * `PensionPactFolderProviderVerificationTest` (AS service-account-openbank-tax-reporting) and
 * `PensionReportingNegativeAuthProviderVerificationTest`. Literal expected path, reflected request
 * path; the decoded body goes through the real PSP 31-04 mapping.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = TaxReportingPensionPactConsumerTest.PROVIDER, pactVersion = PactSpecVersion.V3)
class TaxReportingPensionPactConsumerTest {

    private val mapper = jacksonObjectMapper().findAndRegisterModules()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private fun contributions(o: au.com.dius.pact.consumer.dsl.LambdaDslObject) {
        o.numberType("participant", 0)
        o.numberType("employer", 0)
        o.numberType("state", 0)
        o.numberType("transferIn", 0)
        o.numberType("total", 0)
    }

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun aggregatesPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(STATE)
        .uponReceiving("GET participant aggregates for the third quarter of 2026")
        .path(EXPECTED_PATH)
        .query("companyId=$COMPANY&periodStart=2026-07-01&periodEnd=2026-09-30")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.stringValue("periodStart", "2026-07-01")
                o.stringValue("periodEnd", "2026-09-30")
                o.stringType("currency", "CZK")
                o.`object`("participants") { p ->
                    p.integerType("inForce", 0)
                    p.integerType("contributing", 0)
                    p.integerType("pensioners", 0)
                }
                o.`object`("contributionsYtd") { c -> contributions(c) }
                o.`object`("payoutsYtd") { p ->
                    p.numberType("total", 0)
                    p.integerType("cases", 0)
                    p.numberType("taxWithheld", 0)
                }
            }.build(),
        )
        .toPact()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun unauthenticatedPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(NEGATIVE_AUTH_STATE)
        .uponReceiving("GET participant aggregates with no M2M identity is refused")
        .path(EXPECTED_PATH)
        .query("companyId=$COMPANY&periodStart=2026-07-01&periodEnd=2026-09-30")
        .method("GET")
        .willRespondWith()
        .status(401)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "aggregatesPact")
    fun `the aggregates decode but cannot establish PSP 31-04 beneficiary stock`(mockServer: MockServer) {
        assertThat(clientPath()).isEqualTo(EXPECTED_PATH)
        val body = given().baseUri(mockServer.getUrl())
            .queryParam("companyId", COMPANY)
            .queryParam("periodStart", "2026-07-01").queryParam("periodEnd", "2026-09-30")
            .get(clientPath()).then().statusCode(200).extract().asString()
        val aggregates = mapper.readValue<ParticipantAggregatesDto>(body)
        val catalogue = CatalogueParser.parse(mapper, "statutory-returns/cz/pension-cnb.v1.json")
        val definition = catalogue.definition("PSP31-04")!!
        assertThatThrownBy {
            runBlocking {
                PensionReturnDataAdapter(FixedSources(aggregates), NoCorporateFacts, NoCompanyBooks)
                    .fetch(
                        catalogue,
                        definition,
                        COMPANY,
                        ReportingPeriod(Periodicity.QUARTER, LocalDate.parse("2026-09-30")),
                    )
            }
        }.isInstanceOf(ReturnDataUnavailableException::class.java)
            .hasMessageContaining("verified as-of beneficiary source")
    }

    @Test
    @PactTestFor(pactMethod = "unauthenticatedPact")
    fun `a read with no identity is refused with 401`(mockServer: MockServer) {
        given().baseUri(mockServer.getUrl())
            .queryParam("companyId", COMPANY)
            .queryParam("periodStart", "2026-07-01").queryParam("periodEnd", "2026-09-30")
            .get(clientPath()).then().statusCode(401)
    }

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun capturePact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(POST_STATE)
        .uponReceiving("tax captures a March 2010 participant source under its own service token")
        .path(CAPTURE_PATH)
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json", "Idempotency-Key" to IDEMPOTENCY_KEY))
        .body(
            """{"companyId":"$COMPANY","periodStart":"2010-03-01","periodEnd":"2010-03-31"}""",
        )
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.uuid("snapshotId")
                o.stringValue("companyId", COMPANY)
                o.stringValue("periodStart", "2010-03-01")
                o.stringValue("periodEnd", "2010-03-31")
                o.integerType("revision", 1)
                o.stringValue("payloadSchemaVersion", SCHEMA)
                o.stringMatcher("sha256", "[0-9a-f]{64}", "a".repeat(64))
            }.build(),
        )
        .toPact()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun immutableGetPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(GET_STATE)
        .uponReceiving("tax reads the exact March 2009 immutable participant source by ID and company")
        .path("$CAPTURE_PATH/$SNAPSHOT_ID")
        .query("companyId=$COMPANY")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.`object`("receipt") { r ->
                    r.stringValue("snapshotId", SNAPSHOT_ID)
                    r.stringValue("companyId", COMPANY)
                    r.stringValue("periodStart", "2009-01-01")
                    r.stringValue("periodEnd", "2009-03-31")
                    r.integerType("revision", 1)
                    r.stringValue("payloadSchemaVersion", SCHEMA)
                    r.stringMatcher("sha256", "[0-9a-f]{64}", EXAMPLE_SHA)
                }
                o.stringType("payloadJson", EXAMPLE_PAYLOAD)
            }.build(),
        )
        .toPact()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun noIdentityPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(NEGATIVE_AUTH_STATE)
        .uponReceiving("participant source capture without a service token is refused")
        .path(CAPTURE_PATH)
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json", "Idempotency-Key" to IDEMPOTENCY_KEY))
        .body(
            """{"companyId":"$COMPANY","periodStart":"2010-03-01","periodEnd":"2010-03-31"}""",
        )
        .willRespondWith()
        .status(401)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "capturePact")
    fun `capture receipt decodes with the outbound client DTO`(mockServer: MockServer) {
        assertThat(snapshotClientPath("captureParticipantSnapshot")).isEqualTo(CAPTURE_PATH)
        val body = given().baseUri(mockServer.getUrl()).contentType("application/json")
            .header("Idempotency-Key", IDEMPOTENCY_KEY)
            .body("""{"companyId":"$COMPANY","periodStart":"2010-03-01","periodEnd":"2010-03-31"}""")
            .post(CAPTURE_PATH).then().statusCode(200).extract().asString()
        val receipt = mapper.readValue(body, ParticipantSnapshotReceiptDto::class.java)
        assertThat(receipt.companyId).isEqualTo(UUID.fromString(COMPANY))
        assertThat(receipt.payloadSchemaVersion).isEqualTo(SCHEMA)
    }

    @Test
    @PactTestFor(pactMethod = "immutableGetPact")
    fun `immutable payload DTO retains its exact string bytes and checksum`(mockServer: MockServer) {
        assertThat(snapshotClientPath("participantSnapshot")).isEqualTo("$CAPTURE_PATH/{snapshotId}")
        val body = given().baseUri(mockServer.getUrl()).queryParam("companyId", COMPANY)
            .get("$CAPTURE_PATH/$SNAPSHOT_ID").then().statusCode(200).extract().asString()
        val snapshot = mapper.readValue(body, ParticipantSnapshotDto::class.java)
        val actualSha = MessageDigest.getInstance("SHA-256")
            .digest(snapshot.payloadJson.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        assertThat(actualSha).isEqualTo(snapshot.receipt.sha256)
        assertThat(snapshot.receipt.snapshotId).isEqualTo(UUID.fromString(SNAPSHOT_ID))
        val aggregate = mapper.readValue(snapshot.payloadJson, ParticipantAggregatesDto::class.java)
        assertThat(aggregate.currency).isEqualTo("CZK")
    }

    @Test
    @PactTestFor(pactMethod = "noIdentityPact")
    fun `capture with no identity is refused`(mockServer: MockServer) {
        given().baseUri(mockServer.getUrl()).contentType("application/json")
            .header("Idempotency-Key", IDEMPOTENCY_KEY)
            .body("""{"companyId":"$COMPANY","periodStart":"2010-03-01","periodEnd":"2010-03-31"}""")
            .post(CAPTURE_PATH).then().statusCode(401)
    }

    private fun snapshotClientPath(method: String): String {
        val base = PensionReportingClient::class.java.getAnnotation(Path::class.java).value
        val sub = PensionReportingClient::class.java.methods.single { it.name == method }
            .getAnnotation(Path::class.java).value
        return base + sub
    }

    private class FixedSources(private val aggregates: ParticipantAggregatesDto) : PensionReportingSources {
        override suspend fun fund(fundId: UUID, from: LocalDate, to: LocalDate): FundPeriodFiguresDto =
            error("not used by PSP 31-04")

        override suspend fun participants(companyId: UUID, from: LocalDate, to: LocalDate) = aggregates
    }

    companion object {
        const val CONSUMER = "openbank-tax-reporting-service"
        const val PROVIDER = "openbank-pension-service"
        const val POST_STATE = "pension participant technical source capture is permitted for March 2010"
        const val GET_STATE = "pension participant technical source snapshot exists for March 2009"
        const val CAPTURE_PATH = "/api/v2/pension/reporting/participant-snapshots"
        const val COMPANY = "00000000-0000-4000-8000-000000000001"
        const val SNAPSHOT_ID = "d45ca8df-f9dc-4573-bb7b-513219f33a96"
        const val IDEMPOTENCY_KEY = "tax-participant-close-pact-2010"
        const val SCHEMA = "participant-period-aggregates/2.0"
        private val EXAMPLE_PAYLOAD = listOf(
            """{"periodStart":"2009-01-01","periodEnd":"2009-03-31","currency":"CZK",""",
            """"participants":{"inForce":0,"contributing":0,"pensioners":0},""",
            """"contributionsYtd":{"participant":0,"employer":0,"state":0,"transferIn":0,"total":0},""",
            """"payoutsYtd":{"total":0,"cases":0,"taxWithheld":0}}""",
        ).joinToString("")
        private val EXAMPLE_SHA = MessageDigest.getInstance("SHA-256")
            .digest(EXAMPLE_PAYLOAD.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        const val NEGATIVE_AUTH_STATE = "no valid M2M identity is presented"

        /** Must match PensionPactFolderProviderVerificationTest (openbank-pension-service). */
        const val STATE = "pension participant activity may exist for the third quarter of 2026"

        /** LITERAL, retyped from pension-service's ParticipantReportingResource — never derived from the client. */
        const val EXPECTED_PATH = "/api/v2/pension/reporting/participant-aggregates"

        fun clientPath(): String {
            val base = PensionReportingClient::class.java.getAnnotation(Path::class.java).value
            val sub = PensionReportingClient::class.java.methods.single { it.name == "participantAggregates" }
                .getAnnotation(Path::class.java).value
            return base + sub
        }
    }
}
