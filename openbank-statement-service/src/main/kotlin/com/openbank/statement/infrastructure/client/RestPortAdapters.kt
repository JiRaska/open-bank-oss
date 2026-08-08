// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.statement.infrastructure.client

import com.openbank.statement.application.port.out.AccountInfoPort
import com.openbank.statement.application.port.out.BalancePort
import com.openbank.statement.application.port.out.BookedEntryPort
import com.openbank.statement.application.port.out.DocumentServiceException
import com.openbank.statement.application.port.out.DocumentTemplatePort
import com.openbank.statement.application.port.out.PocketAccountInfo
import com.openbank.statement.application.port.out.RenderedDocument
import com.openbank.statement.application.usecase.NotViableAccountException
import com.openbank.statement.domain.model.BalanceAnchor
import com.openbank.statement.domain.model.CreditDebit
import com.openbank.statement.domain.model.StatementEntry
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Maps transaction-service DTOs into the framework-free [StatementEntry] the domain works with.
 * Sign convention: a `DBIT` direction (or a negative amount) becomes [CreditDebit.DBIT]; the entry
 * amount the domain carries is always non-negative (ADR-0035 §E).
 */
@ApplicationScoped
class BookedEntryRestAdapter @Inject constructor(@RestClient private val client: TransactionRestClient) :
    BookedEntryPort {

    override fun bookedEntries(
        accountId: UUID,
        currency: String,
        from: LocalDate,
        to: LocalDate,
    ): Uni<List<StatementEntry>> =
        // transaction-service search returns a {"data":[...]} envelope of entries touching the account
        // (no currency filter param), so filter to this pocket's currency client-side. The endpoint is
        // paged (limit defaults to 50, coerced to max 200) — page through the whole window, or a busy
        // pocket's statement would be silently truncated and could never pass reconciliation.
        fetchAllPages(accountId, from.toString(), to.toString(), offset = 0, acc = emptyList()).map { dtos ->
            dtos.filter { it.currencyCode == null || it.currencyCode == currency }
                .map { it.toEntry(accountId, currency) }
        }

    // Accumulates pages in upstream order (transaction-service sorts by initiatedAt desc; concatenating
    // consecutive offsets preserves that ordering) until a short page signals the end. Fail-closed: if
    // the window somehow exceeds MAX_ENTRIES the Uni fails loudly — a truncated statement must never be
    // minted silently (the close records the pocket as failed and a later run retries).
    private fun fetchAllPages(
        accountId: UUID,
        from: String,
        to: String,
        offset: Int,
        acc: List<TransactionDto>,
    ): Uni<List<TransactionDto>> =
        client.searchByAccount(accountId, from, to, "COMPLETED", PAGE_SIZE, offset).flatMap { resp ->
            val all = acc + resp.data
            when {
                resp.data.size < PAGE_SIZE -> Uni.createFrom().item(all)
                all.size >= MAX_ENTRIES -> Uni.createFrom().failure(
                    IllegalStateException(
                        "booked-entry pagination exceeded $MAX_ENTRIES entries for account $accountId " +
                            "($from..$to) — refusing to mint a possibly-truncated statement",
                    ),
                )
                else -> fetchAllPages(accountId, from, to, offset + PAGE_SIZE, all)
            }
        }

    private companion object {
        /** transaction-service coerces `limit` into 1..200 — ask for the max to minimize round-trips. */
        const val PAGE_SIZE = 200

        /** Hard cap (100 pages) guarding against a non-terminating upstream; far beyond any real month. */
        const val MAX_ENTRIES = 20_000
    }

    private fun TransactionDto.toEntry(accountId: UUID, pocketCurrency: String): StatementEntry {
        val raw = amount ?: BigDecimal.ZERO
        // Direction is per-account: an entry whose target is this account is incoming (CRDT), whose
        // source is this account is outgoing (DBIT). Fall back to the signed amount.
        val acct = accountId.toString()
        val cd = when {
            targetAccountId == acct -> CreditDebit.CRDT
            sourceAccountId == acct -> CreditDebit.DBIT
            raw.signum() < 0 -> CreditDebit.DBIT
            else -> CreditDebit.CRDT
        }
        return StatementEntry(
            entryRef = referenceNumber ?: "",
            amount = raw.abs(),
            currency = currencyCode ?: pocketCurrency,
            creditDebit = cd,
            bookingDate = bookingDate?.let(LocalDate::parse) ?: LocalDate.EPOCH,
            valueDate = valueDate?.let(LocalDate::parse) ?: (bookingDate?.let(LocalDate::parse) ?: LocalDate.EPOCH),
            description = description ?: (type ?: ""),
            counterparty = null,
        )
    }
}

@ApplicationScoped
class BalanceRestAdapter @Inject constructor(@RestClient private val client: BalanceRestClient) : BalancePort {

