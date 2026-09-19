// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.aml.application.port.out.PartyDirectoryPort
import com.openbank.aml.application.port.out.PartyPage
import com.openbank.aml.application.port.out.PartySummary
import io.quarkus.oidc.client.OidcClient
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.rest.client.RestClientBuilder
import java.net.URI
import java.util.UUID

@Path("/api/v1/parties")
@Produces(MediaType.APPLICATION_JSON)
interface PartyServiceRestClient {
    @GET
    fun list(
        @HeaderParam("Authorization") authorization: String,
        @QueryParam("status") status: String,
        @QueryParam("page") page: Int,
        @QueryParam("size") size: Int,
    ): Uni<PartyListDto>
}

/**
 * The subset of party-service's `GET /api/v1/parties` item this service reads. Every other field
 * of that response (names, e-mail) is deliberately NOT mapped, so no personal data is held here.
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

/**
 * party-service's existing paginated list (`?status=` filter, `ROLE_API` admitted), authenticated
 * with this service's own client-credentials token. Used only by the onboarding screening
 * reconciler; never on a request or event path.
 */
@ApplicationScoped
class PartyServiceClient(
    private val oidcClient: Instance<OidcClient>,
    @ConfigProperty(
        name = "quarkus.rest-client.party-service.url",
        defaultValue = "http://party-service.party.svc:8111",
    )
    private val baseUrl: String,
) : PartyDirectoryPort {

    private val httpClient by lazy {
        RestClientBuilder.newBuilder()
            .baseUri(URI.create(baseUrl))
            .build(PartyServiceRestClient::class.java)
    }

    override suspend fun listPendingKyc(page: Int, size: Int): PartyPage {
        val token = oidcClient.get().tokens.awaitSuspending().accessToken
        val dto = httpClient.list("Bearer $token", "PENDING_KYC", page, size).awaitSuspending()
        val items = dto.items.mapNotNull { item ->
            runCatching { UUID.fromString(item.id) }.getOrNull()?.let {
                PartySummary(it, item.partyType, item.status, item.kycStatus)
            }
        }
        return PartyPage(items, hasMore = (page.toLong() + 1) * size < dto.total)
    }
}
