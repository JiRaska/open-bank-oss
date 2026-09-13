// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.infrastructure.reconcile

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import java.time.Instant
import java.util.UUID

/**
 * Read-only fleet sweep over account-service's registry, used by [AccountInitialLoadSource].
 *
 * `/api/v1/accounts/active` is not a new surface invented for analytics: it is the staff/service
 * sweep ADR-0143 added so billing-service's cycle scheduler can discover its batch, and it is
 * already cursor-paginated with a 200 page cap. Reusing it keeps analytics out of another service's
 * database, which is what a direct SQL read would amount to.
 *
 * The M2M token is attached by [OidcClientRequestReactiveFilter], the same way balance-service's
 * `AccountServiceClient` authenticates against this service — the endpoint requires a staff or
 * service role and refuses an anonymous caller.
 */
@RegisterRestClient(configKey = "account-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/accounts")
@Produces(MediaType.APPLICATION_JSON)
interface AccountRegistryClient {

    @GET
    @Path("/active")
    fun listActive(@QueryParam("limit") limit: Int, @QueryParam("cursor") cursor: String?): Uni<AccountRegistryPage>
}

/**
 * One account as the registry reports it.
 *
 * `currencyCode` is the API's spelling and `currency` is the event's; the projection maps between
 * them explicitly. Leaving that to a same-name coincidence is how a field arrives empty and every
 * downstream row looks merely unremarkable.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class AccountRegistryEntry(
    val id: UUID,
    val accountNumber: String,
    val accountType: String,
    val partyId: UUID,
    val productId: String,
    val currencyCode: String,
    val openedAt: Instant,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AccountRegistryPagination(val nextCursor: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AccountRegistryPage(
    val data: List<AccountRegistryEntry> = emptyList(),
    val pagination: AccountRegistryPagination? = null,
)
