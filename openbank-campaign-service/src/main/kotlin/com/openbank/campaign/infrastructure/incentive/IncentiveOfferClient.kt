// SPDX-License-Identifier: Apache-2.0
package com.openbank.campaign.infrastructure.incentive

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.campaign.application.port.out.IncentiveOfferRegistry
import com.openbank.campaign.domain.model.IncentiveOfferRef
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.resteasy.reactive.RestResponse
import java.util.UUID

@RegisterRestClient(configKey = "incentive-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/incentives")
@Produces(MediaType.APPLICATION_JSON)
interface IncentiveServiceClient {
    @GET
    @Path("/offers/{id}")
    fun offer(@PathParam("id") id: UUID): Uni<RestResponse<IncentiveOfferResponse>>
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class IncentiveOfferResponse(val ref: IncentiveOfferRef, val status: String)

@ApplicationScoped
class LiveIncentiveOfferRegistry(@RestClient private val client: IncentiveServiceClient) : IncentiveOfferRegistry {
    override suspend fun resolvePublished(ref: IncentiveOfferRef): IncentiveOfferRef? {
        val http = client.offer(ref.id).awaitSuspending()
        if (http.status != RestResponse.StatusCode.OK) return null
        val response = http.entity ?: return null
        return response.ref.takeIf { response.status == "PUBLISHED" && it == ref }
    }
}
