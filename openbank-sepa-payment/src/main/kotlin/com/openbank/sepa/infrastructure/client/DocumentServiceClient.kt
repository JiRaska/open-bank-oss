// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

/**
 * RestClient binding to `openbank-document-service`'s template endpoints (ADR-0248 #3).
 * `GET /api/v1/documents/templates` lists templates (there is no get-by-code endpoint) so the
 * adapter can resolve a `templateCode` to its current PUBLISHED `bodyHtml`; that body is then
 * rendered via `POST /api/v1/documents/templates/preview`, document-service's existing
 * non-persisting preview endpoint (reused as-is, per the ADR — nothing new added there).
 * Carries the service OIDC token like the other inter-service clients in this module.
 */
@RegisterRestClient(configKey = "document-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/documents/templates")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface DocumentServiceClient {

    @GET
    fun listTemplates(@QueryParam("limit") limit: Int): Uni<List<DocumentTemplateClientResponse>>

    @POST
    @Path("/preview")
    fun preview(request: PreviewTemplateClientRequest): Uni<PreviewTemplateClientResponse>
}

/** Mirror of document-service `TemplateResponse` (tolerate unknown fields as the contract evolves). */
@JsonIgnoreProperties(ignoreUnknown = true)
data class DocumentTemplateClientResponse(
    val code: String,
    val bodyHtml: String,
    val locale: String = "en",
    val status: String = "DRAFT",
)

/** Mirror of document-service `PreviewTemplateRequest`. */
data class PreviewTemplateClientRequest(val bodyHtml: String, val data: Map<String, Any?> = emptyMap())

/** Mirror of document-service `PreviewTemplateResponse` (tolerate unknown fields). */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PreviewTemplateClientResponse(val renderedHtml: String)
