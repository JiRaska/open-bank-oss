// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sdd.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.filter.OidcClientFilter
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

/** RestClient binding to sanctions-service's synchronous screen, as service-account-openbank-sdd. */
@RegisterRestClient(configKey = "sanctions-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@OidcClientFilter("m2m")
@Path("/api/v1/sanctions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface SanctionsServiceClient {

    @POST
    @Path("/screen")
    fun screen(request: ScreenRequest): Uni<ScreenResponse>
}

/** Mirror of sanctions-service `ScreenEntityCommand` (the fields a name screen needs). */
data class ScreenRequest(
    val idempotencyKey: String,
    val entityType: String,
    val name: String,
    val aliases: List<String> = emptyList(),
)

/** Subset of sanctions-service `SanctionsCheck` the gate acts on. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ScreenResponse(
    val status: String? = null,
    val overallScore: Double? = null,
    val matches: List<ScreenMatch> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ScreenMatch(val matchedName: String? = null, val matchScore: Double? = null)
