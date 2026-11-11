// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.infrastructure.returns.gsft

import com.openbank.tax.application.port.out.CeuRegisterRepository
import com.openbank.tax.application.port.out.GsftDataGap
import com.openbank.tax.application.port.out.GsftSnapshot
import com.openbank.tax.application.port.out.GsftSnapshotPort
import com.openbank.tax.application.port.out.ReturnDataUnavailableException
import com.openbank.tax.domain.ceu.RegisterFact
import com.openbank.tax.domain.gsft.DepositAccount
import com.openbank.tax.domain.gsft.Depositor
import com.openbank.tax.domain.gsft.Holding
import com.openbank.tax.infrastructure.returns.crsfatca.AccountSummaryDto
import com.openbank.tax.infrastructure.returns.crsfatca.CrsFatcaReportingSources
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate

/**
 * Sources the GSFT snapshot from the read models this service is ADMITTED to (issue #15356):
 *
 *  - the deposit roster from account-service's `GET /api/v1/accounts/active` (cursor-walked to the
 *    end, the same client and rego grant as CRS/FATCA), keeping only deposit account types;
 *  - principal from balance-service's `GET /api/v1/balances/{accountId}/{currency}?asOf=D`;
 *  - the ADR-0252 synthetic taint from this service's own CEÚ register, as known now: a SYNTHETIC
 *    holder party OR a tainted account opening (a taint is never downgraded, so the latest
 *    knowledge is the safe one).
 *
 * Everything else has no verified source for this caller and is reported in
 * [GsftSnapshot.gaps] instead of being filled in: no identity is fabricated, accrued interest is
 * zero AND flagged, every account is single-holder AND flagged, no exclusion is assumed absent
 * without the flag, and no FX rate is supplied. See `docs/gsft-depositor-data.md`, "Data sourcing".
 */
@ApplicationScoped
class GsftSnapshotAdapter(
    private val sources: CrsFatcaReportingSources,
    private val register: CeuRegisterRepository,
    private val clock: Clock,
) : GsftSnapshotPort {

    override suspend fun snapshot(relevantDay: LocalDate): GsftSnapshot {
        val roster = walkRoster().filter { isDeposit(it) }
        if (roster.map { it.id }.toSet().size != roster.size) {
            throw ReturnDataUnavailableException("account-service returned a duplicate account in the deposit roster")
        }
        val knownAt = clock.instant()
        val synthetic = register.syntheticParties(knownAt)
        // CEÚ's account-level taint: an opening event that was itself tainted (CeuRegister.fold). Read up
        // to TODAY, not D: the roster is the current one, so an account opened after D is in it too.
        val syntheticAccounts = register.factsUpTo(LocalDate.ofInstant(knownAt, clock.zone), knownAt)
            .filter { it is RegisterFact.AccountOpened && it.synthetic }
            .map { it.accountId }
            .toSet()
        val (dropped, retained) = roster.partition { it.partyId in synthetic || it.id in syntheticAccounts }
        val accounts = retained.map { account ->
            DepositAccount(
                accountId = account.id,
                iban = account.accountNumber,
                currency = account.currencyCode,
                // A 404 FAILS here (unlike CRS/FATCA's zero); asOf may be the current balance
                // (GsftDataGap.BALANCE_AS_OF_UNVERIFIED).
                principal = sources.bookedBalanceStrict(account.id, account.currencyCode, relevantDay),
                // UNSOURCED (GsftDataGap.ACCRUED_INTEREST): understates every claim by the interest.
                accruedInterest = BigDecimal.ZERO,
                // UNSOURCED (GsftDataGap.JOINT_HOLDERS): account-service knows one party per account.
                holders = listOf(Holding(account.partyId, null)),
                synthetic = false,
            )
        }
        val depositors = roster.map { it.partyId }.distinct().map { partyId ->
            // UNSOURCED (GsftDataGap.EXCLUSION_CATEGORY): null here is "not known", flagged below.
            Depositor(partyId = partyId, exclusion = null, synthetic = partyId in synthetic)
        }
        return GsftSnapshot(
            relevantDay = relevantDay,
            depositors = depositors,
            accounts = accounts,
            identities = emptyList(),
            rates = null,
            gaps = UNRESOLVED_GAPS,
            // UNSOURCED: no owning service models special-regime beneficiaries yet.
            specialShares = emptyList(),
            // Claim-based (§ 41e odst. 3): nothing is claimed before a payout starts.
            qualifyingCredits = emptyList(),
            syntheticAccountsDroppedAtSource = dropped.size,
        )
    }

    /** Deposit types are insured; GL/nostro rows are the bank's own. An unknown type fails, never guessed. */
    private fun isDeposit(account: AccountSummaryDto): Boolean = when (account.accountType) {
        in DEPOSIT_TYPES -> true
        in NON_DEPOSIT_TYPES -> false
        else -> throw ReturnDataUnavailableException(
            "account ${account.id} has account type '${account.accountType}' — cannot decide whether it is a deposit",
        )
    }

    private suspend fun walkRoster(): List<AccountSummaryDto> = buildList {
        var cursor: String? = null
        do {
            val page = sources.activeAccounts(PAGE_SIZE, cursor)
            addAll(page.accounts)
            cursor = page.nextCursor
        } while (cursor != null)
    }

    companion object {
        private const val PAGE_SIZE = 200
        val DEPOSIT_TYPES = setOf("CURRENT", "SAVINGS", "TERM_DEPOSIT")
        val NON_DEPOSIT_TYPES = setOf("NOSTRO", "GL_ASSET", "GL_LIABILITY", "GL_INCOME", "GL_EXPENSE")

        /** Every gap still open; shrink it only together with the source that closes it. */
        val UNRESOLVED_GAPS: Set<GsftDataGap> = GsftDataGap.entries.toSet()
    }
}
