// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.application.port.`in`

import com.openbank.account.application.port.out.BalanceView
import com.openbank.account.domain.model.Account
import com.openbank.account.domain.model.AccountStatus
import com.openbank.account.domain.model.AccountType
import com.openbank.account.domain.model.CurrencyPocket
import com.openbank.account.domain.model.MissingPocketPolicy
import com.openbank.account.domain.model.PocketResolution
import com.openbank.libs.api.pagination.CursorPage
import com.openbank.libs.domain.money.CurrencyCode
import java.time.LocalDate
import java.util.UUID

data class OpenAccountCommand(
    val idempotencyKey: String,
    val partyId: UUID,
    val productId: UUID,
    val accountType: AccountType,
    val currency: CurrencyCode,
    val requestedBy: UUID,
    /** Legal name for sanctions screening (ADR-0032 §C). Non-null enforced at compile time. */
    val legalName: String,
    /**
     * Initial lifecycle status. Defaults to ACTIVE for operator-opened accounts.
     * The onboarding path opens PENDING_ACTIVATION accounts that activate only once
     * the party clears KYC + AML (ADR-0267); a PENDING_ACTIVATION account is inert
     * (canDebit/canCredit both require ACTIVE).
     */
    val initialStatus: AccountStatus = AccountStatus.ACTIVE,
)

data class CloseAccountCommand(val accountId: UUID, val reason: String?, val requestedBy: UUID)

data class FreezeAccountCommand(val accountId: UUID, val reason: String, val requestedBy: UUID)

data class UnfreezeAccountCommand(val accountId: UUID, val reason: String, val requestedBy: UUID)

data class GetAccountQuery(val accountId: UUID)
data class GetAccountByIbanQuery(val iban: String)
data class ListAccountsQuery(val partyId: UUID, val limit: Int = 20, val afterCursor: String? = null)
data class SearchAccountsQuery(val query: String, val limit: Int = 20, val afterCursor: String? = null)

/**
 * Fleet-wide sweep over ACTIVE accounts, cursor-paginated (ADR-0143: the "list every billable
 * account" read port billing-service's cycle scheduler discovers its batch from).
 */
data class ListActiveAccountsQuery(val limit: Int = 100, val afterCursor: String? = null)

data class AddPocketCommand(val accountId: UUID, val currency: CurrencyCode, val requestedBy: UUID)
data class ClosePocketCommand(val accountId: UUID, val currency: CurrencyCode, val requestedBy: UUID)
data class ListPocketsQuery(val accountId: UUID)
data class ResolvePocketQuery(
    val accountId: UUID,
    val paymentCurrency: CurrencyCode,
    val policy: MissingPocketPolicy = MissingPocketPolicy.CONVERT_TO_PRIMARY,
)

/** Set or replace the account's savings goal (ADR-0153). [targetMinorUnits] must be positive. */
data class UpdateSavingsGoalCommand(
    val accountId: UUID,
    val name: String,
    val targetMinorUnits: Long,
    val targetDate: LocalDate?,
    val requestedBy: UUID,
)
data class ClearSavingsGoalCommand(val accountId: UUID, val requestedBy: UUID)

/** Set or clear the customer-chosen display label. Blank/null clears it. */
data class RenameAccountCommand(val accountId: UUID, val nickname: String?, val requestedBy: UUID)

interface AccountUseCase {
    suspend fun openAccount(command: OpenAccountCommand): Account

    /** Transition a PENDING_ACTIVATION account to ACTIVE (KYC + AML cleared, ADR-0267). */
    suspend fun activateAccount(accountId: UUID): Account
    suspend fun closeAccount(command: CloseAccountCommand): Account
    suspend fun freezeAccount(command: FreezeAccountCommand): Account
    suspend fun unfreezeAccount(command: UnfreezeAccountCommand): Account
    suspend fun getAccount(query: GetAccountQuery): Account
    suspend fun getAccountByIban(query: GetAccountByIbanQuery): Account
    suspend fun listAccounts(query: ListAccountsQuery): CursorPage<Account>
    suspend fun searchAccounts(query: SearchAccountsQuery): CursorPage<Account>

    /** All ACTIVE accounts, fleet-wide, keyset-paginated by id (ADR-0143 billing discovery). */
    suspend fun listActiveAccounts(query: ListActiveAccountsQuery): CursorPage<Account>
    suspend fun getBalance(accountId: UUID): BalanceView
    suspend fun addPocket(command: AddPocketCommand): CurrencyPocket
    suspend fun closePocket(command: ClosePocketCommand): CurrencyPocket
    suspend fun listPockets(query: ListPocketsQuery): List<CurrencyPocket>
    suspend fun resolvePocket(query: ResolvePocketQuery): PocketResolution
    suspend fun updateSavingsGoal(command: UpdateSavingsGoalCommand): Account
    suspend fun clearSavingsGoal(command: ClearSavingsGoalCommand): Account
    suspend fun renameAccount(command: RenameAccountCommand): Account
}

