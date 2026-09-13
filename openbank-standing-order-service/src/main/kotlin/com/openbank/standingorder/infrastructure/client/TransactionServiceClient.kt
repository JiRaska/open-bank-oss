// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.standingorder.infrastructure.client

import com.openbank.libs.web.SyntheticTaintClientFilter
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.Consumes
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
 * REST client for transaction-service's `POST /api/v1/transactions` (#889 follow-up). A
 * `DOMESTIC`/`INTERNAL` order whose creditor IBAN resolves to an account of ours
 * ([AccountServiceClient]) is an own-account or same-bank move, so it books here as `type=TRANSFER`
 * with no `rail` — exactly the signal `com.openbank.libs.domain.payment.SettlementScope` reads as
 * "stays in the bank", giving it same-day value/booking (transaction-service's own fix, #5225)
 * rather than the CERTIS cutoff/calendar a genuinely external CZ payment needs.
 *
 * Idempotency is body-based here (`InitiateTransactionRequest.idempotencyKey`), not a header —
 * `TransactionResource.initiateTransaction` has no `Idempotency-Key` header param, unlike
 * sepa-payment's create endpoint.
 */
@RegisterRestClient(configKey = "transaction-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter::class)
interface TransactionServiceClient {

    @POST
    @Path("/api/v1/transactions")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    fun initiate(request: InitiateTransactionRequest): Uni<Response>
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
)
