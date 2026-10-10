// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonArray
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonArrayMinLike
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.pension.domain.model.ProductLine
import com.openbank.pension.infrastructure.catalog.StrategyCatalogRead
import com.openbank.pension.infrastructure.catalog.StrategyCatalogRestClient
import com.openbank.pension.infrastructure.catalog.StrategyInstrumentCatalogResolver
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/** Published strategy evidence consumed by pension before accepting a DIP selection. */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-product-catalog", pactVersion = PactSpecVersion.V3)
class PensionCatalogPactConsumerTest {
    private val mapper = jacksonObjectMapper()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun offeringsPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(STATE)
        .uponReceiving("GET strategy catalog offerings")
        .path("/api/v2/offerings")
        .method("GET")
        .willRespondWith()
        .status(200)
        .matchHeader("Content-Type", "application/json.*", "application/json")
        .body(newJsonArrayMinLike(1) { it.`object` { offering -> offering.uuid("id") } }.build())
        .toPact()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun publishedRevisionPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(STATE)
        .uponReceiving("GET effective published DIP strategy revision")
        .path("/api/v2/products/$OFFERING_ID")
        .query("effectiveAt=$AT")
        .method("GET")
        .willRespondWith()
        .status(200)
        .matchHeader("Content-Type", "application/json.*", "application/json")
        .body(
            newJsonBody { revision ->
                revision.uuid("id", UUID.fromString(REVISION_ID))
                revision.uuid("offeringId", UUID.fromString(OFFERING_ID))
                revision.numberType("number", 1)
                revision.stringValue("state", "PUBLISHED")
                revision.stringMatcher("pensionApprovalDigest", "[0-9a-f]{64}", DIGEST)
                revision.stringValue("makerId", "pact-pension-maker")
                revision.stringValue("checkerId", "pact-pension-checker")
                revision.stringType("reason", "independently reviewed DIP strategy fixture")
                revision.stringMatcher("contentHash", "[0-9a-f]{64}", "a".repeat(64))
                revision.stringType("effectiveFrom", "2026-10-01T00:00:00Z")
                revision.stringType("effectiveTo", "2026-11-01T00:00:00Z")
                revision.`object`("schemaRef") { schema ->
                    schema.stringValue("id", "org.openbank.retirement.pension-savings")
                    schema.numberType("version", 2)
                }
                revision.`object`("content") { content ->
                    content.`object`("attributes") { attributes ->
                        attributes.stringValue("productLine", "DIP")
                        attributes.stringValue("jurisdictionPackId", "CZ/DIP")
                        attributes.stringValue("fundStrategy", "DYNAMIC")
                        attributes.stringValue("reviewStatus", "LEGAL_AND_COMMERCIAL_REVIEWED")
                        attributes.array("instrumentClasses") { classes ->
                            classes.stringValue("BOND_FUNDS")
                            classes.stringValue("EQUITY_FUNDS")
                        }
                    }
                }
            }.build(),
        )
        .toPact()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun approvalsPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(STATE)
        .uponReceiving("GET independent legal and product approvals for the exact revision")
        .path("/api/v2/offerings/$OFFERING_ID/revisions/$REVISION_ID/pension-approvals")
        .method("GET")
        .willRespondWith()
        .status(200)
        .matchHeader("Content-Type", "application/json.*", "application/json")
        .body(
            newJsonArray { approvals ->
                approvals.`object` { legal ->
                    legal.stringValue("role", "LEGAL_COUNSEL")
                    legal.stringMatcher("digest", "[0-9a-f]{64}", DIGEST)
                    legal.stringType("approvedAt", "2026-10-08T10:00:00Z")
                }
                approvals.`object` { product ->
                    product.stringValue("role", "PRODUCT_OWNER")
                    product.stringMatcher("digest", "[0-9a-f]{64}", DIGEST)
                    product.stringType("approvedAt", "2026-10-08T11:00:00Z")
                }
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "offeringsPact")
    fun `catalog discovery supplies typed offering IDs`(mockServer: MockServer) {
        val path = StrategyCatalogRestClient::class.java.getAnnotation(Path::class.java).value +
            StrategyCatalogRestClient::class.java.methods.single { it.name == "offerings" }
                .getAnnotation(Path::class.java).value
        assertThat(path).isEqualTo("/api/v2/offerings")
        val response = given().baseUri(mockServer.getUrl()).get(path).then().statusCode(200).extract().asString()
        assertThat(mapper.readTree(response).first().path("id").asText()).isNotBlank()
    }

    @Test
    @PactTestFor(pactMethod = "publishedRevisionPact")
    fun `effective revision includes the strategy mapping and approval digest`(mockServer: MockServer) {
        val response = given().baseUri(mockServer.getUrl()).queryParam("effectiveAt", AT)
            .get("/api/v2/products/$OFFERING_ID").then().statusCode(200).extract().asString()
        val revision = mapper.readTree(response)
        assertThat(revision.path("id").asText()).isEqualTo(REVISION_ID)
        assertThat(revision.path("pensionApprovalDigest").asText()).isEqualTo(DIGEST)
        assertThat(revision.path("content").path("attributes").path("instrumentClasses").size()).isEqualTo(2)
        val legalApproval = """{"role":"LEGAL_COUNSEL","digest":"$DIGEST","approvedAt":"2026-10-08T10:00:00Z"}"""
        val productApproval = """{"role":"PRODUCT_OWNER","digest":"$DIGEST","approvedAt":"2026-10-08T11:00:00Z"}"""
        val resolver = StrategyInstrumentCatalogResolver(
            object : StrategyCatalogRead {
                override suspend fun offerings() = listOf(mapper.readTree("""{"id":"$OFFERING_ID"}"""))
                override suspend fun published(offeringId: UUID, at: OffsetDateTime) = revision
                override suspend fun pensionApprovals(offeringId: UUID, revisionId: UUID) = listOf(
                    mapper.readTree(legalApproval),
                    mapper.readTree(productApproval),
                )
            },
        )
        assertThat(
            runBlocking { resolver.effectivePublished("CZ", ProductLine.DIP, "DYNAMIC", Instant.parse(AT)) },
        ).hasSize(1)
    }

    @Test
    @PactTestFor(pactMethod = "approvalsPact")
    fun `exact revision has two distinct role approvals`(mockServer: MockServer) {
        val response = given().baseUri(mockServer.getUrl())
            .get("/api/v2/offerings/$OFFERING_ID/revisions/$REVISION_ID/pension-approvals")
            .then().statusCode(200).extract().asString()
        val approvals = mapper.readTree(response)
        assertThat(approvals.map { it.path("role").asText() })
            .containsExactly("LEGAL_COUNSEL", "PRODUCT_OWNER")
        assertThat(approvals.map { it.path("digest").asText() }).containsOnly(DIGEST)
    }

    private companion object {
        const val CONSUMER = "openbank-pension-service"
        const val PROVIDER = "openbank-product-catalog"
        const val STATE = "a published DIP strategy revision has separate legal and product approvals"
        const val OFFERING_ID = "20000000-0000-0000-0000-000000000084"
        const val REVISION_ID = "30000000-0000-0000-0000-000000000084"
        const val AT = "2026-10-09T10:00:00Z"
        val DIGEST = "b".repeat(64)
    }
}
