// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.client

import com.openbank.libs.web.SyntheticTaintClientFilter
import com.openbank.treasury.application.port.out.LedgerPostingPort
import com.openbank.treasury.domain.model.JournalSpec
import com.openbank.treasury.domain.model.Side
import com.openbank.treasury.domain.model.TreasuryChart
import io.quarkus.oidc.client.filter.OidcClientFilter
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * ledger-service's journal API — the platform's only ledger ingestion surface (ADR-0315 D5).
 *
 * The bearer is minted by the NAMED oidc-client `m2m` — Keycloak client `openbank-treasury`,
 * ROLE_API only — never the shared `openbank-services` client (#10486). ledger_rest_ext.rego grants
 * `service-account-openbank-treasury` exactly `ledger.create`; a reversal is a new offsetting
 * journal under its own idempotency key, so no `ledger.reverse` grant is needed.
 */
@RegisterRestClient(configKey = "ledger-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@OidcClientFilter("m2m")
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

data class JournalLineRequest(
    val glAccountId: UUID,
    val side: String,
    val amount: BigDecimal,
    val currencyCode: String,
    val fxRate: BigDecimal?,
    val baseAmount: BigDecimal,
    val baseCurrencyCode: String,
)

data class JournalResponse(val id: UUID, val transactionId: UUID, val status: String)

@ApplicationScoped
class LedgerPostingAdapter(@RestClient private val client: LedgerRestClient) : LedgerPostingPort {

    /**
     * `transactionId` is the deal id, so `GET /api/v1/journals/transaction/{dealId}` lists every
     * journal a deal produced. Lines are single-currency (base = transaction currency, no FX).
     */
    override suspend fun post(spec: JournalSpec, entryDate: LocalDate, description: String): UUID {
        val request = PostJournalRequest(
            idempotencyKey = spec.idempotencyKey,
            transactionId = spec.dealId,
            entryDate = entryDate.toString(),
            valueDate = entryDate.toString(),
            description = description,
            lines = spec.lines.map {
                JournalLineRequest(
                    glAccountId = TreasuryChart.glAccountId(it.glCode),
                    side = if (it.side == Side.DEBIT) "DEBIT" else "CREDIT",
                    amount = it.amount,
                    currencyCode = it.currency,
                    fxRate = null,
                    baseAmount = it.amount,
                    baseCurrencyCode = it.currency,
                )
            },
            createdBy = SYSTEM_ACTOR,
        )
        return client.postJournal(request).awaitSuspending().id
    }

    companion object {
        /** Stable actor id the ledger records for treasury postings (the human approver is on the deal). */
        val SYSTEM_ACTOR: UUID = UUID.nameUUIDFromBytes("openbank-treasury-service".toByteArray())
    }
}
