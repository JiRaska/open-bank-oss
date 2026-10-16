// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.infrastructure.returns.crsfatca

import com.openbank.tax.application.port.out.ReturnAssemblyData
import com.openbank.tax.application.port.out.ReturnDataUnavailableException
import com.openbank.tax.application.usecase.IncomeCreditService
import com.openbank.tax.domain.returns.ReportingPeriod
import com.openbank.tax.domain.returns.ReturnCatalogue
import com.openbank.tax.domain.returns.ReturnDefinition
import com.openbank.tax.domain.returns.ReturnLine
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.ProcessingException
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** Reads the three sources; split from the mapping so the mapping is testable without HTTP. */
interface CrsFatcaReportingSources {
    /** Page of ACTIVE accounts; [cursor] is the opaque value the previous page answered. */
    suspend fun activeAccounts(limit: Int, cursor: String?): AccountPage

    /** Tax facts for one party. Null when the party never declared a profile (not reportable). */
    suspend fun partyTaxFacts(partyId: UUID): PartyTaxFactsDto?

    /** Booked balance of one account currency at the period end. */
    suspend fun bookedBalance(accountId: UUID, currency: String, asOf: LocalDate): BigDecimal
}

data class AccountPage(val accounts: List<AccountSummaryDto>, val nextCursor: String?)

@ApplicationScoped
class RestCrsFatcaReportingSources(
    @RestClient private val parties: PartyTaxReportingClient,
    @RestClient private val accounts: AccountReportingClient,
    @RestClient private val balances: BalanceReportingClient,
) : CrsFatcaReportingSources {
    override suspend fun activeAccounts(limit: Int, cursor: String?): AccountPage = sourced("account-service") {
        val page = accounts.active(limit, cursor)
        AccountPage(page.data, page.pagination.nextCursor.takeIf { page.pagination.hasNextPage })
    }

    override suspend fun partyTaxFacts(partyId: UUID): PartyTaxFactsDto? = try {
        parties.taxFacts(partyId)
    } catch (e: WebApplicationException) {
        // A party that never self-certified has no profile: the route answers 404 and the party is
        // simply not reportable. Anything else (401/403/5xx) fails the assembly — a guard refusal
        // or a broken provider must never be silently read as "no residencies".
        if (e.response?.status == Response.Status.NOT_FOUND.statusCode) {
            null
        } else {
            throw ReturnDataUnavailableException("party-service answered ${e.response.status}: ${e.message}", e)
        }
    } catch (e: ProcessingException) {
        throw ReturnDataUnavailableException("party-service unreachable: ${e.message}", e)
    }

    override suspend fun bookedBalance(accountId: UUID, currency: String, asOf: LocalDate): BigDecimal = try {
        balances.bookedAsOf(accountId, currency, asOf).bookedAmount
    } catch (e: WebApplicationException) {
        if (e.response?.status == Response.Status.NOT_FOUND.statusCode) {
            // No balance row for an ACTIVE account means no booked movements — genuinely zero,
            // not missing data (the ledger maintains the row on first movement).
            BigDecimal.ZERO
        } else {
            throw ReturnDataUnavailableException("balance-service answered ${e.response.status}: ${e.message}", e)
        }
    } catch (e: ProcessingException) {
        throw ReturnDataUnavailableException("balance-service unreachable: ${e.message}", e)
    }

    private suspend fun <T> sourced(source: String, call: suspend () -> T): T = try {
        call()
    } catch (e: WebApplicationException) {
        throw ReturnDataUnavailableException("$source answered ${e.response.status}: ${e.message}", e)
    } catch (e: ProcessingException) {
        throw ReturnDataUnavailableException("$source unreachable: ${e.message}", e)
    }
}

/**
 * The `cz-tax-information-exchange` catalogue (`tax-information-exchange.v1.json`) mapped onto the
 * fleet's read models (ADR-0340 B):
 *
 *  - the account roster from account-service's fleet-wide ACTIVE list (cursor-paginated, walked to
 *    the end — a partial roster would under-report both the populations and the totals);
 *  - reportable-person status and TINs from party-service's tax-facts boundary, fetched once per
 *    party and cached for the assembly;
 *  - the period-end booked balance from balance-service's statement `asOf` read;
 *  - the gross interest credited from the observed `interest.withholding.recorded.v1` events
 *    (ADR-0033 §F), never recomputed.
 *
 * A line is emitted per account per reportable residency country: CRS for every non-CZ residency,
 * FATCA for the US residency. A party whose profile is missing was never self-certified and is not
 * reportable — the return says so by omitting them, matching the profile-derived definition of
 * REPORTABLE. Mixed-currency totals are summed as bare numbers (platform datapoints, not wire
 * figures); the per-line currency keeps the breakdown honest.
 *
 * Everything is fetched from ONE snapshot pass; any source failure refuses the whole return
 * ([ReturnDataUnavailableException] → 503), never a partial or zero-filled one.
 */
