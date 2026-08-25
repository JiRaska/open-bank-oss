// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
package com.openbank.copilot.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import java.util.UUID

/**
 * The ADR-0269 360 credit profile (#6215), read for the L1 advisor.
 *
 * Service-to-service rather than the propagated customer bearer: analytics-sink's route is an
 * internal operator/auditor surface, not a customer-facing one. The customer's protection here is
 * the CONSENT check that must pass before this client is called at all — see
 * `CreditAiLevelResolver`. A tool that skipped the level check would read a profile the customer
 * never agreed to have read, which is why the check lives in the tool and not in this client.
 */
@RegisterRestClient(configKey = "analytics-sink")
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@RegisterProvider(SyntheticTaintClientFilter::class)
@Path("/api/v1/analytics/credit-profile")
@Produces(MediaType.APPLICATION_JSON)
interface CreditProfileReadClient {
    @GET
    @Path("/{partyId}")
    @Timeout(PROFILE_TIMEOUT_MS)
    fun profile(@PathParam("partyId") partyId: UUID): Uni<CreditProfileDto>
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class CreditProfileDto(
    @param:JsonProperty("months_observed") val monthsObserved: Int = 0,
    @param:JsonProperty("income_monthly") val incomeMonthly: String? = null,
    @param:JsonProperty("outflow_monthly") val outflowMonthly: String? = null,
    @param:JsonProperty("net_monthly") val netMonthly: String? = null,
)

private const val PROFILE_TIMEOUT_MS = 2000L
