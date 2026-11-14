// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.application.usecase

import com.openbank.account.application.port.out.AccountEventPublisher
import com.openbank.account.application.port.out.AccountRepository
import com.openbank.account.domain.event.AccountStatusChangedEvent
import com.openbank.account.domain.model.Account
import com.openbank.account.domain.model.AccountStatus
import com.openbank.account.domain.model.FreezeOrigin
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.time.Clock
import java.util.UUID

/**
 * Carries a party's standing onto its accounts (ADR-0374 D4).
 *
 * A party that is suspended or closed may not move money, so every ACTIVE account it owns is frozen
 * with [FreezeOrigin.PARTY_STANDING]. When the party is ACTIVE again exactly those freezes are
 * lifted; a freeze placed by an operator or a control stays, whatever the party's status.
 *
 * Idempotent in both directions: an account already in the target state is skipped, so a
 * redelivered party event changes nothing and publishes nothing.
 *
 * The customer is deliberately not notified from here. The event says only that the party lost its
 * standing, not why, and one of the reasons is an AML block: an automatic "your account was frozen"
 * message on that path is tipping-off. Whoever suspended the party owns the communication.
 */
@ApplicationScoped
class PartyStandingService(
    private val accountRepository: AccountRepository,
    private val eventPublisher: AccountEventPublisher,
    private val clock: Clock,
) {
    private val log = Logger.getLogger(PartyStandingService::class.java)

    /** Freeze every ACTIVE account of [partyId]; [partyStatus] is recorded as the reason. Returns the count. */
    suspend fun freezeAccountsOf(partyId: UUID, partyStatus: String): Int =
        transition(partyId, "PARTY_$partyStatus", { it.status == AccountStatus.ACTIVE }) {
            it.freeze(FreezeOrigin.PARTY_STANDING)
        }

    /** Lift the freezes [freezeAccountsOf] placed on the accounts of [partyId]. Returns the count. */
    suspend fun restoreAccountsOf(partyId: UUID): Int =
        transition(partyId, "PARTY_ACTIVE", Account::isFrozenByPartyStanding, Account::unfreeze)

    private suspend fun transition(
        partyId: UUID,
        reason: String,
        applies: (Account) -> Boolean,
        change: (Account) -> Account,
    ): Int {
        var changed = 0
        var cursor: UUID? = null
        do {
            val page = accountRepository.findByPartyId(partyId, PAGE_SIZE, cursor)
            for (account in page.filter(applies)) {
                val updated = accountRepository.update(change(account))
                eventPublisher.publish(
                    topic = STATUS_CHANGED_TOPIC,
                    key = updated.id.toString(),
                    event = AccountStatusChangedEvent(
                        aggregateId = updated.id,
                        version = updated.version,
                        previousStatus = account.status,
                        newStatus = updated.status,
                        reason = reason,
                        occurredAt = clock.instant(),
                        sourceService = "account-service",
                    ),
                )
                log.infof("Account %s %s -> %s (%s)", updated.id, account.status, updated.status, reason)
                changed++
            }
            cursor = page.lastOrNull()?.id
        } while (page.size == PAGE_SIZE)
        return changed
    }

    private companion object {
        const val PAGE_SIZE = 50
        const val STATUS_CHANGED_TOPIC = "openbank.accounts.account.status-changed"
    }
}
