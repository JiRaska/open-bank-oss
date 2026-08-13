// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.contract

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
import com.openbank.domestic.infrastructure.client.DocumentServiceClient
import com.openbank.domestic.infrastructure.client.DocumentTemplateSummary
import com.openbank.domestic.infrastructure.client.PreviewTemplateRequest
import com.openbank.domestic.infrastructure.client.PreviewTemplateResponse
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import jakarta.ws.rs.QueryParam
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Consumer-driven contract for the two `openbank-document-service` calls that render a DOMESTIC
 * payment confirmation (ADR-0248 #3): `GET /api/v1/documents/templates` to resolve the PUBLISHED
 * `bodyHtml` for a template code, then `POST /api/v1/documents/templates/preview` to merge the
 * payment's data into it. Both are driven by
 * [com.openbank.domestic.infrastructure.client.PaymentConfirmationRenderAdapter], synchronously, on
 * the customer-facing confirmation download; the adapter is fail-CLOSED (it raises
 * `PaymentConfirmationRenderException`), so a document-service contract break surfaces to the
 * customer as a failed download immediately.
 *
 * **Why this test exists.** #4299 covered `openbank-sepa-payment`'s copy of this same two-call
 * flow. Three merged services hold live synchronous REST clients against document-service and this
 * is the second of them; the third is `openbank-statement-service`
 * (`StatementDocumentServicePactConsumerTest`). Until now this module's only cover for these calls
 * was consumer-authored mocks, which hard-code the very paths the client sends and therefore agree
 * with the client by construction — the configuration that let finrep-service ship a call to
 * `/api/v1/ledger/trial-balance`, a ledger route that has never existed, with green unit tests
 * (#2269).
 *
 * **The two sides are deliberately sourced differently.** Each interaction declares the contract as
 * a LITERAL ([TEMPLATES_PATH], [PREVIEW_PATH], [LIMIT_PARAM]); the REQUESTS are issued against
 * [templatesPath] / [previewPath] / [limitQueryParam], derived by reflection from
 * [DocumentServiceClient]'s own `@Path` and `@QueryParam` annotations. That asymmetry IS the test:
 * the pact mock server fails when the production client is annotated with anything other than the
 * contract path. Deriving BOTH sides from the annotation is DRY and vacuous — expectation and
 * request move together, so the test stays green against a client pointing anywhere at all (#2290).
 *
 * Note the consumer half can only catch a client whose annotation disagrees with the literal here.
 * A path this repo has agreed on but the PROVIDER does not serve is caught only by replaying the
 * committed pact against the real service — `DocumentPactProviderVerificationTest`
 * (openbank-document-service), `@PactFolder("../pacts")` and ungated, so it runs on every pull
 * request. A `@PactBroker` class would not: the PR lane blanks `PACT_BROKER_URL` (ADR-0056), so its
 * `@EnabledIfSystemProperty(pactbroker.url)` gate skips and the contract would be replayed only
 * after the merge (#2327).
 *
 * IMPORTANT — regenerate on change: if this test's `@Pact` methods change, re-run
 * (`./gradlew :openbank-domestic-payment:test --tests "*DomesticPaymentDocumentServicePactConsumerTest*"`)
 * and commit the updated `pacts/openbank-domestic-payment-openbank-document-service.json` in the
 * same PR — an un-regenerated pact silently verifies the OLD contract on the provider side.
 * `pact-drift-check.yml` enforces this over a scope derived from the `@Pact` annotations.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-document-service", pactVersion = PactSpecVersion.V3)
class DomesticPaymentDocumentServicePactConsumerTest {

    private val json = jacksonObjectMapper()

    @Pact(consumer = "openbank-domestic-payment", provider = "openbank-document-service")
    fun listTemplatesPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("the canonical document templates are seeded and published")
        .uponReceiving("GET the document templates to resolve a PUBLISHED domestic-confirmation body")
        .path(TEMPLATES_PATH)
        .query("$LIMIT_PARAM=$TEMPLATE_LIST_LIMIT")
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            // stringType on every element field, DELIBERATELY: the template list is HETEROGENEOUS —
            // document-service seeds six templates across three codes and two locales, in DRAFT /
            // PUBLISHED / RETIRED states. Pinning `code` or `status` to a value would assert "every
            // template is POTVRZENI_O_PLATBE_EN and every one is PUBLISHED", a claim the contract
            // does not make and the provider's own seed data falsifies on the first run. What the
            // consumer needs from the contract is that the three fields it filters and reads on are
            // PRESENT and are strings; the selection itself is PaymentConfirmationRenderAdapter's
            // own business.
            newJsonArrayMinLike(1) { a ->
                a.`object` { t ->
                    t.stringType("code", "POTVRZENI_O_PLATBE_EN")
                    t.stringType("status", "PUBLISHED")
                    t.stringType("bodyHtml", "<p>{{document.status}}</p>")
                }
            }.build(),
        )
        .toPact()

    @Pact(consumer = "openbank-domestic-payment", provider = "openbank-document-service")
    fun previewTemplatePact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("the template preview renderer is available")
        .uponReceiving("POST a template body plus domestic payment data to render the confirmation HTML")
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
            // `renderedHtml` string — the only field the adapter returns.
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

        val templates: List<DocumentTemplateSummary> = json.readValue(body)
        assertThat(templates).isNotEmpty()
        // All three fields PaymentConfirmationRenderAdapter touches must deserialize into the
        // client DTO: it filters on `code` + `status`, then sends `bodyHtml` on to the preview call.
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

        val response: PreviewTemplateResponse = json.readValue(body)
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

        /** Mirrors `PaymentConfirmationRenderAdapter.LIST_LIMIT`, the bound the adapter sends. */
        const val TEMPLATE_LIST_LIMIT = 200

        /**
         * The exact body the adapter posts: the PUBLISHED template body it just resolved, plus the
         * data map the confirmation mapper builds. Kept minimal — the contract is the two-field
         * envelope, not the confirmation's field vocabulary, which is the mapper's own business.
         */
        val PREVIEW_REQUEST = PreviewTemplateRequest(
            bodyHtml = "<p>{{document.status}}</p>",
            data = mapOf("document" to mapOf("status" to "COMPLETED")),
        )

        private val listTemplatesMethod =
            DocumentServiceClient::class.java.getDeclaredMethod("listTemplates", Int::class.javaPrimitiveType)

        private val previewMethod =
            DocumentServiceClient::class.java.getDeclaredMethod("previewTemplate", PreviewTemplateRequest::class.java)

        /** The client's interface-level `@Path`; both methods here add their own segment to it. */
        private val interfacePath: String =
            DocumentServiceClient::class.java.getAnnotation(Path::class.java).value

        /** Interface `@Path` + method `@Path`, joined the way JAX-RS resolves them. */
        val templatesPath: String = joinJaxRs(listTemplatesMethod.getAnnotation(Path::class.java).value)

        /** Likewise, for the preview POST. */
        val previewPath: String = joinJaxRs(previewMethod.getAnnotation(Path::class.java).value)

        /** The query-parameter name the production client sends the list bound under. */
        val limitQueryParam: String = listTemplatesMethod.parameterAnnotations
            .flatMap { it.toList() }
            .filterIsInstance<QueryParam>()
            .single()
            .value

        private fun joinJaxRs(methodPath: String): String =
            "/" + listOf(interfacePath, methodPath).joinToString("/") { it.trim('/') }.trim('/')
    }
}
