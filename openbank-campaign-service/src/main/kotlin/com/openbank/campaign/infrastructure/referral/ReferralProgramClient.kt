// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.campaign.infrastructure.referral

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.campaign.application.port.out.ReferralProgramCatalogPort
import com.openbank.campaign.domain.model.ReferralProgramRef
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.util.UUID

@RegisterRestClient(configKey = "referral-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/referrals/programs")
@Produces(MediaType.APPLICATION_JSON)
interface ReferralProgramClient {
    @GET
    @Path("/{id}")
    fun published(@PathParam("id") id: UUID): Uni<ReferralProgramResponse>
}

@JsonIgnoreProperties(ignoreUnknown = true)
/**
 * The Referral 200 response is deliberately a minimal immutable reference. Its HTTP contract
 * already makes 200 mean published and unexpired; adding lifecycle fields here would both drift
 * from that contract and make Campaign decide Referral-owned lifecycle semantics.
 */
data class ReferralProgramResponse(val id: UUID, val name: String, val version: Int)

/** Live validation prevents a draft from pinning an invented or unpublished MGM programme. */
@ApplicationScoped
class LiveReferralProgramCatalogAdapter(@RestClient private val client: ReferralProgramClient) :
    ReferralProgramCatalogPort {
    override suspend fun resolvePublished(id: UUID): ReferralProgramRef? = try {
        client.published(id).awaitSuspending().let { response ->
            ReferralProgramRef(response.id, response.name, response.version)
        }
    } catch (failure: WebApplicationException) {
        if (failure.response.status == Response.Status.NOT_FOUND.statusCode) null else throw failure
    }
}
