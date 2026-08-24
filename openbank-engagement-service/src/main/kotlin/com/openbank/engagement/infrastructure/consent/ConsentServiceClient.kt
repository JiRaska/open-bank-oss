// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.infrastructure.consent

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.engagement.application.port.out.ConsentCheckPort
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.util.UUID

/** Same shape as campaign-service's `ConsentServiceClient` — the live per-call check, never cached. */
@RegisterRestClient(configKey = "consent-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/consents")
@Produces(MediaType.APPLICATION_JSON)
interface ConsentRestClient {
    @GET
    @Path("/party/{partyId}/grantee/{granteeId}/active")
    fun hasActiveConsent(
        @PathParam("partyId") partyId: UUID,
        @PathParam("granteeId") granteeId: String,
        @QueryParam("scope") scope: String,
    ): Uni<ConsentCheckResponse>
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class ConsentCheckResponse(val granted: Boolean = false)

@ApplicationScoped
class ConsentServiceClientAdapter(
    @RestClient private val client: ConsentRestClient,
    // Same fixed internal marketing grantee campaign-service's notification consumer checks
    // against (ADR-0205 D3) — one constant fleet-wide so the gate cannot drift per call site.
    @ConfigProperty(name = "openbank.engagement.marketing-grantee", defaultValue = "party-service:marketing-comms")
    private val marketingGrantee: String,
) : ConsentCheckPort {
    override suspend fun hasActiveConsent(partyId: UUID, scope: String): Boolean =
        client.hasActiveConsent(partyId, marketingGrantee, scope).awaitSuspending().granted
}
