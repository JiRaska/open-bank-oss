// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import java.util.UUID

/**
 * Typed client for transaction-service's list-by-account endpoint, used by the GDPR Art. 20
 * portability export (ADR-0204 D2) to assemble the subject's transaction history.
 * `GET /api/v1/transactions?accountId=` is `@RolesAllowed(API/VIEWER/OPERATOR/ADMIN)`, so the
 * call carries the same M2M bearer as the other party-service outbound clients
 * ([OidcClientRequestReactiveFilter] on the `openbank-services` client_credentials grant).
 */
@RegisterRestClient(configKey = "transaction-service")
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/transactions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface TransactionServiceRestClient {

    @GET
    fun listByAccount(
        @QueryParam("accountId") accountId: UUID,
        @QueryParam("limit") limit: Int,
    ): Uni<TransactionPageResponse>
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class TransactionPageResponse(
    val items: List<TransactionItemResponse> = emptyList(),
    val data: List<TransactionItemResponse> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TransactionItemResponse(
    val id: String? = null,
    val transactionId: String? = null,
    val bookingDate: String? = null,
    val amount: String? = null,
    val currency: String? = null,
    val type: String? = null,
    val status: String? = null,
    val counterpartyName: String? = null,
    val targetIban: String? = null,
    val sourceIban: String? = null,
    val remittanceInfo: String? = null,
    val endToEndId: String? = null,
)
