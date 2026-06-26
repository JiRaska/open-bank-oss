// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
package com.openbank.statement.infrastructure.client

import com.openbank.statement.application.port.out.AccountInfoPort
import com.openbank.statement.application.port.out.BalancePort
import com.openbank.statement.application.port.out.BookedEntryPort
import com.openbank.statement.application.port.out.PocketAccountInfo
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
        // (no currency filter param), so filter to this pocket's currency client-side.
        client.searchByAccount(accountId, from.toString(), to.toString(), "COMPLETED").map { resp ->
            resp.data.filter { it.currencyCode == null || it.currencyCode == currency }
                .map { it.toEntry(accountId, currency) }
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
