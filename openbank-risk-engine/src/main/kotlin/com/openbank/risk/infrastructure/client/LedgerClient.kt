// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.infrastructure.client

import com.openbank.libs.web.SyntheticTaintClientFilter
import com.openbank.risk.application.port.out.LedgerPort
import com.openbank.risk.domain.model.LedgerInputs
import com.openbank.risk.domain.model.SubLedgerBalance
import com.openbank.risk.domain.model.TrialBalanceLine
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Outbound client for openbank-ledger-service's two read views, authenticated M2M with the
 * shared `openbank-services` client-credentials token ([OidcClientRequestReactiveFilter], same
 * wiring as finrep-service's LedgerClient). Paths are ledger's published ones
 * (`openapi.yaml`: `/api/v1/journals/trial-balance`, `/api/v1/journals/sub-ledger-balances`).
 */
@RegisterRestClient(configKey = "ledger-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/journals")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface LedgerRestClient {

    @GET
    @Path("/trial-balance")
    fun trialBalance(@QueryParam("asOf") asOf: String, @QueryParam("scope") scope: String): Uni<TrialBalanceResponse>

    @GET
    @Path("/sub-ledger-balances")
    fun subLedgerBalances(@QueryParam("asOf") asOf: String): Uni<SubLedgerBalancesResponse>
}

data class TrialBalanceLineResponse(
    val code: String,
    val type: String,
    val currency: String,
    val totalDebit: BigDecimal,
    val totalCredit: BigDecimal,
    val net: BigDecimal,
)

data class TrialBalanceResponse(val asOf: String, val scope: String?, val lines: List<TrialBalanceLineResponse>)

data class SubLedgerBalanceResponse(
    val subAccountId: UUID,
    val currency: String,
    val totalDebit: BigDecimal,
    val totalCredit: BigDecimal,
)

data class SubLedgerBalancesResponse(val asOf: String, val balances: List<SubLedgerBalanceResponse>)

@ApplicationScoped
class LedgerAdapter(@RestClient private val client: LedgerRestClient) : LedgerPort {

    /**
     * The trial balance is requested with `scope=ALL`, not ledger's default `REAL_ONLY`, and that
     * is forced by the other endpoint: `sub-ledger-balances` has no scope parameter and counts
     * every booked line. Comparing a REAL_ONLY trial balance against an unscoped sub-ledger would
     * report every synthetic (ADR-0252 canary) posting on a deposit-control account as a break.
     * The two reads must describe the same population for the tie-out to mean anything; the
     * run's `provenance` is what says which world it is about.
     */
    override suspend fun read(asOf: LocalDate): LedgerInputs {
        val tb = client.trialBalance(asOf.toString(), SCOPE_ALL).awaitSuspending()
        val sl = client.subLedgerBalances(asOf.toString()).awaitSuspending()
        return LedgerInputs(
            asOf = asOf,
            trialBalance = tb.lines.map {
                TrialBalanceLine(it.code, it.type, it.currency, it.totalDebit, it.totalCredit, it.net)
            },
            subLedger = sl.balances.map {
                SubLedgerBalance(it.subAccountId, it.currency, it.totalDebit, it.totalCredit)
            },
        )
    }

    private companion object {
        const val SCOPE_ALL = "ALL"
    }
}
