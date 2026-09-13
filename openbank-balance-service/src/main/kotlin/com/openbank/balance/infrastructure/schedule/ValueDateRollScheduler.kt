// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.infrastructure.schedule

import com.openbank.balance.application.port.out.BalanceEventPublisher
import com.openbank.balance.application.port.out.BalanceRepository
import com.openbank.balance.domain.model.BalanceEvent
import com.openbank.balance.domain.model.BalanceEventActors
import com.openbank.balance.domain.model.BalanceEventType
import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.libs.domain.event.EventActor
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import com.openbank.libs.persistence.lock.ClusterLock
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.time.OffsetDateTime

/**
 * ADR-0178 Phase 2 (#1745) — the daily **value-date roll**.
 *
 * ## What it does, and deliberately does not do
 *
 * It announces maturity; it does not compute money. The effective figures
 * ([com.openbank.balance.domain.model.Balance.effectiveBooked] /
 * [com.openbank.balance.domain.model.Balance.effectiveAvailable]) are DERIVED per read from the
 * dated projection audit against the current accounting day, so a credit becomes spendable at its
 * value date whether or not this job ever runs. What a pure read-side derivation cannot do is *tell
 * anyone*: nothing emits a balance event on the day the money matures, so the mobile app, the
 * admin-ui and every downstream projection would keep showing yesterday's spendable figure until
 * some unrelated movement happened to refresh it. This job closes that gap by publishing a
 * `BALANCE_UPDATED` for each account whose credit matured today.
 *
 * That split is the whole design, and it is chosen for its failure mode. A materialized
 * `effective_booked` column promoted by this job would make a missed run a **wrong money figure**;
 * derived-plus-announce makes a missed run a **delayed notification**. On a money-path service the
 * second is the only acceptable one — and this job is exactly the kind that silently never runs
 * (#2148/#2187: five schedulers, three money-path, had never executed once).
 *
 * ## Vert.x context
 *
 * `suspend fun`, not a plain method wrapping `runBlocking` — Quarkus dispatches a non-suspend
 * `@Scheduled` method on a bare executor thread with no Vert.x context, and the first reactive
 * Panache call (here [ClusterLock], before anything else) throws `HR000068` and aborts the tick
 * having done nothing, silently. `ValueDateRollSchedulerIT` drives the real cron rather than
 * calling this method, because a direct call supplies the very context the scheduler does not.
 *
 * ## Cross-pod exclusion
 *
 * An Argo Rollouts canary window runs old and new pods together and both fire this trigger, which
 * would publish each maturity event twice. The publication is not idempotent downstream, so the
 * tick is held under [ClusterLock] exactly as the reconciliation scheduler is (#1201).
 */
