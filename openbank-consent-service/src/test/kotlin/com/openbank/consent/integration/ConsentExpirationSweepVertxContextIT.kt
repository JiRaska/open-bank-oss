// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.consent.integration

import com.openbank.consent.application.port.out.ConsentRepository
import com.openbank.consent.domain.model.Consent
import com.openbank.consent.domain.model.ConsentScope
import com.openbank.consent.domain.model.ConsentStatus
import com.openbank.consent.domain.model.GranteeType
import com.openbank.consent.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Regression coverage for #2913 — the consent expiration sweep had never once succeeded.
 *
 * [com.openbank.consent.infrastructure.ConsentExpirationJob.sweepExpiredConsents] was a plain
 * (non-`suspend`) `@Scheduled` method that built a `Uni` and `subscribe()`d it. Quarkus invokes
 * such a method on a bare `executor-thread` carrying no Vert.x context, so the first reactive
 * Panache call inside the pipeline threw
 * `HR000068: This method should exclusively be invoked from a Vert.x EventLoop thread` — hourly,
 * for the life of the service. The failure landed in the subscriber's failure callback as one
 * ERROR line: the pod stayed ready and the metric was only emitted on success, so a job that had
 * never run looked exactly like a job with nothing to do.
 *
 * **Why this drives the cron rather than calling the method.** The defect is in how the framework
 * dispatches the method, not in its body. A test that calls `sweepExpiredConsents()` (or
 * `buildSweepPipeline`) directly supplies the very Vert.x context the scheduler does not — which
 * is exactly why the five existing [com.openbank.consent.infrastructure.ConsentExpirationJobTest]
 * cases were green against broken code the whole time. The profile below shrinks the cron to every
 * two seconds so the real scheduler dispatch, the real Postgres read and the real ACTIVE→EXPIRED
 * transition are all exercised for real.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(ConsentExpirationSweepVertxContextIT.FastSweepProfile::class)
class ConsentExpirationSweepVertxContextIT {

    /**
     * Fires the sweep every two seconds instead of hourly at HH:05. The outbox dispatcher is
     * pinned off so its own tick cannot race the assertion — the sweep enqueues its
     * `ConsentExpired` in the same transaction as the status flip, and it is the flip this
     * asserts.
     */
    class FastSweepProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.scheduler.enabled" to "true",
            "openbank.consent.expiration-cron" to "*/2 * * * * ?",
            "openbank.outbox.dispatch-enabled" to "false",
        )
    }

    @Inject
    lateinit var consents: ConsentRepository

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    /** An ACTIVE consent whose `validTo` is already in the past, so the next sweep must claim it. */
    private fun seedLapsedConsent(): UUID {
        val now = OffsetDateTime.now()
        val consent = Consent(
            id = UUID.randomUUID(),
            partyId = UUID.randomUUID(),
            granteeId = "tpp-sweep-it",
            granteeType = GranteeType.TPP,
            granteeName = "Sweep IT TPP",
            scopes = setOf(ConsentScope.ACCOUNTS_READ),
            accountIbans = null,
            validFrom = now.minusDays(30),
            validTo = now.minusHours(1),
            redirectUri = null,
            tppTransactionId = null,
            ipAddress = null,
            userAgent = null,
            status = ConsentStatus.ACTIVE,
            createdAt = now.minusDays(30),
            updatedAt = now.minusDays(30),
            scaSessionId = null,
        )
        onEventLoop { consents.save(consent) }
        return consent.id
    }

    @Test
    fun `the scheduled sweep transitions a lapsed consent to EXPIRED`() {
        val id = seedLapsedConsent()

        val deadline = System.nanoTime() + BUDGET_NANOS
        var status = onEventLoop { consents.findById(id) }?.status
        while (status != ConsentStatus.EXPIRED && System.nanoTime() < deadline) {
            Thread.sleep(POLL_INTERVAL_MILLIS)
            status = onEventLoop { consents.findById(id) }?.status
        }

        assertThat(status)
            .describedAs(
                "a scheduler-dispatched sweep must transition the lapsed consent — a consent still " +
                    "ACTIVE means the run threw HR000068 off the Vert.x context on its first query " +
                    "and the failure callback swallowed it into one ERROR line (#2913)",
            )
            .isEqualTo(ConsentStatus.EXPIRED)
    }

    private companion object {
        /** Generous against the 2 s cron so a slow CI runner cannot flake the wait. */
        const val BUDGET_NANOS = 60_000_000_000L
        const val POLL_INTERVAL_MILLIS = 250L
    }
}