data class GrantAuthorizationCommand(
    val accountId: UUID,
    val partyId: UUID,
    val role: com.openbank.account.domain.model.AuthorizationRole,
    val dailyLimit: com.openbank.libs.domain.money.Money?,
    val transactionLimit: com.openbank.libs.domain.money.Money?,
    val validFrom: java.time.LocalDate,
    val validTo: java.time.LocalDate?,
    val grantedBy: UUID,
)

data class RevokeAuthorizationCommand(
    val accountId: UUID,
    val authorizationId: UUID,
    val revokedBy: UUID,
    val reason: String,
)

data class ListAuthorizationsQuery(val accountId: UUID)

interface AuthorizationUseCase {
    suspend fun grantAuthorization(
        command: GrantAuthorizationCommand,
    ): com.openbank.account.domain.model.AccountAuthorization
    suspend fun revokeAuthorization(
        command: RevokeAuthorizationCommand,
    ): com.openbank.account.domain.model.AccountAuthorization
    suspend fun listAuthorizations(
        query: ListAuthorizationsQuery,
    ): List<com.openbank.account.domain.model.AccountAuthorization>
    suspend fun isAuthorized(
        accountId: UUID,
        partyId: UUID,
        role: com.openbank.account.domain.model.AuthorizationRole,
    ): Boolean

    /**
     * Amount-aware variant for the payment path (ADR-0232 D3 / AC6): a delegated
     * PAYMENT_ONLY answer also checks the grant's per-transaction ceiling.
     */
    suspend fun isAuthorizedForAmount(
        accountId: UUID,
        partyId: UUID,
        role: com.openbank.account.domain.model.AuthorizationRole,
        amount: com.openbank.libs.domain.money.Money?,
    ): Boolean

    /**
     * The payment path's question, answered with the EVIDENCE and not just a boolean
     * (ADR-0232 D3/D5, #2990 AC9/AC10).
     *
     * [isAuthorizedForAmount] returns Boolean, which is enough to permit a debit and NOT
     * enough to record one: a delegated payment has to be auditable *as delegated*, which
     * needs the grant that permitted it and the grantor it was taken on behalf of. Both are
     * known at the moment of the decision and unrecoverable afterwards — the grant can be
     * revoked a second later and the projection row closed, at which point no later query
     * can reconstruct which grant was live when the money moved.
     *
     * Refusals are classified rather than collapsed to `false` so the audit trail can tell
     * "this party has no grant" from "this party has a grant and blew the ceiling" — the
     * second is a limit event a grantor should see, the first is closer to an access probe.
     */
    suspend fun authorizeDelegatedPayment(
        accountId: UUID,
        partyId: UUID,
        amount: com.openbank.libs.domain.money.Money?,
    ): DelegatedPaymentDecision
}

/**
 * Why the payment path was (or was not) allowed to debit the account (ADR-0232 D3/D5).
 *
 * OWNER and DELEGATED both authorise; they are distinct because only the second one has to
 * be written into the audit chain as an on-behalf-of action.
 */
enum class DelegatedPaymentOutcome {
    /** The initiating party owns the account — not a delegated action, nothing to record. */
    OWNER,

    /** A legacy `account_authorizations` row authorises the party. Amount limits on that table are
     *  NOT evaluated here (see [DelegatedPaymentDecision]); it is not a delegation grant. */
    LEGACY_AUTHORIZATION,

    /** An ACTIVE, in-window, owner-issued delegation grant authorises the party for this amount. */
    DELEGATED,

    /** No grant and no authorization row names this party on this account. */
    NO_GRANT,

    /** A grant names the party, but every candidate grant refuses this amount/currency. */
    LIMIT_EXCEEDED,

    /** The account does not exist. Reported separately so the caller does not turn a typo into a
     *  permission story; callers MUST still collapse it to the same opaque refusal on the wire. */
    ACCOUNT_NOT_FOUND,
}

/**
 * The decision plus the evidence needed to audit it (ADR-0232 D5).
 *
 * [delegationId] and [grantorPartyId] are populated ONLY for [DelegatedPaymentOutcome.DELEGATED];
 * for an owner they are meaningless and for a refusal they would leak the existence of a grant.
 */
data class DelegatedPaymentDecision(
    val outcome: DelegatedPaymentOutcome,
    /** The grant that permitted the debit; null unless [outcome] is DELEGATED. */
    val delegationId: UUID? = null,
    /** The account owner the debit is taken on behalf of; null unless [outcome] is DELEGATED. */
    val grantorPartyId: UUID? = null,
) {
    val authorized: Boolean
        get() = outcome == DelegatedPaymentOutcome.OWNER ||
            outcome == DelegatedPaymentOutcome.LEGACY_AUTHORIZATION ||
            outcome == DelegatedPaymentOutcome.DELEGATED
}
