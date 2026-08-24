// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import java.util.UUID

/**
 * The ADR-0198 D4 consent gate's consent-service client (#2660).
 *
 * `GET /party/{partyId}/grantee/{granteeId}/active` answers exactly the yes/no a sender holds
 * (partyId + channel), never a consent id — `POST /{id}/validate` would require listing every
 * consent the party holds, PSD2 account access included, to answer a marketing question. The
 * boolean is uncacheable by design: ADR-0198 requires a check per send.
 *
 * The M2M token is the shared `openbank-services` client_credentials filter every outbound
 * service-to-service call here uses (same pattern as account-service's SanctionsServiceClient);
 * consent-service authorizes `consent.validate` to ROLE_OPERATOR/ROLE_ADMIN/ROLE_API, which that
 * client carries.
 */
@RegisterRestClient(configKey = "consent-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
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
