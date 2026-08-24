// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sdd.infrastructure.client

import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import java.math.BigDecimal
import java.util.UUID

/**
 * REST client for `openbank-transaction-service` — books the debtor-side debit for an authorised
 * SEPA Direct Debit collection (#1000). OIDC service-to-service auth is propagated by
 * [OidcClientRequestReactiveFilter]; this consumer runs on a plain `@Incoming` suspend handler
 * (not a Temporal activity), so the filter-based token attachment is safe here — unlike
 * domestic-payment's `SettlementAdapter`, which needs an explicit-token workaround specifically
 * because a Temporal activity thread loses the Vert.x context the filter relies on.
 */
@RegisterRestClient(configKey = "transaction-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/transactions")
interface TransactionServiceClient {

    @POST
    fun initiateTransaction(request: InitiateTransactionRequest): Uni<Response>
}

/**
 * Payload sent to transaction-service to book the collection debit. `targetAccountId` is
 * deliberately absent (always external): an SDD creditor is, in the general case, a merchant at
 * another bank — never an internal openbank account — so the debit books against the bank's cash
 * clearing suspense, same as any other external outbound payment (mirrors domestic-payment's
 * `SettlementAdapter`, which resolves a non-null target only for the narrower in-house-transfer
 * case that does not apply to SDD collections).
 */
data class InitiateTransactionRequest(
    val idempotencyKey: String,
    val type: String,
    val sourceAccountId: UUID,
    val amount: BigDecimal,
    val currencyCode: String,
    val description: String,
    val valueDate: String,
    val rail: String,
    val instructionType: String,
)
