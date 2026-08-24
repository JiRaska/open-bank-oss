// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.client

import com.openbank.libs.web.SyntheticTaintClientFilter
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import java.math.BigDecimal
import java.util.UUID

/**
 * REST client for openbank-transaction-service — initiates a debit booking for a domestic
 * payment after the scheme returns ACSC (ADR-0108). Authorization is passed explicitly as an
 * `Authorization` header (Bearer token) rather than via [OidcClientRequestReactiveFilter]:
 * the filter does not attach a token when called from a Temporal activity running on a
 * duplicated Vert.x context, causing 401s (same root cause as ADR-0104 BUG #3 for SEPA).
 */
@RegisterRestClient(configKey = "transaction-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@Path("/api/v1/transactions")
interface TransactionServiceClient {

    @POST
    fun initiateTransaction(
        @HeaderParam("Authorization") authorization: String,
        request: InitiateSettlementRequest,
    ): Uni<Response>
}

/** Payload sent to transaction-service to debit the debtor account and book the transfer. */
data class InitiateSettlementRequest(
    val idempotencyKey: String,
    val type: String,
    val sourceAccountId: UUID,
    // The internal creditor account to CREDIT. When present, transaction-service books a two-sided
    // journal (debit source + credit target) so the payee actually receives the money; when null
    // (external transfer) the debit goes to the bank cash-clearing suspense as before.
    val targetAccountId: UUID? = null,
    val amount: BigDecimal,
    val currencyCode: String,
    val description: String,
    val valueDate: String,
    val rail: String,
    val instructionType: String? = null,
)
