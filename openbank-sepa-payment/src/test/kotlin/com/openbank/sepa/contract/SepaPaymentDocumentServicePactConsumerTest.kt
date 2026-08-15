// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonArrayMinLike
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.sepa.infrastructure.client.DocumentServiceClient
import com.openbank.sepa.infrastructure.client.DocumentTemplateClientResponse
import com.openbank.sepa.infrastructure.client.PreviewTemplateClientRequest
import com.openbank.sepa.infrastructure.client.PreviewTemplateClientResponse
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import jakarta.ws.rs.QueryParam
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Consumer-driven contract for the two `openbank-document-service` calls that render a SEPA
 * payment confirmation (ADR-0248 #3): `GET /api/v1/documents/templates` to resolve the PUBLISHED
 * `bodyHtml` for a template code, then `POST /api/v1/documents/templates/preview` to merge the
 * payment's data into it. Both are driven by
 * [com.openbank.sepa.infrastructure.client.DocumentPreviewAdapter] on the synchronous,
 * customer-facing "download my confirmation" click, and the adapter is fail-CLOSED — a
 * document-service contract break surfaces to the customer as a failed download, immediately.
 *
 * **Why this test exists.** Before it, ZERO of the 42 committed pacts named document-service as a
 * provider, while three merged services held live REST clients calling it. sepa-payment's only
 * cover was `DocumentServiceWireMockResource` — a CONSUMER-AUTHORED fixture that hard-codes the
 * very paths and payloads the client sends, so it agrees with the client by construction and can
 * never disagree with the provider. That is exactly the configuration that let finrep-service ship
 * a call to `/api/v1/ledger/trial-balance`, a ledger route that has never existed, with green unit
 * tests (#2269). A stub cannot falsify the client it was written from; only the provider can.
 *
 * **The two sides are deliberately sourced differently.** Each interaction declares the contract as
 * a LITERAL ([TEMPLATES_PATH], [PREVIEW_PATH], [LIMIT_PARAM]); the REQUESTS are issued against
 * [templatesPath] / [previewPath] / [limitQueryParam], derived by reflection from
 * [DocumentServiceClient]'s own `@Path` and `@QueryParam` annotations. That asymmetry IS the test:
 * the pact mock server fails when the production client is annotated with anything other than the
 * contract path, and the provider-side replay of the committed pact fails if document-service ever
 * moves the endpoint. Deriving BOTH sides from the annotation is DRY and vacuous — expectation and
 * request move together, so the test stays green against a client pointing anywhere at all (#2290).
 *
 * The provider replay lives in `DocumentPactProviderVerificationTest` (openbank-document-service),
 * `@PactFolder("../pacts")` and ungated, so it runs on every pull request — a `@PactBroker` class
 * would not: the PR lane blanks `PACT_BROKER_URL` (ADR-0056), so its
 * `@EnabledIfSystemProperty(pactbroker.url)` gate skips and the contract would be replayed only
 * after the merge (#2327).
 *
 * IMPORTANT — regenerate on change: if this test's `@Pact` methods change, re-run
 * (`./gradlew :openbank-sepa-payment:test --tests "*SepaPaymentDocumentServicePactConsumerTest*"`)
 * and commit the updated `pacts/openbank-sepa-payment-openbank-document-service.json` in the same
 * PR — an un-regenerated pact silently verifies the OLD contract on the provider side.
 * `pact-drift-check.yml` enforces this over a scope derived from the `@Pact` annotations.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-document-service", pactVersion = PactSpecVersion.V3)
class SepaPaymentDocumentServicePactConsumerTest {

    private val json = jacksonObjectMapper()

    @Pact(consumer = "openbank-sepa-payment", provider = "openbank-document-service")
    fun listTemplatesPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("the canonical document templates are seeded and published")
        .uponReceiving("GET the document templates to resolve a PUBLISHED payment-confirmation body")
        .path(TEMPLATES_PATH)
        .query("$LIMIT_PARAM=$TEMPLATE_LIST_LIMIT")
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            // stringType on every element field, DELIBERATELY (issue #2425): the template list is
            // HETEROGENEOUS — document-service seeds six templates across three codes and two
            // locales, in DRAFT / PUBLISHED / RETIRED states. Pinning `code` or `status` to a value
            // would assert "every template is POTVRZENI_O_PLATBE_EN, and every one is PUBLISHED",
            // a claim the contract does not make and the provider's own seed data falsifies on the
            // first run. What the consumer actually needs from the contract is that the three
            // fields it filters and reads on are PRESENT and are strings — the selection itself is
            // the adapter's business, covered by DocumentPreviewAdapterTest.
            newJsonArrayMinLike(1) { a ->
                a.`object` { t ->
                    t.stringType("code", "POTVRZENI_O_PLATBE_EN")
                    t.stringType("status", "PUBLISHED")
                    t.stringType("bodyHtml", "<p>{{document.status}}</p>")
                    t.stringType("locale", "en")
                }
            }.build(),
        )
        .toPact()

    @Pact(consumer = "openbank-sepa-payment", provider = "openbank-document-service")
    fun previewTemplatePact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("the template preview renderer is available")
        .uponReceiving("POST a template body plus payment data to render the confirmation HTML")
        .path(PREVIEW_PATH)
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(json.writeValueAsString(PREVIEW_REQUEST))
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            // stringType, NOT stringValue: `renderedHtml` is the Handlebars OUTPUT, so pinning it
            // would make every whitespace or letterhead change in the renderer a contract break.
            // The claim under contract is that a non-persisting preview answers 200 with a
            // `renderedHtml` string — which is the field, and the only field, the adapter returns.
            newJsonBody { o -> o.stringType("renderedHtml", "<p>COMPLETED</p>") }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "listTemplatesPact")
    fun `the template list carries the code, status and body the confirmation adapter reads`(mockServer: MockServer) {
        // Guards the reflection helpers themselves: were they ever to return an empty or partial
        // path, the interaction and the request would still agree with each other and both pact
        // tests would pass against a contract that pins nothing.
        assertThat(templatesPath).isEqualTo(TEMPLATES_PATH)
        assertThat(limitQueryParam).isEqualTo(LIMIT_PARAM)

        val body = given()
            .baseUri(mockServer.getUrl())
            .accept("application/json")
            .queryParam(limitQueryParam, TEMPLATE_LIST_LIMIT)
            .get(templatesPath)
            .then()
            .statusCode(200)
            .extract().body().asString()

        val templates: List<DocumentTemplateClientResponse> = json.readValue(body)
        assertThat(templates).isNotEmpty()
        // All three fields DocumentPreviewAdapter touches must deserialize into the client DTO:
        // it filters on `code` + `status` and then sends `bodyHtml` on to the preview call.
        val template = templates.first()
        assertThat(template.code).isNotBlank()
        assertThat(template.status).isNotBlank()
        assertThat(template.bodyHtml).isNotBlank()
    }

    @Test
    @PactTestFor(pactMethod = "previewTemplatePact")
    fun `preview returns the rendered confirmation HTML for the template body and payment data`(
        mockServer: MockServer,
    ) {
        assertThat(previewPath).isEqualTo(PREVIEW_PATH)

        val body = given()
            .baseUri(mockServer.getUrl())
            .contentType("application/json")
            .body(json.writeValueAsString(PREVIEW_REQUEST))
            .post(previewPath)
            .then()
            .statusCode(200)
            .extract().body().asString()

        val response: PreviewTemplateClientResponse = json.readValue(body)
        assertThat(response.renderedHtml).isNotBlank()
    }

    private companion object {
        /**
         * The contract, written out LITERALLY on purpose: openbank-document-service serves the
         * bounded template list here (`DocumentResource.listTemplates`, `@Path("/api/v1/documents")`
         * + `@Path("/templates")`).
         *
         * It must NOT be derived from [DocumentServiceClient] like [templatesPath] is. Deriving
         * both sides from the same annotation makes the interaction and the request move together,
         * so the pact mock server always sees exactly what it expects and the test passes against a
         * client pointing anywhere at all. The literal is the fixed point the annotation is
         * measured against.
         */
        const val TEMPLATES_PATH = "/api/v1/documents/templates"

        /** Likewise literal: `DocumentResource.previewTemplate`, the non-persisting render. */
        const val PREVIEW_PATH = "/api/v1/documents/templates/preview"

        /** document-service's query-parameter name for the list bound — likewise literal. */
        const val LIMIT_PARAM = "limit"

        /** Mirrors `DocumentPreviewAdapter.TEMPLATE_LIST_LIMIT`, the bound the adapter sends. */
        const val TEMPLATE_LIST_LIMIT = 200

        /**
         * The exact body the adapter posts: the PUBLISHED template body it just resolved, plus the
         * payment data map `PaymentConfirmationMapper` builds. Kept minimal — the contract is the
         * two-field envelope, not the confirmation's field vocabulary, which is the mapper's own
         * unit-tested business.
         */
        val PREVIEW_REQUEST = PreviewTemplateClientRequest(
            bodyHtml = "<p>{{document.status}}</p>",
            data = mapOf("document" to mapOf("status" to "COMPLETED")),
        )

        private val listTemplatesMethod =
            DocumentServiceClient::class.java.getDeclaredMethod("listTemplates", Int::class.javaPrimitiveType)

        private val previewMethod =
            DocumentServiceClient::class.java.getDeclaredMethod("preview", PreviewTemplateClientRequest::class.java)

        private val interfacePath: String =
            DocumentServiceClient::class.java.getAnnotation(Path::class.java).value

        /**
         * The interface `@Path` alone — `listTemplates` carries no method-level `@Path`. Read from
         * the production client so the contract cannot drift from the code that calls it.
         */
        val templatesPath: String = "/" + interfacePath.trim('/')

        /** Interface `@Path` + method `@Path`, joined the way JAX-RS resolves them. */
        val previewPath: String = listOf(
            interfacePath,
            previewMethod.getAnnotation(Path::class.java).value,
        ).joinToString("/") { it.trim('/') }.let { "/$it" }

        /** The query-parameter name the production client sends the list bound under. */
        val limitQueryParam: String = listTemplatesMethod.parameterAnnotations
            .flatMap { it.toList() }
            .filterIsInstance<QueryParam>()
            .single()
            .value
    }
}
