// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.document

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.libs.web.SyntheticTaintClientFilter
import com.openbank.pension.application.onboarding.IntegrationUnavailableException
import com.openbank.pension.application.port.out.RenderedDocument
import io.quarkus.oidc.client.filter.OidcClientFilter
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.jboss.logging.Logger
import java.time.LocalDate

/**
 * document-service's persisting render (`POST /api/v1/documents/render`, its openapi.yaml 1.6.0).
 * The persisting route, deliberately — not the non-persisting `templates/preview` statement-service
 * uses: a key-information document is SIGNED by its hash, so the hash must be document-service's,
 * computed over the bytes it STORED and will serve back, never one this service computes over
 * something it merely saw.
 *
 * Authenticated as pension-service's own client (`openbank-pension`, ROLE_API), like the
 * pension-fund-service client.
 */
@Path("/api/v1/documents")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@OidcClientFilter
@RegisterRestClient(configKey = "document-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
interface DocumentServiceRestClient {
    @POST
    @Path("/render")
    suspend fun render(request: RenderDocumentRequestDto): RenderedDocumentDto
}

data class RenderDocumentRequestDto(
    val templateCode: String,
    val data: Map<String, Any?>,
    val contentType: String = "application/pdf",
    val partyRef: String?,
    val caseRef: String?,
    val productRef: String?,
    val retainUntil: LocalDate? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RenderedDocumentDto(
    val id: String? = null,
    val templateCode: String? = null,
    val sha256: String? = null,
    val status: String? = null,
)

/** The call without JAX-RS annotations, so the rendering rules are unit-tested against a fake. */
fun interface DocumentRenderCall {
    suspend fun render(request: RenderDocumentRequestDto): RenderedDocumentDto
}

/**
 * The rules every pension document render obeys, free of CDI:
 * - the template code is `<base>-<language>`; only `cs` and `en` exist (anything else is `cs`, the
 *   language of the Czech products this service runs);
 * - every data map carries `legalReviewRequired = true` — the templates are drafts until legal
 *   signs them off, and the rendered document says so;
 * - the answer must name a document and carry a well-formed SHA-256, or the render is REFUSED:
 *   a document without its hash cannot be bound to a signature, and a made-up one would bind the
 *   participant to nothing;
 * - document-service unreachable or refusing (no such template, 4xx, 5xx) fails CLOSED with
 *   [IntegrationUnavailableException] (503): no document is ever invented.
 */
class DocumentRendering(private val call: DocumentRenderCall) {
    private val log = Logger.getLogger(DocumentRendering::class.java)

    @Suppress("LongParameterList")
    suspend fun render(
        templateBase: String,
        language: String?,
        data: Map<String, Any?>,
        partyRef: String,
        caseRef: String,
        productRef: String,
    ): RenderedDocument {
        val templateCode = templateCode(templateBase, language)
        val response = try {
            call.render(
                RenderDocumentRequestDto(
                    templateCode = templateCode,
                    data = data + (LEGAL_REVIEW_KEY to true),
                    partyRef = partyRef,
                    caseRef = caseRef,
                    productRef = productRef,
                ),
            )
        } catch (e: WebApplicationException) {
            log.warnf("document-service refused %s: HTTP %d", templateCode, e.response?.status ?: 0)
            unavailable("document-service refused to render $templateCode")
        } catch (e: java.io.IOException) {
            log.warnf(e, "document-service unreachable rendering %s", templateCode)
            unavailable("document-service is unreachable")
        } catch (e: jakarta.ws.rs.ProcessingException) {
            log.warnf(e, "document-service unreachable rendering %s", templateCode)
            unavailable("document-service is unreachable")
        }
        val id = response.id?.takeIf { it.isNotBlank() }
            ?: unavailable(
                "document-service answered without a document id for $templateCode",
            )
        val sha = response.sha256?.lowercase()?.takeIf { SHA256.matches(it) }
            ?: unavailable(
                "document-service answered without a valid SHA-256 for $templateCode",
            )
        return RenderedDocument(id, sha)
    }

    private fun unavailable(message: String): Nothing = throw IntegrationUnavailableException(message)

    companion object {
        const val LEGAL_REVIEW_KEY = "legalReviewRequired"
        val LANGUAGES = setOf("cs", "en")
        private const val DEFAULT_LANGUAGE = "cs"
        private val SHA256 = Regex("^[0-9a-f]{64}$")

        fun language(requested: String?): String =
            requested?.lowercase()?.take(2)?.takeIf { it in LANGUAGES } ?: DEFAULT_LANGUAGE

        fun templateCode(base: String, language: String?): String = "$base-${language(language)}"
    }
}
