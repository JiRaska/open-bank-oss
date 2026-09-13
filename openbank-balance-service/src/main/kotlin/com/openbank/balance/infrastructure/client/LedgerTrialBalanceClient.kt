// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import java.math.BigDecimal

/**
 * RestClient binding to `openbank-ledger-service` for the trial balance
 * (`GET /api/v1/journals/trial-balance?asOf=…`), used by the ADR-0039 Phase A reconciliation to read
 * the per-currency deposit-control balances. Carries the service OIDC token via the reactive client
 * filter, like the other inter-service clients.
 */
@RegisterRestClient(configKey = "ledger-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/journals")
@Produces(MediaType.APPLICATION_JSON)
interface LedgerTrialBalanceClient {

    @GET
    @Path("/trial-balance")
    fun trialBalance(@QueryParam("asOf") asOf: String): Uni<TrialBalanceClientResponse>
}

/** Subset of the ledger trial-balance payload the reconciliation needs. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class TrialBalanceClientResponse(
    val asOf: String? = null,
    val balanced: Boolean? = null,
    val lines: List<TrialBalanceClientLine> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TrialBalanceClientLine(
    val code: String? = null,
    val currency: String? = null,
    val totalDebit: BigDecimal? = null,
    val totalCredit: BigDecimal? = null,
)
