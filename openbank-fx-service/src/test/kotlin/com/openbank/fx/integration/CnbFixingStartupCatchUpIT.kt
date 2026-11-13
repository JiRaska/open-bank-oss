// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.fx.integration

import com.openbank.fx.application.port.out.CnbRateProvider
import com.openbank.fx.application.port.out.FxRateRepository
import com.openbank.fx.domain.model.RateSource
import com.openbank.fx.it.PostgresRedisTestResource
import io.micrometer.core.instrument.MeterRegistry
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * A freshly started pod must ingest the ČNB fixing (and record the workflow's success) without
 * waiting for the 14:40 Prague cron. The cron is switched OFF here, so the only trigger that can
 * produce the row is the boot-time catch-up; delete that `@Scheduled` and this test goes red.
 * Like [CnbIngestionSchedulerVertxContextIT] it drives the real scheduler dispatch, never the
 * method directly, so the Vert.x-context regression (#2187) stays covered on this trigger too.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
@TestProfile(CnbFixingStartupCatchUpIT.StartupCatchUpProfile::class)
class CnbFixingStartupCatchUpIT {

    class StartupCatchUpProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.scheduler.enabled" to "true",
            "openbank.cnb.ingestion-cron" to "off",
            "openbank.cnb.initial-delay" to "1s",
            "openbank.cnb.startup-every" to "1h",
            "openbank.cnb.currencies" to "EUR",
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
            29.05.2026 #103
            země|měna|množství|kód|kurz
            EMU|euro|1|EUR|25,155
            """.trimIndent()
    }

    @Inject
    lateinit var rates: FxRateRepository

    @Inject
    lateinit var registry: MeterRegistry

    private fun ingestedFixing() = VertxContextSupport.subscribeAndAwait {
        uni(CoroutineScope(Dispatchers.Unconfined)) {
            rates.findBySourceAndValidFrom("EUR", "CZK", RateSource.CNB, VALID_FROM)
        }
    }

    private fun successRecorded(): Double? =
        registry.find("openbank.workflow.success.recorded").tag("workflow", "fx-cnb-ingestion").gauge()?.value()

    @Test
    fun `a fresh pod ingests the fixing at boot with the daily cron switched off`() {
        val deadline = System.nanoTime() + BUDGET_NANOS
        while ((ingestedFixing() == null || successRecorded() != 1.0) && System.nanoTime() < deadline) {
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        assertThat(ingestedFixing())
            .describedAs("with the cron off, only the boot-time catch-up trigger can have ingested this fixing")
            .isNotNull
        assertThat(successRecorded())
            .describedAs("the catch-up run must record the workflow success on this pod's own gauge")
            .isEqualTo(1.0)
    }

    private companion object {
        val VALID_FROM: Instant = LocalDate.of(2026, 5, 29).atStartOfDay(ZoneId.of("Europe/Prague")).toInstant()
        const val BUDGET_NANOS = 60_000_000_000L
        const val POLL_INTERVAL_MILLIS = 250L
    }
}
