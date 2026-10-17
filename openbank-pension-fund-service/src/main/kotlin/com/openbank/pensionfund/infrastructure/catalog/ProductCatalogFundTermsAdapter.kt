// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.infrastructure.catalog

import com.fasterxml.jackson.databind.JsonNode
import com.openbank.libs.web.SyntheticTaintClientFilter
import com.openbank.pensionfund.application.port.FundTermsPort
import com.openbank.pensionfund.application.port.PinnedFundTerms
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.math.BigDecimal
import java.util.UUID

/** Read-only v2 catalog boundary: one immutable revision, by id. */
@Path("/api/v2")
@Produces(MediaType.APPLICATION_JSON)
@RegisterRestClient(configKey = "product-catalog")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
interface FundRevisionCatalogClient {
    @GET
    @Path("/revisions/{id}")
    suspend fun revision(@PathParam("id") id: UUID): JsonNode
}

@ApplicationScoped
class ProductCatalogFundTermsAdapter(@RestClient private val client: FundRevisionCatalogClient) : FundTermsPort {
    override suspend fun resolve(revisionId: UUID): PinnedFundTerms {
        val revision = try {
            client.revision(revisionId)
        } catch (e: WebApplicationException) {
            // Only a genuine 404 means "no such revision"; every other failure stays a failure.
            if (e.response?.status == HTTP_NOT_FOUND) {
                throw IllegalArgumentException("productRevisionId $revisionId is not a catalog revision")
            }
            throw e
        }
        return FundRevisionTerms.parse(revisionId, revision)
    }

    private companion object {
        const val HTTP_NOT_FOUND = 404
    }
}

/** Fail-closed parse of a retirement revision's fee schedule; pure, so it is unit-tested without a catalog. */
object FundRevisionTerms {
    private const val RETIREMENT_SCHEMA = "org.openbank.retirement.pension-savings"
    private const val MIN_SCHEMA_VERSION = 2
    private val PERCENT = BigDecimal(100)

    fun parse(requested: UUID, revision: JsonNode): PinnedFundTerms {
        require(revision.path("id").asText() == requested.toString()) { "catalog returned another revision" }
        require(revision.path("state").asText() == "PUBLISHED") {
            "productRevisionId $requested is not a published revision"
        }
        val schema = revision.path("schemaRef")
        require(
            schema.path("id").asText() == RETIREMENT_SCHEMA &&
                schema.path("version").asInt(0) >= MIN_SCHEMA_VERSION,
        ) { "productRevisionId $requested is not a retirement (pension-savings v2) revision" }
        val percent = revision.path("content").path("attributes").path("feeSchedule")
            .path("managementFeeAnnualPercent").takeIf(JsonNode::isTextual)?.textValue()
        requireNotNull(percent) { "revision $requested has no feeSchedule.managementFeeAnnualPercent" }
        val rate = BigDecimal(percent).divide(PERCENT)
        return PinnedFundTerms(requested, rate)
    }
}
