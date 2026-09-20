// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.account.application.port.out.DirectoryPage
import com.openbank.account.application.port.out.DirectoryParty
import com.openbank.account.application.port.out.PartyDirectoryPort
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
 * party-service's existing paginated list (`GET /api/v1/parties?status=`, admits `ROLE_API`). In
 * `%prod` it goes over party-service's private-CA mTLS listener (TLS bucket `party-authority`).
 */
@RegisterRestClient(configKey = "party-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/parties")
@Produces(MediaType.APPLICATION_JSON)
interface PartyDirectoryRestClient {
    @GET
    fun list(
        @QueryParam("status") status: String,
        @QueryParam("page") page: Int,
        @QueryParam("size") size: Int,
    ): Uni<PartyDirectoryListDto>
}

/** Only what the catch-up needs; `ignoreUnknown` keeps e-mail and other fields out of the heap. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PartyDirectoryItemDto(
    val id: String = "",
    val partyType: String = "",
    val status: String = "",
    val legalName: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PartyDirectoryListDto(val items: List<PartyDirectoryItemDto> = emptyList(), val total: Long = 0)

@ApplicationScoped
class PartyDirectoryClient(@RestClient private val client: PartyDirectoryRestClient) : PartyDirectoryPort {

    override suspend fun listActive(page: Int, size: Int): DirectoryPage {
        val dto = client.list("ACTIVE", page, size).awaitSuspending()
        val items = dto.items.mapNotNull { item ->
            runCatching { UUID.fromString(item.id) }.getOrNull()?.let {
                DirectoryParty(it, item.partyType, item.status, item.legalName.orEmpty().trim())
            }
        }
        return DirectoryPage(items, hasMore = (page.toLong() + 1) * size < dto.total)
    }
}
