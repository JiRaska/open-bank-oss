// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.integration

import com.openbank.fx.application.port.`in`.CnbPolicyRateUseCase
import com.openbank.fx.application.port.out.CnbPolicyRateDocument
import com.openbank.fx.application.port.out.CnbPolicyRateFeed
import com.openbank.fx.domain.cnb.CnbPolicyInstrument
import com.openbank.fx.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.mutiny.coroutines.uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.hibernate.reactive.mutiny.Mutiny
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.time.LocalDate

/**
 * The policy-rate ingestion against a real Postgres, dispatched by the REAL scheduler (its boot-time
 * trigger, shrunk to 1 s) so the Vert.x-context path the reactive session needs is the one under
 * test (#2187). Only the external ČNB download is stubbed — with the real committed files.
 *
 * Proves, in order: the boot run stores every row of all three histories and publishes them AND the
 * minimum-reserve ratio and remuneration downloaded from the real ČNB workbook through the outbox; a re-run is a no-op (no row, no
 * event); a changed rate for a stored date is applied as a REVISION with exactly one new event.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
@TestProfile(CnbPolicyRateIngestionIT.BootRunProfile::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class CnbPolicyRateIngestionIT {

    class BootRunProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.scheduler.enabled" to "true",
            "openbank.cnb.policy-rates.initial-delay" to "1s",
            "openbank.cnb.policy-rates.startup-every" to "1h",
            "openbank.cnb.policy-rates.ingestion-cron" to "off",
            "openbank.outbox.dispatch-enabled" to "false",
        )

        override fun getEnabledAlternatives(): MutableSet<Class<*>> = mutableSetOf(StubFeed::class.java)
    }

    /** Serves the committed real ČNB files; [overrides] lets a test serve a changed one. */
    @Alternative
    @ApplicationScoped
    class StubFeed : CnbPolicyRateFeed {
        @Volatile
        var overrides: Map<CnbPolicyInstrument, ByteArray> = emptyMap()

        override suspend fun fetch(instrument: CnbPolicyInstrument): CnbPolicyRateDocument = CnbPolicyRateDocument(
            "https://stub.invalid/${FILES.getValue(instrument)}",
            overrides[instrument] ?: real(instrument),
        )

        override suspend fun fetchMinimumReserves(): CnbPolicyRateDocument =
            CnbPolicyRateDocument("https://stub.invalid/$PMR", resource(PMR))
    }

    @Inject
    lateinit var useCase: CnbPolicyRateUseCase

    @Inject
    lateinit var stub: StubFeed

    @Inject
    lateinit var sf: Mutiny.SessionFactory

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    private fun count(sql: String): Long = onEventLoop {
        sf.withSession { s -> s.createNativeQuery<Long>(sql, Long::class.javaObjectType).singleResult }
            .awaitSuspending()
    }

    private fun rows(instrument: CnbPolicyInstrument) =
        count("select count(*) from cnb_policy_rate where instrument = '${instrument.name}'")

    private fun events() = count("select count(*) from fx_outbox where event_type = 'fx.cnb-policy-rate.published.v1'")

    @Test
    @Order(1)
    fun `the boot-time run stores every row of every history and publishes it with the facts`() {
        val deadline = System.nanoTime() + BUDGET_NANOS
        while (events() < TOTAL_EVENTS && System.nanoTime() < deadline) Thread.sleep(POLL_MILLIS)

        assertThat(rows(CnbPolicyInstrument.REPO_2W)).isEqualTo(117)
        assertThat(rows(CnbPolicyInstrument.DISCOUNT)).isEqualTo(77)
        assertThat(rows(CnbPolicyInstrument.LOMBARD)).isEqualTo(85)
        assertThat(events())
            .describedAs("one event per stored row, including the three downloaded minimum-reserve change points")
            .isEqualTo(TOTAL_EVENTS)
        assertThat(count("select count(*) from cnb_policy_rate where published_at is null")).isZero()

        val repo = onEventLoop { useCase.effectiveAt(CnbPolicyInstrument.REPO_2W, LocalDate.of(2026, 7, 1)) }!!
        assertThat(repo.effectiveFrom).isEqualTo(LocalDate.of(2026, 6, 19))
        assertThat(repo.rate).isEqualByComparingTo("0.0375")
        assertThat(repo.contentSha256).hasSize(64)

        val ratio = onEventLoop {
            useCase.effectiveAt(CnbPolicyInstrument.MIN_RESERVE_RATIO, LocalDate.of(2026, 7, 1))
        }!!
        assertThat(ratio.rate).isEqualByComparingTo("0.04")
        assertThat(ratio.effectiveFrom).isEqualTo(LocalDate.of(2025, 1, 2))
        assertThat(ratio.sourceUrl).endsWith(PMR)
        val before =
            onEventLoop { useCase.effectiveAt(CnbPolicyInstrument.MIN_RESERVE_RATIO, LocalDate.of(2025, 1, 1)) }
        assertThat(before!!.rate).isEqualByComparingTo("0.02")
        // Before the workbook's first representable ratio there is nothing — not a default.
        assertThat(
            onEventLoop {
                useCase.effectiveAt(CnbPolicyInstrument.MIN_RESERVE_RATIO, LocalDate.of(1999, 10, 6))
            },
        )
            .isNull()
        assertThat(rows(CnbPolicyInstrument.MIN_RESERVE_REMUNERATION)).isEqualTo(1)
    }

    @Test
    @Order(2)
    fun `a re-run stores nothing and publishes nothing`() {
        val before = events()

        val outcome = onEventLoop { useCase.ingest(CnbPolicyInstrument.REPO_2W) }

        assertThat(outcome.counts.inserted).isZero()
        assertThat(outcome.counts.unchanged).isEqualTo(117)
        assertThat(outcome.counts.revised).isZero()
        assertThat(outcome.published).isZero()
        assertThat(events()).isEqualTo(before)
    }

    @Test
    @Order(3)
    fun `a changed rate for a stored date is a revision with exactly one new event`() {
        val before = events()
        val changed = String(real(CnbPolicyInstrument.LOMBARD), Charsets.UTF_8)
            .replace("20260619|4,75", "20260619|5,00")
        stub.overrides = mapOf(CnbPolicyInstrument.LOMBARD to changed.toByteArray())

        val outcome = onEventLoop { useCase.ingest(CnbPolicyInstrument.LOMBARD) }

        assertThat(outcome.counts.revised).isEqualTo(1)
        assertThat(outcome.revisions.single().previousRate).isEqualByComparingTo("0.0475")
        assertThat(outcome.published).isEqualTo(1)
        assertThat(events()).isEqualTo(before + 1)
        val fact = onEventLoop { useCase.effectiveAt(CnbPolicyInstrument.LOMBARD, LocalDate.of(2026, 6, 19)) }!!
        assertThat(fact.rate).isEqualByComparingTo("0.05")
        assertThat(fact.previousRate).isEqualByComparingTo("0.0475")
        assertThat(fact.revisedAt).isNotNull()
        stub.overrides = emptyMap()
    }

    companion object {
        private val FILES = mapOf(
            CnbPolicyInstrument.REPO_2W to "vyvoj_repo_historie.txt",
            CnbPolicyInstrument.DISCOUNT to "vyvoj_diskontni_historie.txt",
            CnbPolicyInstrument.LOMBARD to "vyvoj_lombard_historie.txt",
        )

        const val PMR = "PMR_historie_zmen.xlsx"

        fun real(instrument: CnbPolicyInstrument): ByteArray = resource(FILES.getValue(instrument))

        fun resource(name: String): ByteArray = requireNotNull(
            CnbPolicyRateIngestionIT::class.java.getResourceAsStream("/cnb/policy-rates/$name"),
        ).readBytes()

        const val TOTAL_EVENTS = 117L + 77L + 85L + 2L + 1L
        const val BUDGET_NANOS = 60_000_000_000L
        const val POLL_MILLIS = 250L
    }
}
