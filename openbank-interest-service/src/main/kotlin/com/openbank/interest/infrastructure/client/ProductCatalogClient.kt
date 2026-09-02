// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.interest.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.JsonNode
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Read-only v2 catalog boundary for the interest rate snapshotter.
 *
 * The change stream only contains durable identifiers. Every published revision is consequently
 * re-read by its immutable id and its offering is re-read to obtain the canonical specification id
 * that account-service puts on accounts. No interest accrual ever calls this client directly.
 */
@RegisterRestClient(configKey = "product-catalog")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Produces(MediaType.APPLICATION_JSON)
@Path("/api/v2")
interface ProductCatalogClient {
    @GET
    @Path("/events")
    fun events(
        @QueryParam("after") after: String?,
        @QueryParam("limit") @DefaultValue("1") limit: Int,
    ): Uni<CatalogEventPageClientResponse>

    @GET
    @Path("/revisions/{id}")
    fun revision(@PathParam("id") id: UUID): Uni<CatalogRevisionClientResponse>

    @GET
    @Path("/offerings/{id}")
    fun offering(@PathParam("id") id: UUID): Uni<CatalogOfferingClientResponse>
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class CatalogEventPageClientResponse(
    val items: List<CatalogEventClientResponse> = emptyList(),
    val nextCursor: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CatalogEventClientResponse(
    val id: UUID,
    val aggregateType: String,
    val aggregateId: UUID,
    val eventType: String,
    val occurredAt: OffsetDateTime,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CatalogRevisionClientResponse(
    val id: UUID,
    val offeringId: UUID,
    val schemaRef: CatalogSchemaRefClientResponse,
    val state: String,
    val content: CatalogRevisionContentClientResponse,
    val effectiveFrom: OffsetDateTime?,
    val effectiveTo: OffsetDateTime?,
    val contentHash: String?,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CatalogSchemaRefClientResponse(val id: String, val version: Int)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CatalogRevisionContentClientResponse(val attributes: JsonNode)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CatalogOfferingClientResponse(val id: UUID, val specificationId: UUID)