    override fun closingBalance(accountId: UUID, currency: String, asOf: LocalDate): Uni<BalanceAnchor> =
        // Point-in-time read (balance-service >= 1.3.0): pass `asOf` so the upstream rewinds the booked
        // balance to the end-of-day balance as of that date. This anchors statement opening/closing at
        // the true period boundaries; with the upstream projection ledger empty it degrades to the
        // current balance (unchanged from prior behavior).
        client.pocketBalance(accountId, currency, asOf.toString()).map { dto ->
            BalanceAnchor(dto.bookedAmount ?: BigDecimal.ZERO, dto.currency ?: currency, asOf)
        }
}

@ApplicationScoped
class AccountInfoRestAdapter @Inject constructor(
    @RestClient private val client: AccountRestClient,
    @RestClient private val partyClient: PartyRestClient,
) : AccountInfoPort {

    override fun pocketAccount(accountId: UUID): Uni<PocketAccountInfo> = client.account(accountId).chain { acct ->
        // account-service's account-by-id returns a single primary `currencyCode`, not the pocket
        // set, so enumerate the real ACTIVE currency pockets via /pockets (ADR-0024). The IBAN is the
        // account's `accountNumber`; the holder name lives in party-service (resolved via partyId)
        // — without these the rendered statement showed a blank IBAN and empty holder.
        val iban = acct.accountNumber.orEmpty()
        if (iban.isBlank()) {
            return@chain Uni.createFrom().failure(
                NotViableAccountException(accountId, "blank IBAN — debris account (#862)"),
            )
        }
        client.pockets(accountId).chain { resp ->
            val activeCurrencies = resp.pockets.filter { it.status == "ACTIVE" }.mapNotNull { it.currencyCode }
            if (activeCurrencies.isEmpty()) {
                return@chain Uni.createFrom().failure(
                    NotViableAccountException(accountId, "no active pockets — debris account (#862)"),
                )
            }
            holderName(acct.partyId).map { holder ->
                PocketAccountInfo(
                    accountId = acct.id ?: accountId,
                    iban = iban,
                    holderName = holder,
                    currencies = activeCurrencies,
                )
            }
        }
    }

    // Resolve the holder's legal name from party-service. Degrades to an empty name (rather than
    // failing the whole statement render) if the party can't be resolved — the IBAN + amounts still
    // render; a missing name is a soft gap, not a reason to deny the document.
    private fun holderName(partyId: UUID?): Uni<String> = if (partyId == null) {
        Uni.createFrom().item("")
    } else {
        partyClient.party(partyId)
            .map { it.legalName ?: "" }
            .onFailure().recoverWithItem("")
    }
}

/**
 * Calls document-service's non-persisting template preview flow (ADR-0248): list the PUBLISHED
 * templates to find the requested code's `bodyHtml` (there is no get-by-code route), then merge the
 * Handlebars data into it via `/api/v1/documents/templates/preview`. Neither call persists anything;
 * a failure at either step degrades only the caller's one endpoint, never the rest of
 * statement-service (fail-closed reconciliation, camt.053/MT940/PDF render are unaffected).
 */
@ApplicationScoped
class DocumentTemplateRestAdapter @Inject constructor(@RestClient private val client: DocumentRestClient) :
    DocumentTemplatePort {

    override fun renderTemplate(templateCode: String, data: Map<String, Any?>): Uni<RenderedDocument> =
        client.listTemplates(TEMPLATE_LIST_LIMIT)
            .onFailure().transform { e ->
                DocumentServiceException("document-service template list call failed for $templateCode", e)
            }
            .flatMap { templates -> renderFromTemplate(templateCode, data, templates) }

    private fun renderFromTemplate(
        templateCode: String,
        data: Map<String, Any?>,
        templates: List<DocumentTemplateDto>,
    ): Uni<RenderedDocument> {
        val bodyHtml = templates.firstOrNull { it.code == templateCode && it.status == "PUBLISHED" }?.bodyHtml
        return if (bodyHtml == null) {
            Uni.createFrom().failure(
                DocumentServiceException("no PUBLISHED document-service template found for code=$templateCode"),
            )
        } else {
            client.preview(PreviewTemplateRequestDto(bodyHtml, data))
                .onFailure().transform { e ->
                    DocumentServiceException("document-service preview call failed for $templateCode", e)
                }
                .map { resp ->
                    RenderedDocument(contentType = "text/html; charset=utf-8", body = resp.renderedHtml.orEmpty())
                }
        }
    }

    private companion object {
        /** The API max (`GET /templates` is bounded, not cursor-paginated) — plenty for the small,
         *  fixed set of PUBLISHED templates this platform seeds. */
        const val TEMPLATE_LIST_LIMIT = 200
    }
}
