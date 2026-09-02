// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.interest.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.openbank.interest.infrastructure.client.CatalogEventPageClientResponse
import com.openbank.interest.infrastructure.client.CatalogOfferingClientResponse
import com.openbank.interest.infrastructure.client.CatalogRevisionClientResponse
import com.openbank.interest.infrastructure.client.ProductCatalogClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import jakarta.ws.rs.Path as JaxrsPath

/**
 * Contract for the immutable catalog chain that feeds the interest-rate snapshotter.
 *
 * Literal paths are intentionally different from the reflected production-client request paths:
 * provider replay catches server drift and the local assertion catches a client annotation drift.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-product-catalog", pactVersion = PactSpecVersion.V3)
class ProductCatalogInterestSnapshotPactConsumerTest {
    private val mapper = ObjectMapper().findAndRegisterModules().registerKotlinModule()

    @Pact(consumer = "openbank-interest-service", provider = "openbank-product-catalog")
    fun publishedEventPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(STATE)
        .uponReceiving("GET one published catalog revision event for interest synchronization")
        .path(EVENTS_PATH)
        .query("limit=1")
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith().status(200).headers(mapOf("Content-Type" to "application/json"))
        .body(EVENT_PAGE)
        .toPact()

    @Pact(consumer = "openbank-interest-service", provider = "openbank-product-catalog")
    fun immutableRevisionPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(STATE)
        .uponReceiving("GET the immutable published fixed-rate catalog revision")
        .path("/api/v2/revisions/$REVISION_ID")
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith().status(200).headers(mapOf("Content-Type" to "application/json"))
        .body(REVISION)
        .toPact()

    @Pact(consumer = "openbank-interest-service", provider = "openbank-product-catalog")
    fun offeringPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(STATE)
        .uponReceiving("GET the offering owning the published fixed-rate revision")
        .path("/api/v2/offerings/$OFFERING_ID")
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith().status(200).headers(mapOf("Content-Type" to "application/json"))
        .body(OFFERING)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "publishedEventPact")
    fun `events response deserializes through the production client DTO`(server: MockServer) {
        assertPaths()
        val page = get(server, eventsPath()).let { mapper.readValue(it, CatalogEventPageClientResponse::class.java) }
        assertThat(page.items.single().aggregateId.toString()).isEqualTo(REVISION_ID)
        assertThat(page.nextCursor).isNotBlank()
    }

    @Test
    @PactTestFor(pactMethod = "immutableRevisionPact")
    fun `immutable revision retains exact decimal text`(server: MockServer) {
        assertPaths()
        val revision = get(server, revisionPath()).let {
            mapper.readValue(it, CatalogRevisionClientResponse::class.java)
        }
        assertThat(revision.state).isEqualTo("PUBLISHED")
        assertThat(revision.content.attributes.at("/interest/annualRate").asText())
            .isEqualTo("0.012345678901234567")
    }

    @Test
    @PactTestFor(pactMethod = "offeringPact")
    fun `offering response exposes canonical specification identity`(server: MockServer) {
        assertPaths()
        val offering = get(server, offeringPath()).let {
            mapper.readValue(it, CatalogOfferingClientResponse::class.java)
        }
        assertThat(offering.specificationId.toString()).isEqualTo(SPECIFICATION_ID)
    }

    private fun get(server: MockServer, path: String): String = HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI.create(server.getUrl() + path))
            .header("Accept", "application/json")
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    ).body()

    private fun assertPaths() {
        assertThat(eventsPath()).isEqualTo(EVENTS_PATH + "?limit=1")
        assertThat(revisionPath()).isEqualTo("/api/v2/revisions/$REVISION_ID")
        assertThat(offeringPath()).isEqualTo("/api/v2/offerings/$OFFERING_ID")
    }

    private companion object {
        const val STATE = "a published fixed-rate deposit revision exists for interest synchronization"
        const val SPECIFICATION_ID = "10000000-0000-0000-0000-000000000010"
        const val OFFERING_ID = "20000000-0000-0000-0000-000000000010"
        const val REVISION_ID = "30000000-0000-0000-0000-000000000010"
        const val EVENT_ID = "40000000-0000-0000-0000-000000000010"
        const val EVENTS_PATH = "/api/v2/events"
        const val EVENT_PAGE =
            """{"items":[{"id":"$EVENT_ID","aggregateType":"catalog.revision","aggregateId":"$REVISION_ID","eventType":"com.openbank.catalog.revision_published","schemaVersion":1,"occurredAt":"2027-01-01T00:00:00Z","headers":{},"payload":{}}],"nextCursor":"MQ"}"""
        const val REVISION =
            """{"id":"$REVISION_ID","offeringId":"$OFFERING_ID","number":1,"schemaRef":{"id":"org.openbank.banking.deposit","version":2},"state":"PUBLISHED","content":{"name":{"en":"Interest pact deposit"},"attributes":{"currency":"EUR","productType":"SAVINGS","interest":{"rateType":"FIXED","dayCount":"ACT_365","payoutFrequency":"MONTHLY","annualRate":"0.012345678901234567"}},"prices":[],"eligibility":[],"relationships":[],"documentCodes":[]},"effectiveFrom":"2027-01-01T00:00:00Z","effectiveTo":null,"makerId":"pact-interest-author","checkerId":"pact-interest-checker","reason":"published fixed-rate fixture","contentHash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","createdAt":"2027-01-01T00:00:00Z","updatedAt":"2027-01-01T00:00:00Z","revision":0}"""
        const val OFFERING =
            """{"id":"$OFFERING_ID","specificationId":"$SPECIFICATION_ID","code":"PACT_INTEREST_OFFERING","market":{"brands":[],"countries":[],"channels":[],"segments":[],"locales":[]},"revision":0}"""

        private fun eventsPath(): String {
            val method = ProductCatalogClient::class.java.getMethod("events", String::class.java, Int::class.java)
            return ProductCatalogClient::class.java.getAnnotation(JaxrsPath::class.java).value +
                method.getAnnotation(JaxrsPath::class.java).value + "?limit=1"
        }

        private fun revisionPath(): String {
            val method = ProductCatalogClient::class.java.getMethod(
                "revision",
                java.util.UUID::class.java,
            )
            return (
                ProductCatalogClient::class.java.getAnnotation(JaxrsPath::class.java).value +
                    method.getAnnotation(JaxrsPath::class.java).value
                ).replace("{id}", REVISION_ID)
        }

        private fun offeringPath(): String {
            val method = ProductCatalogClient::class.java.getMethod(
                "offering",
                java.util.UUID::class.java,
            )
            return (
                ProductCatalogClient::class.java.getAnnotation(JaxrsPath::class.java).value +
                    method.getAnnotation(JaxrsPath::class.java).value
                ).replace("{id}", OFFERING_ID)
        }
    }
}
