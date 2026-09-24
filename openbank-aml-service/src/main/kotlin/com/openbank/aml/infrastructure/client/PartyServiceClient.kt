// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.aml.application.port.out.PartyDirectoryPort
import com.openbank.aml.application.port.out.PartyPage
import com.openbank.aml.application.port.out.PartySummary
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.util.UUID

/**
 * party-service's existing paginated list (`GET /api/v1/parties?status=`, admits `ROLE_API`). The
 * reactive OIDC filter attaches this service's client-credentials token. In `%prod` the call goes
 * over party-service's private-CA mTLS listener (named TLS bucket `party-authority`).
 */
@RegisterRestClient(configKey = "party-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/parties")
@Produces(MediaType.APPLICATION_JSON)
interface PartyServiceRestClient {
    @GET
    fun list(
        @QueryParam("status") status: String,
        @QueryParam("page") page: Int,
        @QueryParam("size") size: Int,
    ): Uni<PartyListDto>
}

/**
 * The subset of a `GET /api/v1/parties` item this service reads. `ignoreUnknown` is load-bearing:
 * the item also carries legal name and e-mail, and not binding them keeps that PII out of this
 * service's heap and logs.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PartyListItemDto(
    val id: String = "",
    val partyType: String = "",
    val status: String = "",
    val kycStatus: String = "",
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PartyListDto(val items: List<PartyListItemDto> = emptyList(), val total: Long = 0)

/** Used only by the onboarding screening reconciler; never on a request or event path. */
@ApplicationScoped
class PartyServiceClient(@RestClient private val client: PartyServiceRestClient) : PartyDirectoryPort {

    override suspend fun listPendingKyc(page: Int, size: Int): PartyPage {
        val dto = client.list("PENDING_KYC", page, size).awaitSuspending()
        val items = dto.items.mapNotNull { item ->
            runCatching { UUID.fromString(item.id) }.getOrNull()?.let {
                PartySummary(it, item.partyType, item.status, item.kycStatus)
            }
        }
        return PartyPage(items, hasMore = (page.toLong() + 1) * size < dto.total)
    }
}
