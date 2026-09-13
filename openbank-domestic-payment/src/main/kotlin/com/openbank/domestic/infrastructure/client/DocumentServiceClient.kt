// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.client

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
 * RestClient binding to `openbank-document-service`'s template surface (ADR-0248 #3). Only two
 * calls are used, both read-only / non-persisting on the document-service side:
 *  - [listTemplates] to resolve the PUBLISHED `bodyHtml` for a `templateCode` — document-service's
 *    `preview` endpoint takes a raw `bodyHtml`, not a `templateCode` (it merges an UNSAVED template
 *    body, by design — see `PreviewTemplateRequest`/`DocumentResource.previewTemplate`), so the
 *    caller resolves the current published body first.
 *  - [previewTemplate] to merge [PreviewTemplateRequest.data] into that body and get back HTML.
 *    Never persisted: no `Document` row, no `document.generated` outbox event, on either call.
 */
@RegisterRestClient(configKey = "document-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/documents")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface DocumentServiceClient {

    @GET
    @Path("/templates")
    fun listTemplates(@QueryParam("limit") limit: Int): Uni<List<DocumentTemplateSummary>>

    @POST
    @Path("/templates/preview")
    fun previewTemplate(request: PreviewTemplateRequest): Uni<PreviewTemplateResponse>
}

/** Mirror of document-service's `PreviewTemplateRequest`. */
data class PreviewTemplateRequest(val bodyHtml: String, val data: Map<String, Any?> = emptyMap())

/** Mirror of document-service's `PreviewTemplateResponse`. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PreviewTemplateResponse(val renderedHtml: String)

/** Mirror of document-service's `TemplateResponse` — only the fields this adapter needs. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class DocumentTemplateSummary(val code: String, val status: String, val bodyHtml: String)
