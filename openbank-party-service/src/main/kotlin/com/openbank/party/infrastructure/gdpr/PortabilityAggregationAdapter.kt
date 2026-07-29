// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.gdpr

import com.openbank.party.application.port.out.GdprAggregationAuthException
import com.openbank.party.application.port.out.PortabilityAggregationPort
import com.openbank.party.domain.model.PortabilityAccount
import com.openbank.party.domain.model.PortabilityCard
import com.openbank.party.domain.model.PortabilityTransaction
import com.openbank.party.domain.model.redactIban
import com.openbank.party.infrastructure.client.AccountServiceRestClient
import com.openbank.party.infrastructure.client.CardServiceRestClient
import com.openbank.party.infrastructure.client.TransactionServiceRestClient
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.WebApplicationException
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import java.util.UUID

/**
 * GDPR Art. 20 portability aggregation adapter (ADR-0204 D2): assembles the subject's accounts
 * with transaction history and card metadata over the M2M REST clients.
 *
 * Failure handling mirrors [GdprAggregationAdapter] deliberately: 401/403 fails hard (a refused
 * read must never read as "no data"), everything else degrades to an empty slice with a log —
 * a downstream outage must not block the subject's request. The counterparty IBAN is redacted
 * HERE, at the boundary, so no un-redacted counterparty identifier ever reaches the export
 * model (Art. 20(4) — the retained identifier is the bank-code prefix, per ADR-0204 D2).
 */
@ApplicationScoped
class PortabilityAggregationAdapter(
    @RestClient private val accountClient: AccountServiceRestClient,
    @RestClient private val transactionClient: TransactionServiceRestClient,
    @RestClient private val cardClient: CardServiceRestClient,
) : PortabilityAggregationPort {

    private val log = Logger.getLogger(PortabilityAggregationAdapter::class.java)

    override suspend fun fetchAccountsWithTransactions(partyId: UUID): List<PortabilityAccount> =
        accountClient.listByParty(partyId, ACCOUNT_PAGE_SIZE, null)
            .onFailure().recoverWithUni(recover(ACCOUNTS, partyId, null))
            .awaitSuspending()
            ?.data
            ?.mapNotNull { account ->
                val accountId = account.id ?: return@mapNotNull null
                PortabilityAccount(
                    accountId = accountId.toString(),
                    iban = account.accountNumber ?: "",
                    currency = account.currencyCode ?: "",
                    productCode = account.productId?.toString(),
                    status = account.status,
                    transactions = fetchTransactions(accountId),
                )
            }
            ?: emptyList()

    private suspend fun fetchTransactions(accountId: UUID): List<PortabilityTransaction> =
        transactionClient.listByAccount(accountId, TRANSACTION_PAGE_SIZE)
            .onFailure().recoverWithUni(recover(TRANSACTIONS, accountId, null))
            .awaitSuspending()
            ?.let { page -> page.items.ifEmpty { page.data } }
            ?.map { tx ->
                PortabilityTransaction(
                    transactionId = tx.transactionId ?: tx.id ?: "",
                    bookingDate = tx.bookingDate,
                    amount = tx.amount,
                    currency = tx.currency,
                    type = tx.type,
                    status = tx.status,
                    counterpartyName = tx.counterpartyName,
                    counterpartyIbanRedacted = redactIban(tx.targetIban ?: tx.sourceIban),
                    remittanceInfo = tx.remittanceInfo,
                    endToEndId = tx.endToEndId,
                )
            }
            ?: emptyList()

    override suspend fun fetchCards(partyId: UUID): List<PortabilityCard> = cardClient.listByParty(partyId)
        .onFailure().recoverWithUni(recover(CARDS, partyId, null))
        .awaitSuspending()
        // The Art. 20 payload keeps only contract-basis card metadata (ADR-0204). maskedPan
        // and limits are deliberately NOT mapped: a portability export must not become a PCI
        // data store, and a limit is risk-side data, not subject-provided.
        ?.mapNotNull { card ->
            val id = (card["id"] as? String) ?: return@mapNotNull null
            val expiry = card["expiryDate"] as? String // ISO LocalDate, e.g. 2028-03-15
            PortabilityCard(
                cardId = id,
                productCode = card["productCode"] as? String,
                status = card["status"] as? String,
                expiryMonth = expiry?.substring(MONTH_START, MONTH_END)?.toIntOrNull(),
                expiryYear = expiry?.substring(YEAR_START, YEAR_END)?.toIntOrNull(),
            )
        }
        ?: emptyList()

    /**
     * Splits a downstream failure into "refused" and "absent or unavailable" — the same shape
     * as [GdprAggregationAdapter.recover], so an authz refusal propagates as a real failure
     * while an outage degrades to the fallback (nil slice) with the throwable logged, never
     * swallowed.
     */
    private fun <T> recover(service: String, partyId: UUID, fallback: T?): (Throwable) -> Uni<T?> = { t ->
        val status = (t as? WebApplicationException)?.response?.status
        if (status == UNAUTHORIZED || status == FORBIDDEN) {
            log.errorf(
                "gdpr.portability.%s DENIED status=%d partyId=%s — export must not proceed",
                service,
                status,
                partyId,
            )
            Uni.createFrom().failure(GdprAggregationAuthException(service, status))
        } else {
            log.warnf(t, "gdpr.portability.%s degraded status=%s partyId=%s", service, status ?: "unreachable", partyId)
            Uni.createFrom().item { fallback }
        }
    }

    companion object {
        private const val UNAUTHORIZED = 401
        private const val FORBIDDEN = 403
        private const val ACCOUNTS = "account-service"
        private const val TRANSACTIONS = "transaction-service"
        private const val CARDS = "card-issuance-service"
        private const val ACCOUNT_PAGE_SIZE = 50
        private const val TRANSACTION_PAGE_SIZE = 200
        private const val YEAR_START = 0
        private const val YEAR_END = 4
        private const val MONTH_START = 5
        private const val MONTH_END = 7
    }
}
