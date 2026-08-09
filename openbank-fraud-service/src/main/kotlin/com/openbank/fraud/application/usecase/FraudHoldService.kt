// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.application.usecase

import com.openbank.fraud.application.port.out.AccountPartyLookupPort
import com.openbank.fraud.application.port.out.FraudHoldRepository
import com.openbank.fraud.application.port.out.FraudScoreRepository
import com.openbank.fraud.domain.model.FraudVerdict
import com.openbank.fraud.infrastructure.persistence.FraudOutboxRepositoryImpl
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * ADR-0220 D3.5's fraud-hold signal (issue #2749) — deliberately conservative and
 * marketing-suppression-only, per the design decision recorded on the issue: triggers on
 * repeated REVIEW (no rule in `FraudRuleEngine` produces DECLINE today, and adding one would
 * change real scoring behaviour, which this signal must never do), and auto-expires rather than
 * needing a manual clear (there is no fraud-case/investigation lifecycle in this service to
 * resolve one from). This NEVER touches account/payment restrictions — those stay the entirely
 * separate, manual, `ROLE_OPERATOR`-gated account-service freeze.
 *
 * Called from [FraudScoringService.score] in the same fire-and-forget, exceptions-swallowed slot
 * as `runShadow` — a hold-raising failure must never affect the returned
 * [com.openbank.fraud.domain.model.FraudScore] or slow the money-path scoring call.
 */
@ApplicationScoped
class FraudHoldService @Inject constructor(
    private val scoreRepo: FraudScoreRepository,
    private val holdRepo: FraudHoldRepository,
    private val accountClient: AccountPartyLookupPort,
    private val outbox: FraudOutboxRepositoryImpl,
    private val clock: Clock,
    @ConfigProperty(name = "openbank.fraud-hold.review-threshold", defaultValue = "3")
    private val reviewThreshold: Int,
    @ConfigProperty(name = "openbank.fraud-hold.window-days", defaultValue = "30")
    private val windowDays: Long,
    @ConfigProperty(name = "openbank.fraud-hold.ttl-days", defaultValue = "30")
    private val ttlDays: Long,
) {
    private val log = Logger.getLogger(FraudHoldService::class.java)

    /**
     * Field-injected rather than a constructor parameter: the constructor already takes 8, and
     * detekt's LongParameterList fires AT `constructorThreshold: 9`, not above it.
     */
    @Inject
    lateinit var domainMetrics: DomainMetrics

    private var liveness: WorkflowLivenessRecorder? = null

    /**
     * Registered from `StartupEvent` rather than an `init` block: `@ApplicationScoped` is LAZY, so
     * a bean created on first use would publish no gauge until something happened to touch it —
     * and for a sweep that nothing else calls, that could be never.
     */
    fun registerLiveness(@Observes @Suppress("UNUSED_PARAMETER") event: StartupEvent) {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)
    }

    /**
     * Checked after every scoring decision, but only ever acts on REVIEW — ALLOW/CHALLENGE/DECLINE
     * carry no repeated-review signal to count. Side-effect only: never returns anything the
     * caller's response depends on.
     */
    @Suppress("TooGenericExceptionCaught") // same fail-open contract as runShadow — never breaks scoring
    suspend fun maybeRaise(accountId: UUID?, verdict: FraudVerdict) {
        if (verdict != FraudVerdict.REVIEW || accountId == null) return
        try {
            val since = Instant.now(clock).minus(Duration.ofDays(windowDays))
            val recentReviews = scoreRepo.countRecentByAccountAndVerdict(accountId, FraudVerdict.REVIEW.name, since)
            if (recentReviews < reviewThreshold) return

            val partyId = accountClient.findPartyByAccountId(accountId) ?: return
            val now = Instant.now(clock)
            Panache.withTransaction {
                holdRepo.raise(
                    partyId = partyId,
                    accountId = accountId,
                    reason = REASON_REPEATED_REVIEW,
                    ruleVersion = RULE_VERSION,
                    setAt = now,
                    expiresAt = now.plus(Duration.ofDays(ttlDays)),
                ).chain { _: Void? ->
                    emitHoldChanged(partyId, accountId, active = true, reason = REASON_REPEATED_REVIEW, now)
                }
            }.awaitSuspending()
            log.infof(
                "fraud-hold raised party=%s account=%s reviews_in_window=%d threshold=%d",
                partyId,
                accountId,
                recentReviews,
                reviewThreshold,
            )
        } catch (ex: Exception) {
            log.warnf(ex, "fraud-hold check failed for account %s (scoring unaffected)", accountId)
        }
    }

    /**
     * Clears every hold whose TTL has passed. Scheduled rather than reactive to a "good behaviour"
     * signal — this service has no positive event (an account simply not re-entering REVIEW is not
     * observable as a discrete moment) to clear on, matching the auto-expiry design decision.
     */
    @Scheduled(
        every = "\${openbank.fraud-hold.sweep-interval:1h}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        identity = "fraud-hold-expiry-sweep",
    )
    @Suppress("TooGenericExceptionCaught")
    suspend fun sweepExpired() {
        try {
            val now = Instant.now(clock)
            val expired = holdRepo.findExpiredActive(now)
            expired.forEach { record ->
                Panache.withTransaction {
                    holdRepo.clear(record.partyId)
                        .chain { _: Void? ->
                            emitHoldChanged(record.partyId, record.accountId, active = false, REASON_EXPIRED, now)
                        }
                }.awaitSuspending()
            }
            if (expired.isNotEmpty()) {
                log.infof("fraud-hold expiry sweep cleared=%d", expired.size)
            }
            // Only on the path that actually completed. A heartbeat inside (or after) the catch
            // would assert the very thing it exists to disprove — and this sweep swallows its
            // exception, so a permanently broken run is otherwise indistinguishable from a healthy
            // quiet one: no exception escapes, and "cleared=0" is the normal case.
            liveness?.recordSuccess()
        } catch (ex: Exception) {
            log.warnf(ex, "fraud-hold expiry sweep failed")
        }
    }

    private fun emitHoldChanged(
        partyId: UUID,
        accountId: UUID,
        active: Boolean,
        reason: String,
        occurredAt: Instant,
    ): Uni<Void> {
        val payload = """{"partyId":"$partyId","accountId":"$accountId","active":$active,""" +
            """"reason":"$reason","ruleVersion":"$RULE_VERSION","occurredAt":"$occurredAt"}"""
        return outbox.persistInTransaction(
            OutboxMessage(
                eventId = Ids.newId(),
                aggregateId = partyId,
                eventType = "fraud.hold_changed",
                payload = payload,
                createdAt = occurredAt,
            ),
        ).replaceWithVoid()
    }

    private companion object {
        const val RULE_VERSION = "fraud-hold-v1"
        const val REASON_REPEATED_REVIEW = "repeated_review"
        const val REASON_EXPIRED = "expired"
        const val WORKFLOW_NAME = "fraud-hold-expiry-sweep"

        /**
         * Matches the `openbank.fraud-hold.sweep-interval` default. The staleness rule bakes in a
         * 2x multiplier, so this is the schedule, not a tighter SLA — but a deployment that widens
         * the cron without widening this makes the gauge over-strict rather than lax, which is the
         * safe direction to be wrong in.
         */
        val EXPECTED_INTERVAL: Duration = Duration.ofHours(1)
    }
}
