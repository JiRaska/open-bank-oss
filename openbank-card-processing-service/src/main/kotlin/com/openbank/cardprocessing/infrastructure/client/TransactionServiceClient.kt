// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.infrastructure.client

import io.quarkus.oidc.client.filter.OidcClientFilter
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import java.math.BigDecimal
import java.util.UUID

/**
 * Where cleared card spend becomes money in the books.
 *
 * The same shape every other rail uses (`InitiateTransactionRequest`), because card spend is a
 * rail and not a special case: `rail = CARD`, `instructionType = ONE_OFF` (ADR-0103 D2 vocabulary).
 */
@Path("/api/v1/transactions")
@RegisterRestClient(configKey = "transaction-api")
@OidcClientFilter
@Produces(MediaType.APPLICATION_JSON)
interface TransactionServiceClient {

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    suspend fun initiate(request: InitiateTransactionRequest): TransactionResponse
}

data class InitiateTransactionRequest(
    val idempotencyKey: String,
    val type: String,
    val sourceAccountId: UUID?,
    val targetAccountId: UUID?,
    val amount: BigDecimal,
    val currencyCode: String,
    val description: String?,
    val valueDate: String,
    val rail: String?,
    val instructionType: String?,
)

data class TransactionResponse(val id: UUID? = null, val status: String? = null)
