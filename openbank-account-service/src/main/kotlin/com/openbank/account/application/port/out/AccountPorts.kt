// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.application.port.out

import com.openbank.account.domain.model.Account
import com.openbank.account.domain.model.AccountAuthorization
import com.openbank.account.domain.model.CurrencyPocket
import com.openbank.libs.domain.account.Iban
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Outbound persistence port for the account aggregate (single IBAN, N currency pockets). */
interface AccountRepository {

    suspend fun findById(id: UUID): Account?

    suspend fun findByIban(iban: Iban): Account?

    suspend fun findByPartyId(partyId: UUID, limit: Int, afterId: UUID?): List<Account>

    /**
     * Fuzzy substring search over the account's IBAN. [normalizedFragment] must already be
     * normalized (spaces stripped, upper-cased) by the caller; it is matched as `%fragment%`
     * against the trigram-indexed `account_number` column. Keyset-paginated by id for a stable
     * cursor, identically to [findByPartyId].
     */
    suspend fun searchByIban(normalizedFragment: String, limit: Int, afterId: UUID?): List<Account>

    suspend fun save(account: Account): Account

    suspend fun update(account: Account): Account

    suspend fun existsByIban(iban: Iban): Boolean
}

/** Outbound persistence port for the per-account currency pockets. */
interface CurrencyPocketRepository {

    suspend fun findByAccountId(accountId: UUID): List<CurrencyPocket>

    suspend fun findByAccountIdAndCurrency(accountId: UUID, currency: String): CurrencyPocket?

    suspend fun save(pocket: CurrencyPocket): CurrencyPocket

    suspend fun update(pocket: CurrencyPocket): CurrencyPocket
}

/** Outbound persistence port for account authorizations (signatories / mandates). */
interface AccountAuthorizationRepository {

    suspend fun save(auth: AccountAuthorization): AccountAuthorization

    suspend fun findById(id: UUID): AccountAuthorization?

    suspend fun findByAccountId(accountId: UUID): List<AccountAuthorization>

    suspend fun findActiveByAccountAndParty(accountId: UUID, partyId: UUID): List<AccountAuthorization>
}

/** A read-model projection of a pocket balance as served by balance-service. */
data class BalanceView(
    val accountId: UUID,
    val currency: String,
    val booked: BigDecimal,
    val available: BigDecimal,
    val reserved: BigDecimal,
    val pending: BigDecimal,
    val arrangedOverdraftLimit: BigDecimal,
    val updatedAt: Instant,
)

/** Outbound query port over balance-service for reading pocket balances and seeding them. */
interface BalanceQueryPort {

    suspend fun initialize(accountId: UUID, currency: String, arrangedOverdraftLimit: BigDecimal)

    suspend fun getByAccount(accountId: UUID): List<BalanceView>

    suspend fun getByAccountAndCurrency(accountId: UUID, currency: String): BalanceView?
}

/** Outbound port for publishing account domain events to the broker. */
interface AccountEventPublisher {

    suspend fun publish(topic: String, key: String, event: Any)
}
