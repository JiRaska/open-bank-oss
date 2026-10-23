// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.client

import com.fasterxml.jackson.annotation.JsonInclude
import com.openbank.libs.close.AccountingDayClosed
import com.openbank.libs.web.SyntheticTaintClientFilter
import com.openbank.treasury.application.port.out.AccountingDayClosedException
import com.openbank.treasury.application.port.out.LedgerPostingPort
import com.openbank.treasury.application.port.out.PostedJournal
import com.openbank.treasury.domain.model.JournalSpec
import com.openbank.treasury.domain.model.ResolvedAccount
import com.openbank.treasury.domain.model.Side
import io.quarkus.oidc.client.filter.OidcClientFilter
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.config.inject.ConfigProperty
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

/**
 * A leg names its account ONE way: [glAccountId] (explicit: the rollout shim, or a reversal pinned to
 * the original account) or [accountingClass] + [accountingRole] (the ledger resolves it). Absent
 * fields are omitted from the JSON, so an old ledger sees exactly the payload it always did.
 */
data class JournalLineRequest(
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val glAccountId: UUID?,
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val accountingClass: String?,
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val accountingRole: String?,
    val side: String,
    val amount: BigDecimal,
    val currencyCode: String,
    val fxRate: BigDecimal?,
    val baseAmount: BigDecimal,
    val baseCurrencyCode: String,
)

data class JournalResponse(
    val id: UUID,
    val transactionId: UUID,
    val status: String,
    val lines: List<JournalLineResponse> = emptyList(),
)

data class JournalLineResponse(val glAccountId: UUID, val sequence: Int = 0)

@ApplicationScoped
class LedgerPostingAdapter(
    @RestClient private val client: LedgerRestClient,
    /**
     * `openbank.treasury.ledger.post-by-class`. FALSE (default) keeps the explicit-`glAccountId`
     * payload every ledger understands, so deploying treasury before the ledger that serves
     * accounting classes cannot fail a posting. Set TRUE once that ledger is live (ADR-0341 D3).
     */
    @ConfigProperty(name = "openbank.treasury.ledger.post-by-class", defaultValue = "false")
    private val postByClass: Boolean,
) : LedgerPostingPort {

    /**
     * `transactionId` is the deal id, so `GET /api/v1/journals/transaction/{dealId}` lists every
     * journal a deal produced. Lines are single-currency (base = transaction currency, no FX).
     */
    override suspend fun post(spec: JournalSpec, entryDate: LocalDate, description: String): PostedJournal {
        val request = PostJournalRequest(
            idempotencyKey = spec.idempotencyKey,
            transactionId = spec.dealId,
            entryDate = entryDate.toString(),
            valueDate = entryDate.toString(),
            description = description,
            lines = spec.lines.map {
                val explicit = it.glAccountId ?: if (postByClass) null else LegacyGlChart.glAccountId(it)
                JournalLineRequest(
                    glAccountId = explicit,
                    accountingClass = it.accountingClass.takeIf { explicit == null },
                    accountingRole = it.role.name.takeIf { explicit == null },
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
        val response = try {
            client.postJournal(request).awaitSuspending()
        } catch (e: WebApplicationException) {
            val status = e.response?.status ?: 0
            val body = runCatching { e.response?.readEntity(String::class.java) }.getOrNull()
            if (AccountingDayClosed.isClosedDayRejection(status, body)) {
                throw AccountingDayClosedException("ledger day $entryDate is closed: ${spec.idempotencyKey} refused", e)
            }
            throw e
        }
        return PostedJournal(response.id, resolvedAccounts(spec, response))
    }

    /**
     * Where each leg landed, from the ledger's own answer (on an idempotent replay: the ORIGINAL
     * entry's accounts). Legs come back in `sequence` order, which is the order they were sent.
     * A count mismatch records nothing rather than guessing a pairing.
     */
    private fun resolvedAccounts(spec: JournalSpec, response: JournalResponse): List<ResolvedAccount> {
        val answered = response.lines.sortedBy { it.sequence }
        if (answered.size != spec.lines.size) return emptyList()
        return spec.lines.zip(answered).map { (line, resolved) ->
            ResolvedAccount(line.accountingClass, line.role, line.currency, resolved.glAccountId)
        }
    }

    companion object {
        /** Stable actor id the ledger records for treasury postings (the human approver is on the deal). */
        val SYSTEM_ACTOR: UUID = UUID.nameUUIDFromBytes("openbank-treasury-service".toByteArray())
    }
}
