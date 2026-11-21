// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sdd.integration

import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.sdd.application.port.out.SddMandateRepository
import com.openbank.sdd.domain.model.MandateAmendment
import com.openbank.sdd.domain.model.MandateStatus
import com.openbank.sdd.domain.model.SddMandate
import com.openbank.sdd.domain.model.SddScheme
import com.openbank.sdd.domain.model.SequenceType
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * Regression coverage in the family of #2148 / #2187 (`LedgerSchedulerVertxContextIT`,
 * `StandingOrderExecutionSweepIT`): sdd ships `%test.quarkus.scheduler.enabled: false`, so every
 * other test in this module sees [com.openbank.sdd.infrastructure.scheduler.MandateExpiryScheduler]
 * only through a **direct call** — and a direct call supplies the Vert.x context the real cron does
 * not. This IT is the one place the scheduler is switched back on: the profile shrinks the cron to
 * every two seconds against a real Postgres, flips `openbank.sdd.expiry.enabled` on, and waits for
 * a genuinely scheduler-dispatched sweep to expire an idle mandate.
 *
 * The sweep being enabled is what the per-environment rollout of `OPENBANK_SDD_EXPIRY_ENABLED`
 * relies on; without this test a scheduler that silently never ran (no Vert.x context, a swallowed
 * throw before the per-item try/catch) would be indistinguishable from "no mandates were idle".
 *
 * The outbox dispatcher is switched off here so a dispatched event cannot race the assertions. The
 * sweep must also write exactly one `sdd.mandate.expired.v1` outbox row per expired mandate, in the
 * SAME transaction as the status change (equal `xmin`), and none for the live mandate.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(MandateExpirySchedulerVertxContextIT.FastExpiryProfile::class)
class MandateExpirySchedulerVertxContextIT {

    /**
     * Literal values only: a `QuarkusTestProfile` loads in a **different classloader** from the
     * test class, so a companion-initialized value here would hand the scheduler one value and the
     * assertion another.
     */
    class FastExpiryProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.scheduler.enabled" to "true",
            "openbank.sdd.expiry-cron" to "*/2 * * * * ?",
            "openbank.sdd.expiry.enabled" to "true",
            "openbank.outbox.dispatch-enabled" to "false",
        )
    }

    @Inject
    lateinit var mandates: SddMandateRepository

    @Inject
    lateinit var dataSource: DataSource

    /** (mandate xmin, outbox xmin, event_type) for every outbox row of [mandateId]. */
    private fun outboxWritersOf(mandateId: UUID): List<Triple<String, String, String>> =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT m.xmin::text, o.xmin::text, o.event_type
                FROM sdd_mandate m
                JOIN sdd_outbox o ON o.aggregate_id = m.id
                WHERE m.id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, mandateId)
                statement.executeQuery().use { rows ->
                    generateSequence { if (rows.next()) rows else null }
                        .map { Triple(it.getString(1), it.getString(2), it.getString(3)) }
                        .toList()
                }
            }
        }

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    /** Polls [ready] until it holds, or the budget runs out. */
    private fun await(ready: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + BUDGET_NANOS
        while (System.nanoTime() < deadline) {
            if (ready()) return true
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        return ready()
    }

    @Test
    fun `the scheduled expiry sweep expires an idle mandate and leaves a live one alone`() {
        val idle = mandate(signatureDate = LocalDate.of(2020, 1, 1), lastCollection = null)
        val live = mandate(signatureDate = LocalDate.now(), lastCollection = LocalDate.now())
        onEventLoop {
            mandates.save(idle).awaitSuspending()
            mandates.save(live).awaitSuspending()
        }

        val expired = await {
            onEventLoop { mandates.findById(idle.id).awaitSuspending() }?.status == MandateStatus.EXPIRED
        }

        assertThat(expired)
            .describedAs("the cron-dispatched expiry sweep flips an idle mandate to EXPIRED")
            .isTrue()
        assertThat(onEventLoop { mandates.findById(live.id).awaitSuspending() }?.status)
            .describedAs("a mandate with a recent collection is not idle and stays ACTIVE")
            .isEqualTo(MandateStatus.ACTIVE)

        val writers = outboxWritersOf(idle.id)
        assertThat(writers.map { it.third })
            .describedAs("the sweep emits exactly one expired event for the idle mandate")
            .containsExactly("sdd.mandate.expired.v1")
        assertThat(writers.single().second)
            .describedAs("the EXPIRED row and its outbox row commit in one transaction")
            .isEqualTo(writers.single().first)
        assertThat(outboxWritersOf(live.id))
            .describedAs("a live mandate gets no expired event")
            .isEmpty()
    }

    private fun mandate(signatureDate: LocalDate, lastCollection: LocalDate?) = SddMandate(
        id = UUID.randomUUID(),
        accountId = UUID.randomUUID(),
        debtorIban = "CZ6508000000192000145399",
        creditorIdentifier = "CZ12ZZZ123456789",
        umr = "UMR-${UUID.randomUUID().toString().take(8)}",
        scheme = SddScheme.CORE,
        sequenceType = SequenceType.RCUR,
        creditorName = "Creditor",
        debtorName = "Debtor",
        signatureDate = signatureDate,
        status = MandateStatus.ACTIVE,
        b2bConfirmed = false,
        lastCollectionDate = lastCollection,
        lastPreNotificationDate = null,
        createdAt = Instant.now(),
        amendments = emptyList<MandateAmendment>(),
    )

    private companion object {
        const val BUDGET_NANOS = 30_000_000_000L
        const val POLL_INTERVAL_MILLIS = 200L
    }
}
