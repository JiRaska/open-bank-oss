// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.infrastructure.client

import com.openbank.libs.web.SyntheticTaintClientFilter
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import java.math.BigDecimal
import java.util.UUID

@RegisterRestClient(configKey = "transaction-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@Path("/api/v1/transactions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface TransactionServiceClient {

    @POST
    fun initiateTransaction(
        @HeaderParam("Authorization") authorization: String,
        request: InitiateSettlementRequest,
    ): Uni<Response>

    @POST
    @Path("/{transactionId}/reverse")
    fun reverseTransaction(
        @HeaderParam("Authorization") authorization: String,
        @PathParam("transactionId") transactionId: UUID,
        request: ReverseTransactionRequest,
    ): Uni<Response>
}

data class ReverseTransactionRequest(val idempotencyKey: String, val reason: String)

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
