// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.account.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

@RegisterRestClient(configKey = "sanctions-service")
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/sanctions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface SanctionsServiceClient {
    @POST
    @Path("/screen")
    fun screen(request: SanctionsScreenRequest): Uni<SanctionsScreenResponse>
}

data class SanctionsScreenRequest(
    val idempotencyKey: String,
    val entityType: String,
    val name: String,
    val aliases: List<String> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SanctionsScreenResponse(
    val status: String? = null,
    val overallScore: Double? = null,
    val matches: List<SanctionsScreenMatch> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SanctionsScreenMatch(val matchedName: String? = null, val matchScore: Double? = null)
