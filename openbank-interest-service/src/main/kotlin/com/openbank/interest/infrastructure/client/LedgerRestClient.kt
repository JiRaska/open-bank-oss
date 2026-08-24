// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.infrastructure.client

import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import java.math.BigDecimal
import java.util.UUID

/**
 * Typed client for the ledger-service journal API (ADR-0033 §D). Interest-service never mutates
 * balances itself: it hands the ledger a balanced double-entry journal and the ledger is the single
 * system of record for money movements. `POST /api/v1/journals` is the platform's only ledger
 * ingestion surface — the same contract transaction-service, billing-service and lending-service use.
 *
 * A service bearer token is injected by [OidcClientRequestReactiveFilter]; the call is guarded by
 * [LedgerCallGuard] (retry/timeout/circuit-breaker).
 */
@RegisterRestClient(configKey = "ledger-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/journals")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface LedgerRestClient {

    @POST
    fun postJournal(request: PostJournalRequest): Uni<JournalResponse>
}

data class PostJournalRequest(
    val idempotencyKey: String,
    val transactionId: UUID,
    val entryDate: String,
    val valueDate: String,
    val description: String?,
    val lines: List<JournalLineRequest>,
    val createdBy: UUID,
)

/**
 * One leg. [subAccountId] is the sub-ledger (analytická evidence) dimension and is accepted by the
 * ledger **only** on deposit-control legs (ADR-0039 Phase B); it must be null everywhere else or
 * `LedgerService` rejects the whole entry.
 */
data class JournalLineRequest(
    val glAccountId: UUID,
    val side: String,
    val amount: BigDecimal,
    val currencyCode: String,
    val fxRate: BigDecimal?,
    val baseAmount: BigDecimal,
    val baseCurrencyCode: String,
    val subAccountId: UUID? = null,
)

data class JournalResponse(val id: UUID, val transactionId: UUID, val status: String)
