// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.fx.integration

import com.openbank.fx.application.port.out.CnbRateProvider
import com.openbank.fx.application.port.out.FxRateRepository
import com.openbank.fx.domain.model.RateSource
import com.openbank.fx.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Regression coverage for #2187 (the fleet sweep of #2148) — the daily ČNB fixing ingestion had
 * never ingested a single rate.
 *
 * [com.openbank.fx.infrastructure.schedule.CnbRateIngestionScheduler.ingestDailyFixing] was a plain
 * (non-`suspend`) method whose body was `runBlocking { … }`. Quarkus invokes such a method on a bare
 * `executor-thread`, which carries **no Vert.x context**, so the first reactive Panache query inside
 * (`FxRateRepository.findBySourceAndValidFrom`, via `sf.withSession`) threw
 * `HR000068: This method should exclusively be invoked from a Vert.x EventLoop thread`. The
 * scheduler's own `catch` then swallowed it into one ERROR line, so the statutory ADR-0046 fixing
 * was silently never ingested — and the ledger FX revaluation that consumes it (fixed in the same
 * sweep) had nothing to read even once its own scheduler worked.
 *
 * **Why this test drives the cron and not `ingestDailyFixing()`.** The defect is in *how the
 * framework invokes the method*, not in the method body: calling it directly from a test supplies a
 * Vert.x context the real scheduler does not, so `CnbRateIngestionSchedulerTest` (mocked use case,
 * direct call) passed against the broken code the whole time. The profile below shrinks the cron to
 * every two seconds and stubs only the external ČNB feed, so everything from the scheduler
 * dispatch through to the real Postgres write is exercised for real.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
@TestProfile(CnbIngestionSchedulerVertxContextIT.FastIngestionProfile::class)
class CnbIngestionSchedulerVertxContextIT {

    /**
     * Fires the ingestion every two seconds instead of at 14:40 Prague, narrows the ingested set to
     * one currency, and swaps the external ČNB HTTP feed for [StubCnbRateProvider] — the feed is
     * the one thing that genuinely cannot run here, and it is not what is under test. The outbox
     * dispatcher is pinned off so its own tick cannot interfere with the assertions.
     */
    class FastIngestionProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.scheduler.enabled" to "true",
            "openbank.cnb.ingestion-cron" to "*/2 * * * * ?",
            "openbank.cnb.currencies" to CURRENCY,
            "openbank.outbox.dispatch-enabled" to "false",
        )

        override fun getEnabledAlternatives(): MutableSet<Class<*>> = mutableSetOf(StubCnbRateProvider::class.java)
    }

    /** Serves one deterministic fixing instead of calling cnb.cz. */
    @Alternative
    @ApplicationScoped
    class StubCnbRateProvider : CnbRateProvider {
        override suspend fun fetchFixing(date: LocalDate?): String =
            """
            30.05.2026 #104
            země|měna|množství|kód|kurz
            EMU|euro|1|$CURRENCY|$RATE_TEXT
            """.trimIndent()
    }

    @Inject
    lateinit var rates: FxRateRepository

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    /**
     * Looked up by its exact `validFrom` rather than via `findLatestBySource`, whose `validTo > now`
     * filter would hide this deliberately fixed (and by now expired) fixing date — and by the same
     * token this cannot match a row any other IT left behind.
     */
    private fun ingestedFixing() =
        onEventLoop { rates.findBySourceAndValidFrom(CURRENCY, QUOTE, RateSource.CNB, VALID_FROM) }

    @Test
    fun `the scheduled CNB ingestion persists the fixing`() {
        val deadline = System.nanoTime() + BUDGET_NANOS
        var ingested = ingestedFixing()
        while (ingested == null && System.nanoTime() < deadline) {
            Thread.sleep(POLL_INTERVAL_MILLIS)
            ingested = ingestedFixing()
        }

        assertThat(ingested)
            .describedAs(
                "a scheduler-dispatched ingestion must persist the fixing — nothing persisted means " +
                    "the run threw HR000068 off the Vert.x context on its first query and the " +
                    "scheduler's catch swallowed it (#2187)",
            )
            .isNotNull
        // A mid rate carries no bank spread (ADR-0046), so bid == ask == the published rate.
        assertThat(ingested!!.bidRate).isEqualByComparingTo(BigDecimal(RATE))
        assertThat(ingested.askRate).isEqualByComparingTo(BigDecimal(RATE))
    }

    private companion object {
        const val CURRENCY = "EUR"
        const val QUOTE = "CZK"

        /** The feed writes rates with a decimal comma; the parser is what turns it into [RATE]. */
        const val RATE_TEXT = "25,145"
        const val RATE = "25.145"

        /** Matches the header of the stub feed above; the fixing is valid from its own Prague day. */
        val VALID_FROM: Instant = LocalDate.of(2026, 5, 30)
            .atStartOfDay(ZoneId.of("Europe/Prague"))
            .toInstant()

        /** Generous vs the 2 s cron so a slow CI runner cannot flake the wait. */
        const val BUDGET_NANOS = 60_000_000_000L
        const val POLL_INTERVAL_MILLIS = 250L
    }
}
