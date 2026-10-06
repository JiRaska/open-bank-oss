// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.integration

import com.openbank.billing.domain.AnnualFeeSummary
import com.openbank.billing.infrastructure.outbox.BillingOutboxRepositoryImpl
import com.openbank.billing.infrastructure.persistence.repository.BillingAssessmentRepositoryImpl
import com.openbank.billing.it.PostgresRedisTestResource
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Real-DB proof that annual issuance survives outbox retention and concurrent scheduler reruns. */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
class AnnualFeeSummaryIssuanceIT {
    @Inject
    lateinit var assessments: BillingAssessmentRepositoryImpl

    @Inject
    lateinit var outbox: BillingOutboxRepositoryImpl

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    private fun summary(): AnnualFeeSummary = AnnualFeeSummary(
        accountId = "annual-it-${UUID.randomUUID()}",
        partyRef = "party-it",
        year = 2025,
        currency = "CZK",
        fees = emptyList(),
        totalFees = BigDecimal.ZERO,
        interestRate = null,
    )

    private fun aggregateId(summary: AnnualFeeSummary): UUID = UUID.nameUUIDFromBytes(
        "annual-fee-summary:${summary.accountId}:${summary.year}".toByteArray(StandardCharsets.UTF_8),
    )

    private fun rowCount(summary: AnnualFeeSummary): Long = onEventLoop {
        Panache.withSession {
            outbox.count("aggregateId = ?1 and eventType = ?2", aggregateId(summary), ANNUAL_EVENT)
        }.awaitSuspending()
    }

    @Test
    fun `SENT purge cannot make an annual summary publish twice`() {
        val summary = summary()
        val now = Instant.now()
        assertThat(onEventLoop { assessments.appendAnnualFeeSummaryEvent(summary, now) }).isTrue()
        val eventId = onEventLoop {
            Panache.withSession {
                outbox.find("aggregateId = ?1 and eventType = ?2", aggregateId(summary), ANNUAL_EVENT)
                    .firstResult()
            }.awaitSuspending()!!.eventId
        }
        onEventLoop { outbox.markSent(eventId, now.minus(Duration.ofDays(8))) }
        assertThat(onEventLoop { outbox.purgeSent(Duration.ofDays(7), 100, now) }).isGreaterThanOrEqualTo(1)
        assertThat(rowCount(summary)).isZero()

        assertThat(onEventLoop { assessments.appendAnnualFeeSummaryEvent(summary, now) }).isFalse()
        assertThat(rowCount(summary)).isZero()
    }

    @Test
    fun `concurrent annual runs append exactly one event`() {
        val summary = summary()
        val results = runBlocking {
            val first = async(Dispatchers.IO) {
                onEventLoop { assessments.appendAnnualFeeSummaryEvent(summary, Instant.now()) }
            }
            val second = async(Dispatchers.IO) {
                onEventLoop { assessments.appendAnnualFeeSummaryEvent(summary, Instant.now()) }
            }
            awaitAll(first, second)
        }
        assertThat(results).containsExactlyInAnyOrder(true, false)
        assertThat(rowCount(summary)).isEqualTo(1)
    }

    private companion object {
        const val ANNUAL_EVENT = "billing.annual-fee-summary.ready"
    }
}
