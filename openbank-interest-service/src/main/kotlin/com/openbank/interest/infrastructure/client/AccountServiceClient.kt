// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import java.math.BigDecimal
import java.util.UUID

/**
 * REST client for `openbank-account-service`, used by the daily accrual run to discover accounts
 * and read balances. OIDC service-to-service auth is attached by [OidcClientRequestReactiveFilter]
 * (the `openbank-services` M2M token, ROLE_API — the same principal billing-service uses for
 * these exact endpoints, so no new OPA policy is required).
 */
@RegisterRestClient(configKey = "account-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Produces(MediaType.APPLICATION_JSON)
@Path("/api/v1/accounts")
interface AccountServiceClient {

    /** Fleet-wide ACTIVE accounts, cursor-paginated (ADR-0143 discovery read). */
    @GET
    @Path("/active")
    fun listActive(
        @QueryParam("limit") @DefaultValue("100") limit: Int,
        @QueryParam("cursor") cursor: String?,
    ): Uni<ActiveAccountsClientResponse>

    /** Booked/available/reserved balance for one account. */
    @GET
    @Path("/{accountId}/balance")
    fun getBalance(@PathParam("accountId") accountId: UUID): Uni<AccountBalanceClientResponse>
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class ActiveAccountsClientResponse(
    val data: List<AccountClientResponse> = emptyList(),
    val pagination: PageInfoClientResponse = PageInfoClientResponse(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AccountClientResponse(val id: UUID, val productId: UUID, val accountType: String, val currencyCode: String)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PageInfoClientResponse(val hasNextPage: Boolean = false, val nextCursor: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AccountBalanceClientResponse(
    val accountId: UUID? = null,
    val currentBalance: BigDecimal? = null,
    val currencyCode: String? = null,
)
