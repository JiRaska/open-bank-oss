// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.fraud.integration

import com.openbank.fraud.application.port.out.AccountPartyLookupPort
import com.openbank.fraud.application.port.out.FraudHoldRecord
import com.openbank.fraud.application.port.out.FraudHoldRepository
import com.openbank.fraud.application.port.out.FraudScoreRepository
import com.openbank.fraud.application.usecase.FraudHoldService
import com.openbank.fraud.domain.model.FraudScore
import com.openbank.fraud.domain.model.FraudVerdict
import com.openbank.fraud.domain.model.ScoreRequest
import com.openbank.fraud.infrastructure.persistence.FraudOutboxRepositoryImpl
import com.openbank.fraud.infrastructure.persistence.entity.FraudOutboxEntity
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.asUni
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Drives [FraudHoldService] through real Postgres (ADR-0050 outbox atomicity, and the
 * find-then-merge upsert on `fraud_hold`'s assigned id both need a real Vert.x context — a bare
 * MockK unit test cannot exercise `Panache.withTransaction`, per this repo's own documented
 * footgun). [StubAccountPartyLookupPort] replaces the real `AccountServiceClient` bean
 * (`@Alternative @Priority(1)`, same idiom as `MarketingGateIT.StubContactGateProducer`) — no real
 * account-service is reachable from this IT's stack.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.fraud.it.PostgresRedisTestResource::class)
class FraudHoldServiceIT {

    @Inject
    lateinit var scoreRepository: FraudScoreRepository

    @Inject
    lateinit var holdService: FraudHoldService

    @Inject
    lateinit var holdRepository: FraudHoldRepository

    @Inject
    lateinit var outboxRepository: FraudOutboxTestRepository

    @Inject
    lateinit var outboxWriter: FraudOutboxRepositoryImpl

    private fun <T> runSuspend(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni() }

    private fun seedReviewScores(accountId: UUID, count: Int) = repeat(count) {
        runSuspend {
            scoreRepository.save(
                ScoreRequest(amount = BigDecimal("500.00"), currency = "EUR", rail = "SEPA", accountId = accountId),
                FraudScore(FraudVerdict.REVIEW, 60, listOf("velocity"), "v4"),
            )
        }
    }

    private fun activeHold(partyId: UUID): FraudHoldRecord? = runSuspend { holdRepository.findActive(partyId) }

    private fun outboxCountFor(eventType: String): Long =
        VertxContextSupport.subscribeAndAwait { Panache.withSession { outboxRepository.count("eventType", eventType) } }

    @Test
    fun `three REVIEW verdicts in the window raise a fraud hold and emit fraud_hold_changed`() {
        val accountId = UUID.randomUUID()
        val partyId = UUID.randomUUID()
        StubAccountPartyLookupPort.partyId = partyId
        seedReviewScores(accountId, 3)

        runSuspend { holdService.maybeRaise(accountId, FraudVerdict.REVIEW) }

        val hold = activeHold(partyId)
        assertThat(hold).isNotNull
        assertThat(hold?.accountId).isEqualTo(accountId)
        assertThat(hold?.reason).isEqualTo("repeated_review")
        assertThat(outboxCountFor("fraud.hold_changed")).isGreaterThanOrEqualTo(1)
    }

    @Test
    fun `below-threshold REVIEW count never raises a hold`() {
        val accountId = UUID.randomUUID()
        val partyId = UUID.randomUUID()
        StubAccountPartyLookupPort.partyId = partyId
        seedReviewScores(accountId, 2)

        runSuspend { holdService.maybeRaise(accountId, FraudVerdict.REVIEW) }

        assertThat(activeHold(partyId)).isNull()
    }

    @Test
    fun `expiry sweep clears an expired hold and emits the expired reason`() {
        val accountId = UUID.randomUUID()
        val partyId = UUID.randomUUID()
        val now = Instant.now()
        runSuspendUni {
            holdRepository.raise(
                partyId,
                accountId,
                "repeated_review",
                "fraud-hold-v1",
                now.minusSeconds(120),
                now.minusSeconds(60),
            )
        }
        assertThat(activeHold(partyId)).isNotNull

        runSuspend { holdService.sweepExpired() }

        assertThat(activeHold(partyId)).isNull()
        assertThat(outboxCountFor("fraud.hold_changed")).isGreaterThanOrEqualTo(1)
    }

    @Test
    fun `raising a hold twice for the same party updates the existing row instead of duplicating it`() {
        val accountId = UUID.randomUUID()
        val partyId = UUID.randomUUID()
        val now = Instant.now()
        runSuspendUni {
            holdRepository.raise(partyId, accountId, "repeated_review", "fraud-hold-v1", now, now.plusSeconds(3600))
        }
        runSuspendUni {
            holdRepository.raise(partyId, accountId, "repeated_review", "fraud-hold-v1", now, now.plusSeconds(7200))
        }

        val hold = activeHold(partyId)
        assertThat(hold).isNotNull
        assertThat(hold?.expiresAt).isEqualTo(now.plusSeconds(7200))
    }

    @Test
    fun `the outbox repository's full processable cycle round-trips through Postgres`() {
        val eventId = UUID.randomUUID()
        val aggregateId = UUID.randomUUID()
        VertxContextSupport.subscribeAndAwait {
            Panache.withTransaction {
                outboxWriter.persistInTransaction(
                    OutboxMessage(
                        eventId = eventId,
                        aggregateId = aggregateId,
                        eventType = "fraud.hold_changed",
                        payload = "{}",
                        createdAt = Instant.now(),
                    ),
                )
            }
        }

        val pending = runSuspend { outboxWriter.countProcessable() }
        assertThat(pending).isGreaterThanOrEqualTo(1)

        val claimed = runSuspend { outboxWriter.claimProcessable(10) }
        assertThat(claimed.map { it.eventId }).contains(eventId)

        runSuspend { outboxWriter.markSent(eventId) }
        val listedAfterSent = runSuspend { outboxWriter.listProcessable(10) }
        assertThat(listedAfterSent.map { it.eventId }).doesNotContain(eventId)

        val secondEventId = UUID.randomUUID()
        VertxContextSupport.subscribeAndAwait {
            Panache.withTransaction {
                outboxWriter.persistInTransaction(
                    OutboxMessage(
                        eventId = secondEventId,
                        aggregateId = aggregateId,
                        eventType = "fraud.hold_changed",
                        payload = "{}",
                        createdAt = Instant.now(),
                    ),
                )
            }
        }
        runSuspend { outboxWriter.markFailed(secondEventId, "boom") }
        val afterFailure = runSuspend { outboxWriter.listProcessable(10) }
        assertThat(afterFailure.map { it.eventId }).contains(secondEventId)
    }

    private fun runSuspendUni(block: () -> io.smallrye.mutiny.Uni<Void>) {
        VertxContextSupport.subscribeAndAwait { Panache.withTransaction { block() } }
    }
}

/** Test-only projection so the IT can count outbox rows without exposing repository internals to prod. */
@ApplicationScoped
class FraudOutboxTestRepository : PanacheRepository<FraudOutboxEntity>

/**
 * Replaces the real `AccountServiceClient` bean. [partyId] is a plain knob each test sets before
 * driving `maybeRaise` — deterministic without a real account-service call.
 */
@Alternative
@Priority(1)
@ApplicationScoped
class StubAccountPartyLookupPort : AccountPartyLookupPort {
    override suspend fun findPartyByAccountId(accountId: UUID): UUID? = partyId

    companion object {
        var partyId: UUID? = null
    }
}
