// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.statement.contract

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
import com.openbank.statement.infrastructure.client.DocumentRestClient
import com.openbank.statement.infrastructure.client.DocumentTemplateDto
import com.openbank.statement.infrastructure.client.PreviewTemplateRequestDto
import com.openbank.statement.infrastructure.client.PreviewTemplateResponseDto
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import jakarta.ws.rs.QueryParam
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Consumer-driven contract for the two `openbank-document-service` calls that render a statement
 * document (ADR-0248): `GET /api/v1/documents/templates` to resolve the PUBLISHED `bodyHtml` for a
 * template code, then `POST /api/v1/documents/templates/preview` to merge the statement data into
 * it. Both are driven by
 * [com.openbank.statement.infrastructure.client.DocumentTemplateRestAdapter], synchronously, and
 * the adapter is fail-CLOSED (it raises `DocumentServiceException`), so a document-service contract
 * break surfaces on the caller's endpoint immediately.
 *
 * **Why this test exists.** #4299 covered `openbank-sepa-payment`'s copy of this same two-call
 * flow; this is the third and last of the merged services holding a live synchronous REST client
 * against document-service (the second being `openbank-domestic-payment`,
 * `DomesticPaymentDocumentServicePactConsumerTest`). Until now this module's only cover for these
 * calls was consumer-authored mocks, which hard-code the very paths the client sends and therefore
 * agree with the client by construction — the configuration that let finrep-service ship a call to
 * `/api/v1/ledger/trial-balance`, a ledger route that has never existed, with green unit tests
 * (#2269).
 *
 * **The two sides are deliberately sourced differently.** Each interaction declares the contract as
 * a LITERAL ([TEMPLATES_PATH], [PREVIEW_PATH], [LIMIT_PARAM]); the REQUESTS are issued against
 * [templatesPath] / [previewPath] / [limitQueryParam], derived by reflection from
 * [DocumentRestClient]'s own `@Path` and `@QueryParam` annotations. That asymmetry IS the test: the
 * pact mock server fails when the production client is annotated with anything other than the
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
 * (`./gradlew :openbank-statement-service:test --tests "*StatementDocumentServicePactConsumerTest*"`)
 * and commit the updated `pacts/openbank-statement-service-openbank-document-service.json` in the
 * same PR — an un-regenerated pact silently verifies the OLD contract on the provider side.
 * `pact-drift-check.yml` enforces this over a scope derived from the `@Pact` annotations.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-document-service", pactVersion = PactSpecVersion.V3)
class StatementDocumentServicePactConsumerTest {

    private val json = jacksonObjectMapper()

    @Pact(consumer = "openbank-statement-service", provider = "openbank-document-service")
    fun listTemplatesPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("the canonical document templates are seeded and published")
        .uponReceiving("GET the document templates to resolve a PUBLISHED statement-document body")
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
            // PUBLISHED / RETIRED states. Pinning `code` or `status` to a value would assert a claim
            // the contract does not make and the provider's own seed data falsifies on the first
            // run. What the consumer needs from the contract is that the three fields it filters and
            // reads on are PRESENT and are strings; the selection itself is
            // DocumentTemplateRestAdapter's own business.
            newJsonArrayMinLike(1) { a ->
                a.`object` { t ->
                    t.stringType("code", "POTVRZENI_O_PLATBE_EN")
                    t.stringType("status", "PUBLISHED")
                    t.stringType("bodyHtml", "<p>{{document.status}}</p>")
                }
            }.build(),
        )
        .toPact()

    @Pact(consumer = "openbank-statement-service", provider = "openbank-document-service")
    fun previewTemplatePact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("the template preview renderer is available")
        .uponReceiving("POST a template body plus statement data to render the document HTML")
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
            // `renderedHtml` string — the only field the adapter reads.
            newJsonBody { o -> o.stringType("renderedHtml", "<p>COMPLETED</p>") }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "listTemplatesPact")
    fun `the template list carries the code, status and body the document adapter reads`(mockServer: MockServer) {
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

        val templates: List<DocumentTemplateDto> = json.readValue(body)
        assertThat(templates).isNotEmpty()
        // All three fields DocumentTemplateRestAdapter touches must deserialize into the client DTO:
        // it filters on `code` + `status`, then sends `bodyHtml` on to the preview call. The DTO
        // declares them nullable, so asserting NOT-NULL here is the real check — a renamed provider
        // field would deserialize to null rather than failing, and the adapter would then report
        // "no PUBLISHED template" for a template that is published.
        val template = templates.first()
        assertThat(template.code).isNotNull()
        assertThat(template.status).isNotNull()
        assertThat(template.bodyHtml).isNotNull()
    }

    @Test
    @PactTestFor(pactMethod = "previewTemplatePact")
    fun `preview returns the rendered document HTML for the template body and statement data`(mockServer: MockServer) {
        assertThat(previewPath).isEqualTo(PREVIEW_PATH)

        val body = given()
            .baseUri(mockServer.getUrl())
            .contentType("application/json")
            .body(json.writeValueAsString(PREVIEW_REQUEST))
            .post(previewPath)
            .then()
            .statusCode(200)
            .extract().body().asString()

        val response: PreviewTemplateResponseDto = json.readValue(body)
        // Nullable in the DTO, and `renderedHtml.orEmpty()` in the adapter — so a renamed provider
        // field would silently render an EMPTY document rather than fail. Assert it is present.
        assertThat(response.renderedHtml).isNotNull()
    }

    private companion object {
        /**
         * The contract, written out LITERALLY on purpose: openbank-document-service serves the
         * bounded template list here (`DocumentResource.listTemplates`, `@Path("/api/v1/documents")`
         * + `@Path("/templates")`).
         *
         * It must NOT be derived from [DocumentRestClient] like [templatesPath] is. Deriving both
         * sides from the same annotation makes the interaction and the request move together, so the
         * pact mock server always sees exactly what it expects and the test passes against a client
         * pointing anywhere at all. The literal is the fixed point the annotation is measured
         * against.
         */
        const val TEMPLATES_PATH = "/api/v1/documents/templates"

        /** Likewise literal: `DocumentResource.previewTemplate`, the non-persisting render. */
        const val PREVIEW_PATH = "/api/v1/documents/templates/preview"

        /** document-service's query-parameter name for the list bound — likewise literal. */
        const val LIMIT_PARAM = "limit"

        /** Mirrors `DocumentTemplateRestAdapter.TEMPLATE_LIST_LIMIT`, the bound the adapter sends. */
        const val TEMPLATE_LIST_LIMIT = 200

        /**
         * The exact body the adapter posts: the PUBLISHED template body it just resolved, plus the
         * statement data map. Kept minimal — the contract is the two-field envelope, not the
         * document's field vocabulary, which is the caller's own business.
         */
        val PREVIEW_REQUEST = PreviewTemplateRequestDto(
            bodyHtml = "<p>{{document.status}}</p>",
            data = mapOf("document" to mapOf("status" to "COMPLETED")),
        )

        private val listTemplatesMethod =
            DocumentRestClient::class.java.getDeclaredMethod("listTemplates", Int::class.javaPrimitiveType)

        private val previewMethod =
            DocumentRestClient::class.java.getDeclaredMethod("preview", PreviewTemplateRequestDto::class.java)

        /**
         * Unlike sepa-payment's and domestic-payment's clients, [DocumentRestClient] carries NO
         * interface-level `@Path` — each method spells the full route. Read as an empty prefix so
         * the join below is uniform, and so ADDING an interface `@Path` (which would prefix every
         * route and break both calls) fails the equality assertions in the tests above.
         */
        private val interfacePath: String =
            DocumentRestClient::class.java.getAnnotation(Path::class.java)?.value ?: ""

        /** Interface `@Path` (empty here) + method `@Path`, joined the way JAX-RS resolves them. */
        val templatesPath: String = joinJaxRs(listTemplatesMethod.getAnnotation(Path::class.java).value)

        /** Likewise, for the preview POST. */
        val previewPath: String = joinJaxRs(previewMethod.getAnnotation(Path::class.java).value)

        /** The query-parameter name the production client sends the list bound under. */
        val limitQueryParam: String = listTemplatesMethod.parameterAnnotations
            .flatMap { it.toList() }
            .filterIsInstance<QueryParam>()
            .single()
            .value

        private fun joinJaxRs(methodPath: String): String = "/" +
            listOf(interfacePath, methodPath)
                .filter { it.trim('/').isNotEmpty() }
                .joinToString("/") { it.trim('/') }
    }
}