@ApplicationScoped
class ValueDateRollScheduler(
    private val balanceRepo: BalanceRepository,
    private val eventPublisher: BalanceEventPublisher,
    private val accountingClock: AccountingClock,
    private val clusterLock: ClusterLock,
) {
    private val log = Logger.getLogger(ValueDateRollScheduler::class.java)

    /**
     * Field-injected rather than a constructor parameter: this constructor already had 4, and
     * threading a 5th purely for the heartbeat is no worse, but the pattern is kept consistent
     * with the two-heartbeats-added-under-time-pressure siblings (FraudHoldService,
     * SavingsProposalExpiryScheduler) so a reviewer sees the same shape each time.
     */
    @Inject
    lateinit var domainMetrics: DomainMetrics

    private var liveness: WorkflowLivenessRecorder? = null

    /**
     * Registered from `StartupEvent`, not an `init` block: `@ApplicationScoped` is LAZY, so a bean
     * created on first use would publish no gauge until something touched it — and for a tick
     * nothing else calls, that could be never (this repo's own PdfBoxPadesSealAdapter lesson).
     */
    fun registerLiveness(@Observes @Suppress("UNUSED_PARAMETER") event: StartupEvent) {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)
    }

    /**
     * Runs shortly after the accounting day rolls over, in the bank zone — the credits whose
     * `entry_date` is today became effective at midnight, so the announcement belongs at the start
     * of the day, not at the 23:30 reconciliation tick.
     */
    @Scheduled(
        cron = "{openbank.balance.value-date-roll.cron:0 5 0 * * ?}",
        timeZone = "Europe/Prague",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun rollDaily() {
        val ran = clusterLock.tryRunExclusively(JOB_NAME) {
            try {
                announceMaturities()
                // Only on the path that completed. A heartbeat inside (or after) the catch would
                // assert the very thing it exists to disprove — and this tick swallows its own
                // exception, so a permanently broken roll is otherwise indistinguishable from a
                // healthy quiet one: no exception escapes, and "0 matured" is the normal case on
                // most days.
                liveness?.recordSuccess()
            } catch (ex: Exception) {
                // observed-by: the workflow-liveness gauge for JOB_NAME. `recordSuccess()` above runs
                // only on the completed path, so a permanently failing roll leaves the gauge climbing
                // until WorkflowLivenessStale fires (ADR-0237) — the failure IS visible, just not
                // through a DLQ. Never crash the runtime: the money figures are derived and already
                // correct without this job, so a failure here costs a notification, not a balance.
                log.errorf(ex, "Value-date roll failed: %s", ex.message)
            }
        }
        if (ran == null) {
            log.infof("Value-date roll: another pod already holds this tick's lock — skipping")
        }
    }

    private suspend fun announceMaturities() {
        val today = accountingClock.today()
        val matured = balanceRepo.findCreditsMaturingOn(today)
        if (matured.isEmpty()) {
            log.debugf("Value-date roll %s: no credits matured", today)
            return
        }

        var published = 0
        matured.forEach { key ->
            val balance = balanceRepo.findByAccountIdAndCurrency(key.accountId, key.currency)
            if (balance == null) {
                // The projection audit holds a row for an account whose balance row is gone. Report
                // it and carry on: skipping one announcement must not strand the rest of the batch.
                log.warnf(
                    "Value-date roll %s: no balance row for account=%s currency=%s",
                    today,
                    key.accountId,
                    key.currency,
                )
                return@forEach
            }
            // Re-read the tail as of today so the published figures are the post-maturity ones —
            // the credits that matured today are no longer in it, by construction of `> :asOf`.
            val effective = balance.copy(
                notYetEffectiveCredit = balanceRepo.sumNotYetEffectiveCredit(
                    key.accountId,
                    key.currency,
                    today,
                ),
            )
            eventPublisher.publish(
                BalanceEvent(
                    // UUIDv7 (ADR-0106): a durable, index-ordered event id.
                    eventId = Ids.newId(),
                    eventType = BalanceEventType.BALANCE_UPDATED,
                    accountId = key.accountId,
                    currency = key.currency,
                    // No movement: the money was already booked on its posting day. What changed
                    // today is only that it became effective, so there is no delta to report.
                    amount = BigDecimal.ZERO,
                    bookedAmount = effective.effectiveBooked(),
                    availableAmount = effective.effectiveAvailable(),
                    reservedAmount = effective.reservedAmount,
                    occurredAt = OffsetDateTime.ofInstant(accountingClock.instant(), java.time.ZoneOffset.UTC),
                    actorId = BalanceEventActors.VALUE_DATE_ROLL,
                    actorType = EventActor.TYPE_SYSTEM,
                    sourceService = "balance-service",
                ),
            )
            published++
        }
        log.infof("Value-date roll %s: announced %d matured credit(s)", today, published)
    }

    private companion object {
        const val JOB_NAME = "balance.value-date-roll"
        const val WORKFLOW_NAME = "balance-value-date-roll"

        /** Matches the `@Scheduled` default of once daily. Wide on purpose: this job is not the
         *  money path — a missed run delays a notification, never a wrong figure — so the
         *  staleness threshold should not page on a single skipped tick under ClusterLock
         *  contention. */
        val EXPECTED_INTERVAL: java.time.Duration = java.time.Duration.ofDays(1)
    }
}
