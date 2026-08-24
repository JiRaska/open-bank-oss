// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.statement.infrastructure.client

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

// document-service's `/api/v1/documents/templates/preview` endpoint is reused as-is (ADR-0248): it
// only accepts a literal `bodyHtml`, not a `templateCode` lookup, and there is no get-by-code route —
// only the bounded `GET /templates` list. So rendering a document by code is two calls: list (find
// the PUBLISHED template's bodyHtml), then preview (merge data into it). Neither call persists
// anything.
@JsonIgnoreProperties(ignoreUnknown = true)
data class DocumentTemplateDto(
    val code: String? = null,
    val version: String? = null,
    val bodyHtml: String? = null,
    val locale: String? = null,
    val status: String? = null,
)

data class PreviewTemplateRequestDto(val bodyHtml: String, val data: Map<String, Any?>)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PreviewTemplateResponseDto(val renderedHtml: String? = null)

@RegisterProvider(OidcClientRequestReactiveFilter::class)
@RegisterRestClient(configKey = "document-api")
@RegisterProvider(SyntheticTaintClientFilter::class)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface DocumentRestClient {
    @GET
    @Path("/api/v1/documents/templates")
    fun listTemplates(@QueryParam("limit") limit: Int): Uni<List<DocumentTemplateDto>>

    @POST
    @Path("/api/v1/documents/templates/preview")
    fun preview(request: PreviewTemplateRequestDto): Uni<PreviewTemplateResponseDto>
}
