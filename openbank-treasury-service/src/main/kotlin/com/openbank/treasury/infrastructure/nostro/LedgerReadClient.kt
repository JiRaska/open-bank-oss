// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.nostro

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.web.SyntheticTaintClientFilter
import com.openbank.treasury.application.port.out.LedgerReadPort
import com.openbank.treasury.application.port.out.LedgerUnavailableException
import com.openbank.treasury.domain.model.LedgerNostroLine
import com.openbank.treasury.domain.model.Side
import com.openbank.treasury.domain.model.TreasuryChart
import io.quarkus.oidc.client.filter.OidcClientFilter
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.WebApplicationException
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
    @Path("/accounts/{code}/balance")
    fun accountBalance(
        @PathParam("code") code: String,
        @QueryParam("asOf") asOf: String,
        @QueryParam("currency") currency: String,
    ): Uni<AccountBalanceView>
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

/** ledger's AccountCurrencyBalanceResponse: native amounts in [currency], `net` = debit − credit. */
data class AccountBalanceView(
    val code: String,
    val currency: String,
    val asOf: String,
    val scope: String? = null,
    val debit: BigDecimal,
    val credit: BigDecimal,
    val net: BigDecimal,
)

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
     * REAL_ONLY (the ledger default): canary activity never reaches a real correspondent. Native
     * amounts in [currency] (#11107), so a EUR nostro gets a EUR figure. The echo is checked so a
     * balance for some other account, currency or date can never be compared against the statement.
     *
     * A 404 is TWO different answers and only one of them means "not stated": ledger's own
     * unknown-account body (`{"error":"GL account <code> not found"}`, GlAccountNotFoundExceptionMapper)
     * is the ledger saying it does not hold the GL. Any other 404 — above all a ledger that does not
     * serve this route yet, because treasury deployed first — is an upstream failure, never a NULL
     * with a false reason: that would blank CZK balances that worked before (#11113 review).
     */
    override suspend fun accountBalance(glCode: String, currency: String, asOf: LocalDate): BigDecimal? {
        val view = try {
            client.accountBalance(glCode, asOf.toString(), currency).awaitSuspending()
        } catch (e: WebApplicationException) {
            val status = e.response?.status
            if (status == NOT_FOUND) {
                val body = runCatching { e.response.readEntity(String::class.java) }.getOrNull()
                if (isUnknownAccount(body, glCode)) return null
            }
            throw LedgerUnavailableException(
                "ledger answered $status for GET /api/v1/journals/accounts/$glCode/balance — " +
                    "not its unknown-account answer; is the ledger serving the native-balance route?",
                e,
            )
        }
        check(view.code == glCode && view.currency == currency && view.asOf == asOf.toString()) {
            "ledger answered a balance for ${view.code}/${view.currency}/${view.asOf}, asked $glCode/$currency/$asOf"
        }
        return view.net
    }

    internal companion object {
        const val PAGE_SIZE = 200
        const val MAX_PAGES = 50
        const val NOT_FOUND = 404
        val BOOKED = setOf("POSTED", "REVERSED")
        private val JSON = ObjectMapper()

        /** true only for ledger's unknown-account body naming exactly [glCode]. */
        fun isUnknownAccount(body: String?, glCode: String): Boolean {
            if (body.isNullOrBlank()) return false
            val error = runCatching { JSON.readTree(body)?.get("error")?.asText() }.getOrNull()
            return error == "GL account $glCode not found"
        }
    }
}
