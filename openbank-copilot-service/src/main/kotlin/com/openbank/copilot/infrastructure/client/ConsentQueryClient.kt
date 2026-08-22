// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
package com.openbank.copilot.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import java.util.UUID

/**
 * consent-service's active-consent check (ADR-0269 rule 5).
 *
 * Service-to-service, NOT the propagated customer bearer: the question is "did this customer grant
 * this scope", which the customer's own token cannot be trusted to answer about itself.
 */
@RegisterRestClient(configKey = "consent-service")
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/consents")
@Produces(MediaType.APPLICATION_JSON)
interface ConsentQueryClient {
    @GET
    @Path("/party/{partyId}/grantee/{granteeId}/active")
    @Timeout(CONSENT_TIMEOUT_MS)
    fun hasActiveConsent(
        @PathParam("partyId") partyId: UUID,
        @PathParam("granteeId") granteeId: String,
        @QueryParam("scope") scope: String,
    ): Uni<ConsentCheckDto>
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class ConsentCheckDto(val granted: Boolean = false)

private const val CONSENT_TIMEOUT_MS = 2000L
