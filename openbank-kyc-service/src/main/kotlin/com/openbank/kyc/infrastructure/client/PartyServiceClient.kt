// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import java.time.Instant
import java.util.UUID

/**
 * RestClient binding to `openbank-party-service`'s party register
 * (`GET /api/v1/parties`), used only by the #5698 orphaned-party reconciliation.
 *
 * Mirrors the [SanctionsServiceClient] stack already in this service (ADR-0032 §D) — the reactive
 * OIDC filter attaches the service token, without which party-service answers 401. The endpoint
 * admits `ROLE_API`, which is the role the shared service account carries in every realm, so no
 * new grant is needed for this call.
 */
@RegisterRestClient(configKey = "party-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/parties")
@Produces(MediaType.APPLICATION_JSON)
interface PartyServiceClient {

    @GET
    fun listParties(@QueryParam("page") page: Int, @QueryParam("size") size: Int): Uni<PartyListResponse>
}

/**
 * Subset of party-service's list payload. `@JsonIgnoreProperties(ignoreUnknown = true)` is
 * load-bearing rather than defensive: the response carries `legalName`, `email`, `tradingName` and
 * `kycStatus`, and this reconciliation has no need for any of them. Not binding them is how the PII
 * stays out of this service's heap and logs.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PartyListResponse(val items: List<PartyListItem> = emptyList(), val total: Long = 0)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PartyListItem(val id: UUID? = null, val status: String? = null, val createdAt: Instant? = null)
