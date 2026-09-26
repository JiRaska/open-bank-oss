// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepainstant.infrastructure.client

import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.filter.OidcClientFilter
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import java.math.BigDecimal
import java.util.UUID

/**
 * RestClient binding to `openbank-transaction-service` (`POST /api/v1/transactions`).
 * Uses the named oidc-client `m2m` (sepa-instant's own Keycloak client, #10486) for M2M token
 * injection; [AmlServiceClient] selects the same named client since #10486 batch 3. Idempotent on the `Idempotency-Key` header.
 */
@RegisterRestClient(configKey = "transaction-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
// #10486: this client's bearer is minted by the NAMED oidc-client `m2m` - Keycloak client
// `openbank-sepa-instant` (ROLE_API only) - never the shared `openbank-services` default client.
@OidcClientFilter("m2m")
@Path("/api/v1/transactions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface TransactionServiceClient {

    @POST
    fun initiateTransaction(
        @HeaderParam("Idempotency-Key") idempotencyKey: String,
        request: InitiateSettlementRequest,
    ): Uni<Response>
}

/** Wire representation of a debit settlement request sent to transaction-service. */
data class InitiateSettlementRequest(
    val idempotencyKey: String,
    val type: String,
    val sourceAccountId: UUID,
    val amount: BigDecimal,
    val currencyCode: String,
    val description: String,
    val valueDate: String,
    val rail: String,
    val instructionType: String? = null,
)