@ApplicationScoped
class CrsFatcaReturnDataAdapter(
    private val sources: CrsFatcaReportingSources,
    private val incomeCredits: IncomeCreditService,
) {
    val available: Boolean = true

    suspend fun fetch(
        catalogue: ReturnCatalogue,
        definition: ReturnDefinition,
        @Suppress("unused") entityId: String, // single-company scope (ADR-0340); the roster IS the entity
        period: ReportingPeriod,
    ): ReturnAssemblyData {
        if (catalogue.id != CATALOGUE_ID) {
            throw ReturnDataUnavailableException(
                "catalogue ${catalogue.id} is not bound to the CRS/FATCA adapter",
            )
        }
        val windowFrom = period.endDate.withDayOfYear(1)
        val accounts = walkAccounts()
        val factsByParty = hashMapOf<UUID, PartyTaxFactsDto?>()
        val incomeByAccount = incomeCredits.totalsByAccount(windowFrom, period.endDate)
            .groupBy({ it.accountId to it.currency }, { it.gross })
            .mapValues { (_, parts) -> parts.fold(BigDecimal.ZERO, BigDecimal::add) }

        val lines = mutableListOf<ReturnLine>()
        // Totals are summed per DISTINCT ACCOUNT, never per line: one account with two residency
        // countries emits two CRS lines but its balance and income must count once.
        val accountFigures = linkedMapOf<String, Pair<BigDecimal, BigDecimal>>()
        val accountParties = linkedMapOf<String, String>()
        for (account in accounts) {
            val facts = factsByParty.getOrPut(account.partyId) { sources.partyTaxFacts(account.partyId) }
            val residencies = reportableResidencies(definition.code, facts)
            if (facts != null && residencies.isNotEmpty()) {
                val balance = sources.bookedBalance(account.id, account.currencyCode, period.endDate)
                val interest = incomeByAccount[account.id to account.currencyCode] ?: BigDecimal.ZERO
                accountFigures[account.accountNumber] = balance to interest
                accountParties[account.accountNumber] = account.partyId.toString()
                lines += residencies.map { lineFor(account, facts, it, balance, interest) }
            }
        }

        return ReturnAssemblyData(
            values = linkedMapOf(
                DATAPOINT_PERSONS to accountParties.values.toSet().size.toBigDecimal(),
                DATAPOINT_ACCOUNTS to accountFigures.keys.size.toBigDecimal(),
                DATAPOINT_INTEREST to accountFigures.values.fold(BigDecimal.ZERO) { sum, (_, interest) ->
                    sum + interest
                },
                DATAPOINT_BALANCE to accountFigures.values.fold(BigDecimal.ZERO) { sum, (balance, _) ->
                    sum + balance
                },
            ),
            lines = lines,
        )
    }

    /**
     * Which residency countries make [facts] reportable under [returnCode]. A party whose profile
     * is missing was never self-certified and is not reportable — the return says so by omitting
     * them, matching the profile-derived definition of REPORTABLE.
     */
    private fun reportableResidencies(returnCode: String, facts: PartyTaxFactsDto?): List<TaxResidencyDto> {
        if (facts == null) return emptyList()
        return when (returnCode) {
            RETURN_CRS -> facts.taxResidencies.filter { it.country != COUNTRY_DOMESTIC }
            RETURN_FATCA ->
                if (facts.fatcaStatus == STATUS_US_PERSON) {
                    facts.taxResidencies.filter { it.country == COUNTRY_US }
                } else {
                    emptyList()
                }
            else -> throw ReturnDataUnavailableException(
                "return $returnCode is not defined by the CRS/FATCA catalogue",
            )
        }
    }

    private fun lineFor(
        account: AccountSummaryDto,
        facts: PartyTaxFactsDto,
        residency: TaxResidencyDto,
        balance: BigDecimal,
        interest: BigDecimal,
    ) = ReturnLine(
        linkedMapOf(
            FIELD_HOLDER_NAME to facts.holderName,
            FIELD_PARTY_REF to account.partyId.toString(),
            FIELD_ACCOUNT_REF to account.accountNumber,
            FIELD_RESIDENCY to residency.country,
            FIELD_TIN to residency.tin,
            FIELD_CURRENCY to account.currencyCode,
            FIELD_BALANCE to balance.toPlainString(),
            FIELD_INTEREST to interest.toPlainString(),
        ),
    )

    /** Walks the whole ACTIVE roster; a truncated roster would under-report every datapoint. */
    private suspend fun walkAccounts(): List<AccountSummaryDto> = buildList {
        var cursor: String? = null
        do {
            val page = sources.activeAccounts(PAGE_SIZE, cursor)
            addAll(page.accounts)
            cursor = page.nextCursor
        } while (cursor != null)
    }

    companion object {
        const val CATALOGUE_ID = "cz-tax-information-exchange"
        const val RETURN_CRS = "CRS"
        const val RETURN_FATCA = "FATCA"
        const val STATUS_US_PERSON = "US_PERSON"
        const val COUNTRY_DOMESTIC = "CZ"
        const val COUNTRY_US = "US"
        private const val PAGE_SIZE = 200

        const val FIELD_HOLDER_NAME = "holder_name"
        const val FIELD_PARTY_REF = "party_ref"
        const val FIELD_ACCOUNT_REF = "account_ref"
        const val FIELD_RESIDENCY = "residency_country"
        const val FIELD_TIN = "tin"
        const val FIELD_CURRENCY = "currency"
        const val FIELD_BALANCE = "period_end_balance"
        const val FIELD_INTEREST = "interest_credited"

        const val DATAPOINT_PERSONS = "reportable_persons"
        const val DATAPOINT_ACCOUNTS = "reportable_accounts"
        const val DATAPOINT_INTEREST = "total_interest_credited"
        const val DATAPOINT_BALANCE = "total_period_end_balance"
    }
}
