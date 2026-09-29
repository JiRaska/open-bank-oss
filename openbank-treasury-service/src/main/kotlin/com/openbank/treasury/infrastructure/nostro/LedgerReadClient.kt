// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.nostro

import com.openbank.libs.web.SyntheticTaintClientFilter
import com.openbank.treasury.application.port.out.LedgerReadPort
import com.openbank.treasury.domain.model.LedgerNostroLine
import com.openbank.treasury.domain.model.Side
import com.openbank.treasury.domain.model.TreasuryChart
import io.quarkus.oidc.client.filter.OidcClientFilter
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
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
 * ledger-service's journal READ API, same base URL and the same `m2m` identity as the posting
 * client. ledger_rest_ext.rego grants `service-account-openbank-treasury` ledger.list + ledger.read
 * for this (#10896) — it is an outbound interface, so its non-null params are caller-supplied.
 */
@RegisterRestClient(configKey = "ledger-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@OidcClientFilter("m2m")
@Path("/api/v1/journals")
@Produces(MediaType.APPLICATION_JSON)
interface LedgerReadRestClient {
    @GET
    fun list(
        @QueryParam("fromDate") fromDate: String,
        @QueryParam("toDate") toDate: String,
        @QueryParam("limit") limit: Int,
        @QueryParam("cursor") cursor: String?,
    ): Uni<JournalPage>

    @GET
    @Path("/trial-balance")
    fun trialBalance(@QueryParam("asOf") asOf: String): Uni<TrialBalanceView>
}

data class JournalPage(val data: List<JournalView> = emptyList(), val pagination: PageView? = null)

data class PageView(val hasNextPage: Boolean = false, val nextCursor: String? = null)

data class JournalView(
    val id: UUID,
    val transactionId: UUID,
    val entryDate: String,
    val description: String? = null,
    val status: String,
    val synthetic: Boolean = false,
    val lines: List<JournalLineView> = emptyList(),
)

data class JournalLineView(
    val id: UUID,
    val glAccountId: UUID,
    val side: String,
    val amount: BigDecimal,
    val currencyCode: String,
)

data class TrialBalanceView(val lines: List<TrialBalanceLineView> = emptyList())

data class TrialBalanceLineView(val glAccountId: UUID, val net: BigDecimal)

@ApplicationScoped
class LedgerReadAdapter(@RestClient private val client: LedgerReadRestClient) : LedgerReadPort {

    override suspend fun nostroLines(glCode: String, from: LocalDate, to: LocalDate): List<LedgerNostroLine> {
        val glId = TreasuryChart.glAccountId(glCode)
        val out = mutableListOf<LedgerNostroLine>()
        var cursor: String? = null
        var pages = 0
        do {
            val page = client.list(from.toString(), to.toString(), PAGE_SIZE, cursor).awaitSuspending()
            page.data
                .filter { it.status in BOOKED && !it.synthetic }
                .forEach { j ->
                    j.lines.filter { it.glAccountId == glId }.forEach { l ->
                        out += LedgerNostroLine(
                            journalId = j.id,
                            lineId = l.id,
                            transactionId = j.transactionId,
                            entryDate = LocalDate.parse(j.entryDate),
                            side = Side.valueOf(l.side),
                            amount = l.amount,
                            currency = l.currencyCode,
                            description = j.description,
                        )
                    }
                }
            cursor = page.pagination?.nextCursor?.takeIf { page.pagination.hasNextPage }
            check(++pages <= MAX_PAGES) { "ledger returned more than ${MAX_PAGES * PAGE_SIZE} journals for $from..$to" }
        } while (cursor != null)
        return out
    }

    /**
     * REAL_ONLY (the ledger default): canary activity never reaches a real correspondent. The
     * trial balance sums `base_amount`, so this is a CZK figure whatever the GL's currency; the
     * reconciliation service asks only for a base-currency nostro.
     */
    override suspend fun glBalance(glCode: String, asOf: LocalDate): BigDecimal {
        val glId = TreasuryChart.glAccountId(glCode)
        return client.trialBalance(asOf.toString()).awaitSuspending().lines
            .filter { it.glAccountId == glId }
            .fold(BigDecimal.ZERO) { acc, l -> acc.add(l.net) }
    }

    private companion object {
        const val PAGE_SIZE = 200
        const val MAX_PAGES = 50
        val BOOKED = setOf("POSTED", "REVERSED")
    }
}
