// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.application.usecase

import com.openbank.account.application.port.out.AccountNumberReissue
import com.openbank.account.application.port.out.AccountNumberReissueRepository
import com.openbank.account.domain.event.AccountNumberReissuedEvent
import com.openbank.account.domain.model.CzechIbanValidity
import com.openbank.libs.domain.money.CurrencyCode
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.time.Clock
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/** What one [AccountNumberReissueService.reissueInvalid] pass did. */
data class ReissueReport(val scanned: Int, val reissued: Int, val skipped: Int, val failed: Int, val remaining: Int)

/**
 * Re-issues every account whose stored CZ IBAN fails the ČNB mod-11 check (V35).
 *
 * Idempotent by construction: it acts only on numbers that fail the check, and every number it writes
 * comes from [IbanGenerator], which is valid by construction — so a second pass finds nothing. That
 * makes "the job ran" and "the count is 0" the same claim, and [ReissueReport.remaining] (re-counted
 * after the pass, not inferred from it) is what the gauge publishes.
 */
@ApplicationScoped
class AccountNumberReissueService(
    private val repository: AccountNumberReissueRepository,
    private val generator: IbanGenerator,
    private val clock: Clock,
) {
    private val log = Logger.getLogger(AccountNumberReissueService::class.java)

    suspend fun reissueInvalid(): ReissueReport {
        val invalid = invalidAccounts()
        var reissued = 0
        var skipped = 0
        var failed = 0
        for (account in invalid) {
            try {
                val newNumber = freshNumber()
                val reissue = AccountNumberReissue(
                    accountId = account.accountId,
                    oldAccountNumber = account.accountNumber,
                    newAccountNumber = newNumber,
                    reason = REASON,
                    reissuedAt = clock.instant(),
                )
                val done = repository.reissue(reissue) { newVersion ->
                    AccountNumberReissuedEvent(
                        aggregateId = account.accountId,
                        version = newVersion,
                        previousAccountNumber = account.accountNumber,
                        accountNumber = newNumber,
                        reason = REASON,
                        occurredAt = reissue.reissuedAt,
                    )
                }
                if (done) {
                    reissued++
                    log.infof("account %s: account number re-issued (%s)", account.accountId, REASON)
                } else {
                    skipped++
                }
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                // One account's failure (a concurrent writer's version bump, a lost race on the UNIQUE)
                // must not stop the others; it stays invalid, so `remaining` reports it and the next
                // pass retries it.
                failed++
                log.warnf("account %s: account number re-issue failed: %s", account.accountId, e.toString())
            }
        }
        return ReissueReport(
            scanned = invalid.size,
            reissued = reissued,
            skipped = skipped,
            failed = failed,
            remaining = invalidAccounts().size,
        )
    }

    /** Accounts whose stored number fails the national check, read fresh from the store. */
    suspend fun invalidAccounts() = buildList {
        var after: UUID? = null
        while (true) {
            val page = repository.pageAccountNumbers(after, PAGE_SIZE)
            page.filterNotTo(this) { CzechIbanValidity.isNationallyValid(it.accountNumber) }
            if (page.size < PAGE_SIZE) break
            after = page.last().accountId
        }
    }

    private suspend fun freshNumber(): String {
        repeat(MAX_ATTEMPTS) {
            val candidate = generator.generate(CZK).value
            check(CzechIbanValidity.isNationallyValid(candidate)) { "IbanGenerator produced an invalid number" }
            if (!repository.accountNumberTaken(candidate)) return candidate
        }
        error("no free account number after $MAX_ATTEMPTS attempts")
    }

    companion object {
        const val REASON = "CZ_MOD11_INVALID"
        private const val PAGE_SIZE = 500
        private const val MAX_ATTEMPTS = 5
        private val CZK = CurrencyCode.of("CZK")
    }
}
