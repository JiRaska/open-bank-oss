// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import io.restassured.RestAssured.given
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.UUID

/**
 * Consumer-owned author-to-publish seam. Admin UI executes the committed interactions through its
 * generated production client; this generator pins the independent provider expectations.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-product-catalog", pactVersion = PactSpecVersion.V3)
class CatalogV2PactConsumerTest {
    @Suppress("LongMethod")
    @Pact(consumer = "openbank-admin-ui", provider = "openbank-product-catalog")
    fun productStudioAuthorPublish(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("trusted insurance term-life schema version 1 is installed")
        .uponReceiving("Product Studio loads an exact trusted schema version")
        .path("/api/v2/product-types/org.openbank.insurance.term-life/versions/1")
        .method("GET")
        .headers(ACCEPT_JSON)
        .willRespondWith()
        .status(200)
        .headers(CONTENT_JSON)
        .body(schemaResponse())
        .given("Product Studio specification code is available")
        .uponReceiving("Product Studio creates a schema-pinned specification")
        .path("/api/v2/specifications")
        .method("POST")
        .headers(JSON_HEADERS)
        .body(SPECIFICATION_REQUEST)
        .willRespondWith()
        .status(201)
        .headers(CONTENT_JSON)
        .body(specificationResponse())
        .given("Product Studio offering prerequisite specification exists")
        .uponReceiving("Product Studio creates a contextual offering")
        .path("/api/v2/offerings")
        .method("POST")
        .headers(JSON_HEADERS)
        .body(OFFERING_REQUEST)
        .willRespondWith()
        .status(201)
        .headers(CONTENT_JSON)
        .body(offeringResponse())
        .given("Product Studio draft prerequisite offering exists")
        .uponReceiving("Product Studio authors a schema-valid draft")
        .path("/api/v2/offerings/$DRAFT_OFFERING_ID/revisions")
        .method("POST")
        .headers(JSON_HEADERS)
        .body(REVISION_REQUEST)
        .willRespondWith()
        .status(201)
        .headers(CONTENT_JSON)
        .body(revisionResponse("DRAFT"))
        .given("Product Studio editable draft exists")
        .uponReceiving("Product Studio replaces a draft with a strong precondition")
        .path("/api/v2/offerings/$UPDATE_OFFERING_ID/revisions/$UPDATE_REVISION_ID")
        .method("PUT")
        .headers(JSON_HEADERS + ("If-Match" to "\"0\""))
        .body(REVISION_REQUEST)
        .willRespondWith()
        .status(200)
        .headers(CONTENT_JSON)
        .body(revisionResponse("DRAFT"))
        .given("Product Studio independently checkable draft exists")
        .uponReceiving("Product Studio publishes through an independent checker")
        .path("/api/v2/offerings/$PUBLISH_OFFERING_ID/revisions/$PUBLISH_REVISION_ID/publish")
        .method("POST")
        .headers(JSON_HEADERS + ("If-Match" to "\"0\""))
        .body(PUBLISH_REQUEST)
        .willRespondWith()
        .status(200)
        .headers(CONTENT_JSON)
        .body(revisionResponse("PUBLISHED"))
        .toPact()

    @Test
    @PactTestFor(pactMethod = "productStudioAuthorPublish")
    fun `Product Studio contract covers authoring through independent publication`(mockServer: MockServer) {
        get(mockServer, "/api/v2/product-types/org.openbank.insurance.term-life/versions/1")
        post(mockServer, "/api/v2/specifications", SPECIFICATION_REQUEST)
        post(mockServer, "/api/v2/offerings", OFFERING_REQUEST)
        post(mockServer, "/api/v2/offerings/$DRAFT_OFFERING_ID/revisions", REVISION_REQUEST)
        put(
            mockServer,
            "/api/v2/offerings/$UPDATE_OFFERING_ID/revisions/$UPDATE_REVISION_ID",
            REVISION_REQUEST,
        )
        post(
            mockServer,
            "/api/v2/offerings/$PUBLISH_OFFERING_ID/revisions/$PUBLISH_REVISION_ID/publish",
            PUBLISH_REQUEST,
            true,
        )
    }

    private fun get(server: MockServer, path: String) {
        given().baseUri(server.getUrl()).accept("application/json").get(path).then().statusCode(200)
    }

    private fun post(server: MockServer, path: String, body: String, ifMatch: Boolean = false) {
        val request = given().baseUri(server.getUrl()).accept("application/json").contentType("application/json")
        if (ifMatch) request.header("If-Match", "\"0\"")
        request.body(body).post(path).then().statusCode(if (ifMatch) 200 else 201)
    }

    private fun put(server: MockServer, path: String, body: String) {
        given().baseUri(server.getUrl()).accept("application/json").contentType("application/json")
            .header("If-Match", "\"0\"").body(body).put(path).then().statusCode(200)
    }

    private fun schemaResponse() = newJsonBody { body ->
        body.stringValue("id", SCHEMA_ID)
        body.integerType("version", 1)
        body.stringType("sha256", "0".repeat(64))
        body.`object`("document") { it.stringValue("\$schema", "https://json-schema.org/draft/2020-12/schema") }
    }.build()

    private fun specificationResponse() = newJsonBody { body ->
        body.uuid("id")
        body.stringValue("code", "PACT_STUDIO_SPEC")
        body.`object`("schemaRef") { ref ->
            ref.stringValue("id", SCHEMA_ID)
            ref.integerType("version", 1)
        }
    }.build()

    private fun offeringResponse() = newJsonBody { body ->
        body.uuid("id")
        body.uuid("specificationId", UUID.fromString(OFFERING_SPECIFICATION_ID))
        body.stringValue("code", "PACT_STUDIO_OFFER")
    }.build()

    private fun revisionResponse(state: String) = newJsonBody { body ->
        body.uuid("id")
        body.stringValue("state", state)
        body.integerType("revision", if (state == "PUBLISHED") 1 else 0)
    }.build()

    private companion object {
        const val SCHEMA_ID = "org.openbank.insurance.term-life"
        const val OFFERING_SPECIFICATION_ID = "10000000-0000-0000-0000-000000000001"
        const val DRAFT_OFFERING_ID = "20000000-0000-0000-0000-000000000001"
        const val UPDATE_OFFERING_ID = "20000000-0000-0000-0000-000000000002"
        const val UPDATE_REVISION_ID = "30000000-0000-0000-0000-000000000001"
        const val PUBLISH_OFFERING_ID = "20000000-0000-0000-0000-000000000003"
        const val PUBLISH_REVISION_ID = "30000000-0000-0000-0000-000000000002"
        val ACCEPT_JSON = mapOf("Accept" to "application/json")
        val CONTENT_JSON = mapOf("Content-Type" to "application/json")
        val JSON_HEADERS = ACCEPT_JSON + CONTENT_JSON
        const val SPECIFICATION_REQUEST =
            """{"code":"PACT_STUDIO_SPEC","schemaRef":{"id":"$SCHEMA_ID","version":1}}"""
        const val OFFERING_REQUEST =
            """{"specificationId":"$OFFERING_SPECIFICATION_ID","code":"PACT_STUDIO_OFFER","market":{"countries":["CZ"],"channels":["WEB"],"locales":["en"]}}"""
        const val REVISION_REQUEST =
            """{"schemaRef":{"id":"$SCHEMA_ID","version":1},"name":{"en":"Term life"},"attributes":{"coverage":{"amount":"100000","currency":"EUR"},"termYears":20,"premiumModel":"CALCULATED"},"prices":[],"eligibility":[],"relationships":[],"documentCodes":[]}"""
        const val PUBLISH_REQUEST = """{"reason":"independent commercial approval"}"""
    }
}
