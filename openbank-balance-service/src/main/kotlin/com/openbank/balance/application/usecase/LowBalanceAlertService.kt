// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.balance.application.port.out.BalanceOutboxRepository
import com.openbank.balance.domain.model.LowBalanceAlertRule
import com.openbank.balance.domain.model.LowBalanceAlertState
import com.openbank.balance.domain.model.evaluate
import com.openbank.balance.infrastructure.client.AccountServiceClient
import com.openbank.balance.infrastructure.persistence.entity.LowBalanceAlertPreferenceEntity
import com.openbank.balance.infrastructure.persistence.repository.BalancePanacheRepo
import com.openbank.balance.infrastructure.persistence.repository.LowBalanceAlertRepository
import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.quarkus.hibernate.reactive.panache.Panache
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class LowBalanceAlertView(
    val accountId: UUID,
    val currency: String,
    val enabled: Boolean,
    val threshold: BigDecimal,
    val rearmMargin: BigDecimal,
)

class LowBalanceAccountNotOwnedException : RuntimeException("account is unavailable")

/** Owns customer alert settings and serialised decisions over the current pocket. */
@ApplicationScoped
class LowBalanceAlertService(
    private val preferences: LowBalanceAlertRepository,
    private val balances: BalancePanacheRepo,
    private val accounts: AccountServiceClient,
    private val outbox: BalanceOutboxRepository,
    private val mapper: ObjectMapper,
    @ConfigProperty(name = "openbank.balance.low-alerts-enabled", defaultValue = "false")
    private val enabled: Boolean,
) {
    private val clock = Clock.systemUTC()
    private val accountingClock = AccountingClock.bank(clock)

    suspend fun get(accountId: UUID, currency: String, partyId: UUID): LowBalanceAlertView? {
        requireOwner(accountId, partyId)
        return Panache.withSession {
            preferences.find("accountId = ?1 and currency = ?2", accountId, currency).firstResult()
        }.awaitSuspending()?.takeIf { it.partyId == partyId }?.toView()
    }

    suspend fun configure(
        accountId: UUID,
        currency: String,
        partyId: UUID,
        threshold: BigDecimal,
        rearmMargin: BigDecimal,
        active: Boolean,
    ): LowBalanceAlertView {
        requireOwner(accountId, partyId)
        val rule = LowBalanceAlertRule(threshold, rearmMargin, COOLDOWN)
        return Panache.withTransaction {
            balances.find("accountId = ?1 and currency = ?2", accountId, currency)
                .withLock(LockModeType.PESSIMISTIC_READ).firstResult().flatMap { pocket ->
                    if (pocket == null) {
                        return@flatMap Uni.createFrom().failure(
                            BalanceNotFoundException("Balance not found for account=$accountId currency=$currency"),
                        )
                    }
                    futureCredit(accountId, currency).flatMap { credit ->
                        val current = pocket.availableAmount - credit
                        preferences.find("accountId = ?1 and currency = ?2", accountId, currency)
                            .withLock(LockModeType.PESSIMISTIC_WRITE).firstResult().flatMap { existing ->
                                val row = existing ?: LowBalanceAlertPreferenceEntity().also {
                                    it.accountId = accountId
                                    it.currency = currency
                                }
                                if (existing != null && existing.partyId != partyId) row.lastAlertAt = null
                                row.partyId = partyId
                                row.threshold = threshold
                                row.rearmMargin = rearmMargin
                                row.enabled = active
                                row.armed = active && LowBalanceAlertState.initial(current, rule).armed
                                row.updatedAt = Instant.now(clock)
                                if (existing == null) {
                                    preferences.persist(row).map { row.toView() }
                                } else {
                                    Uni.createFrom().item(row.toView())
                                }
                            }
                    }
                }
        }.awaitSuspending()
    }

    /** Balance events only wake this check; their old amount is never trusted. */
    suspend fun evaluate(accountId: UUID, currency: String) {
        if (!enabled) return
        val subscribed = Panache.withSession {
            preferences.find("accountId = ?1 and currency = ?2 and enabled = true", accountId, currency).firstResult()
        }.awaitSuspending() ?: return
        val owner = accounts.getPartyId(accountId) ?: return
        if (subscribed.partyId != owner) return
        Panache.withTransaction {
            balances.find("accountId = ?1 and currency = ?2", accountId, currency)
                .withLock(LockModeType.PESSIMISTIC_READ).firstResult().flatMap { pocket ->
                    if (pocket == null) return@flatMap Uni.createFrom().voidItem()
                    futureCredit(accountId, currency).flatMap { credit ->
                        preferences.find("accountId = ?1 and currency = ?2", accountId, currency)
                            .withLock(LockModeType.PESSIMISTIC_WRITE).firstResult().flatMap { row ->
                                if (row == null || !row.enabled || row.partyId != owner) {
                                    return@flatMap Uni.createFrom().voidItem()
                                }
                                val state = LowBalanceAlertState(row.armed, row.generation, row.lastAlertAt)
                                val rule = LowBalanceAlertRule(row.threshold, row.rearmMargin, COOLDOWN)
                                val decision = state.evaluate(pocket.availableAmount - credit, rule, Instant.now(clock))
                                row.armed = decision.state.armed
                                row.generation = decision.state.generation
                                row.lastAlertAt = decision.state.lastAlertAt
                                row.updatedAt = Instant.now(clock)
                                if (decision.emit) {
                                    outbox.persistInTransaction(alertMessage(row, Instant.now(clock)))
                                } else {
                                    Uni.createFrom().voidItem()
                                }
                            }
                    }
                }
        }.awaitSuspending()
    }

    private fun futureCredit(accountId: UUID, currency: String): Uni<BigDecimal> =
        Panache.getSession().flatMap { session ->
            session.createQuery(
                "select coalesce(sum(e.delta), 0) from LedgerProjectionEventEntity e " +
                    "where e.accountId = :account and e.currency = :currency and e.entryDate > :today and e.delta > 0",
                BigDecimal::class.java,
            ).setParameter("account", accountId)
                .setParameter("currency", currency)
                .setParameter("today", accountingClock.today())
                .singleResult
        }

    private fun alertMessage(row: LowBalanceAlertPreferenceEntity, now: Instant): OutboxMessage {
        val intentId = UUID.nameUUIDFromBytes(
            "low-balance:${row.accountId}:${row.currency}:${row.generation}"
                .toByteArray(StandardCharsets.UTF_8),
        )
        val request = mapOf(
            "partyId" to row.partyId,
            "channel" to "INBOX",
            "template" to "LOW_BALANCE_ALERT",
            "recipient" to row.partyId.toString(),
            "variables" to emptyMap<String, String>(),
            "correlationId" to intentId,
            "deduplicationKey" to intentId,
        )
        return OutboxMessage(
            eventId = intentId,
            aggregateId = row.accountId,
            eventType = LOW_BALANCE_REQUEST,
            payload = mapper.writeValueAsString(request),
            createdAt = now,
        )
    }

    private suspend fun requireOwner(accountId: UUID, partyId: UUID) {
        if (accounts.getPartyId(accountId) != partyId) throw LowBalanceAccountNotOwnedException()
    }

    private fun LowBalanceAlertPreferenceEntity.toView(): LowBalanceAlertView =
        LowBalanceAlertView(accountId, currency, enabled, threshold, rearmMargin)

    companion object {
        const val LOW_BALANCE_REQUEST = "LOW_BALANCE_ALERT_REQUEST"
        private val COOLDOWN = Duration.ofHours(24)
    }
}
