// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.pensionfund.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.pensionfund.infrastructure.catalog.FundRevisionCatalogClient
import com.openbank.pensionfund.infrastructure.catalog.FundRevisionTerms
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import jakarta.ws.rs.Path as JaxrsPath

/**
 * ADR-0341: the one catalog read a fund makes - an immutable published revision by id - and the fee
 * it pins. The expected path is a LITERAL; only the request goes through the production client's
 * annotation, so a client pointing at a route the catalog does not serve fails the provider replay.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-product-catalog", pactVersion = PactSpecVersion.V3)
class ProductCatalogFundTermsPactConsumerTest {
    private val mapper = ObjectMapper()

    @Pact(consumer = "openbank-pension-fund-service", provider = "openbank-product-catalog")
    fun publishedRevisionPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(STATE)
        .uponReceiving("GET the published pension-savings revision a fund pins")
        .path("/api/v2/revisions/$REVISION_ID")
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith().status(200).headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { revision ->
                revision.stringValue("id", REVISION_ID)
                revision.stringValue("state", "PUBLISHED")
                revision.`object`("schemaRef") { schema ->
                    schema.stringValue("id", "org.openbank.retirement.pension-savings")
                    schema.integerType("version", 2)
                }
                revision.`object`("content") { content ->
                    content.`object`("attributes") { attributes ->
                        attributes.`object`("feeSchedule") { fee ->
                            fee.stringValue("managementFeeAnnualPercent", "0.80")
                            fee.stringValue("performanceFeePercent", "0")
                        }
                    }
                }
            }.build(),
        )
        .toPact()

    @Pact(consumer = "openbank-pension-fund-service", provider = "openbank-product-catalog")
    fun unknownRevisionPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .uponReceiving("GET a revision id the catalog does not know")
        .path("/api/v2/revisions/$UNKNOWN_ID")
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith().status(404)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "publishedRevisionPact")
    fun `published revision yields the fee fraction the fund pins`(server: MockServer) {
        assertThat(clientPath(REVISION_ID)).isEqualTo("/api/v2/revisions/$REVISION_ID")
        val body = get(server, clientPath(REVISION_ID))
        val terms = FundRevisionTerms.parse(UUID.fromString(REVISION_ID), mapper.readTree(body.second))
        assertThat(body.first).isEqualTo(200)
        assertThat(terms.managementFeeRate).isEqualByComparingTo(BigDecimal("0.008"))
    }

    @Test
    @PactTestFor(pactMethod = "unknownRevisionPact")
    fun `unknown revision is a 404 the adapter reports as not a catalog revision`(server: MockServer) {
        assertThat(get(server, clientPath(UNKNOWN_ID)).first).isEqualTo(404)
    }

    /** The production client's own `@Path` composition, with the id substituted. */
    private fun clientPath(id: String): String {
        val base = FundRevisionCatalogClient::class.java.getAnnotation(JaxrsPath::class.java).value
        val method = FundRevisionCatalogClient::class.java.methods.single { it.name == "revision" }
        return base + method.getAnnotation(JaxrsPath::class.java).value.replace("{id}", id)
    }

    private fun get(server: MockServer, path: String): Pair<Int, String> {
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(
                URI.create(server.getUrl() + path),
            ).header("Accept", "application/json").GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        return response.statusCode() to response.body()
    }

    private companion object {
        const val STATE = "a published pension-savings revision with a 0.80 percent management fee exists"
        const val REVISION_ID = "30000000-0000-0000-0000-000000000341"
        const val UNKNOWN_ID = "30000000-0000-0000-0000-000000000fff"
    }
}
