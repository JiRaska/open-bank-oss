// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.infrastructure.consent

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.campaign.application.port.out.ConsentCheckPort
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

@RegisterRestClient(configKey = "consent-service")
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/consents")
@Produces(MediaType.APPLICATION_JSON)
interface ConsentServiceClient {
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

/**
 * ADR-0198/0195: the consent check is a live call to consent-service, never a cached copy — a
 * cached consent is one that survives its own revocation. Fail-closed: an unreachable or denying
 * consent-service means no marketing send.
 */
@ApplicationScoped
class LiveConsentCheckAdapter(
    @RestClient private val client: ConsentServiceClient,
    @ConfigProperty(name = "openbank.campaign.consent-grantee", defaultValue = "party-service:marketing-comms")
    private val grantee: String,
) : ConsentCheckPort {

    override suspend fun hasActiveConsent(partyId: UUID, scope: String): Boolean =
        runCatching { client.hasActiveConsent(partyId, grantee, scope).awaitSuspending().granted }
            .getOrDefault(false)
}
