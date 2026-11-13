// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.infrastructure.client

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

/**
 * Reactive REST client for `openbank-sanctions-service`'s v3 screen endpoint, which carries the
 * explicit screening profile. Shares the `sanctions-service` client configuration (URL, timeouts,
 * service token) with [SanctionsServiceClient], which stays on the frozen v1 contract for the
 * operator-triggered PEP re-screen.
 */
// #15723: the bearer for the customer screen is minted by the NAMED oidc-client `m2m` - Keycloak
// client `openbank-kyc` (ROLE_API only) - never the shared `openbank-services` default client.
@OidcClientFilter("m2m")
@RegisterRestClient(configKey = "sanctions-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@Path("/api/v3/sanctions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface SanctionsCustomerScreeningClient {

    @POST
    @Path("/screen")
    fun screen(request: CustomerScreenRequest): Uni<CustomerScreenResponse>
}

/** Mirror of sanctions-service `ScreenEntityCommandV3`, limited to what a customer screen sends. */
data class CustomerScreenRequest(
    val idempotencyKey: String,
    val entityType: String,
    val name: String,
    val subjectId: String,
    val profile: String,
)

/** Subset of the sanctions-service `SanctionsCheckV3` payload this service acts on. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class CustomerScreenResponse(
    val status: String? = null,
    val overallScore: Double? = null,
    val profile: String? = null,
    val pepStatus: String? = null,
    val matches: List<CustomerScreenMatch> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CustomerScreenMatch(
    val listType: String? = null,
    val matchKind: String? = null,
    val matchedName: String? = null,
    val matchScore: Double? = null,
)
