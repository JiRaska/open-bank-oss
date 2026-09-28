// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.infrastructure.client

import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.filter.OidcClientFilter
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import java.math.BigDecimal
import java.util.UUID

/**
 * REST client for `openbank-transaction-service` — books the withholding-tax remittance cash leg
 * to the finanční úřad (#999). OIDC service-to-service auth is propagated by
 * the NAMED oidc-client `ledger` (interest-service's own Keycloak client `openbank-interest`,
 * ROLE_API only; #10486 batch 2 - the name predates this second use); this consumer runs on a plain reactive `@Incoming` handler
 * (not a Temporal activity), so the filter-based token attachment is safe here — same reasoning
 * as sdd-service's `TransactionServiceClient`.
 */
@RegisterRestClient(configKey = "transaction-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
// #10486 batch 2: same per-service identity as LedgerRestClient - transaction-service's OPA grants
// `service-account-openbank-interest` transaction.create by identity, never the shared client.
@OidcClientFilter("ledger")
@Path("/api/v1/transactions")
interface TransactionServiceClient {

    @POST
    fun initiateTransaction(request: InitiateTransactionRequest): Uni<Response>
}

/**
 * Payload sent to transaction-service to book the remittance debit. `targetAccountId` is
 * deliberately absent: the finanční úřad is external (never an internal openbank account), so the
 * debit books against the bank's cash clearing suspense — same shape as sdd-service's collection
 * debit and domestic-payment's external-transfer case.
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
