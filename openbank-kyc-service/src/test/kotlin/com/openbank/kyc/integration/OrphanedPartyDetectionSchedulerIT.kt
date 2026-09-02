// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.integration

import com.openbank.kyc.application.port.out.PartyDirectoryPage
import com.openbank.kyc.application.port.out.PartyDirectoryPort
import com.openbank.kyc.application.port.out.PartySummary
import com.openbank.kyc.it.PostgresTestResource
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.composite.CompositeMeterRegistry
import io.micrometer.prometheus.PrometheusMeterRegistry
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Regression coverage for the #5698 **detection** control: proof that the reconciliation actually
 * runs as a cron, and that its reactive query survives being dispatched by the scheduler.
 *
 * ### Why this drives the real cron instead of calling `refresh()`
 *
 * The defect class this guards against is in *how the framework invokes the method*, not in the
 * method body. Quarkus dispatches a plain (non-`suspend`) `@Scheduled` method on a bare
 * `executor-thread` that carries no Vert.x context, so the first reactive Panache query inside —
 * here [com.openbank.kyc.application.port.out.KycCaseRepository.findPartyIdsWithAnyCase] — throws
 * `HR000068` and the pass aborts having done nothing, silently. Five schedulers in this fleet, three
 * money-path, had **never** run for exactly that reason (#2148, #2187).
 *
 * A test that called `refresh()` directly would supply the very context the scheduler does not, and
 * would pass against that broken code. So this test never touches the bean: it seeds state, waits,
 * and asserts on the published gauges. Turning the gauge's `refresh` back into a plain, non-suspend
 * function that wraps its body in a blocking coroutine builder makes this time out with the orphan
 * gauge still at its boot-time 0 and `HR000068` in the log — measured, not assumed — which is also,
 * precisely, what the production failure would look like.
 *
 * ### What it proves beyond dispatch
 *
 *  - the batched `IN` projection query works against a real PostgreSQL (a Panache
 *    `.project()` with a mismatched constructor fails only at runtime);
 *  - the rule holds end to end — the party WITH a case row is not flagged, the one without is;
 *  - the gauges move off their boot-time zero only after a genuinely scheduler-driven pass.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(OrphanedPartyDetectionSchedulerIT.FastScanProfile::class)
class OrphanedPartyDetectionSchedulerIT {

    /**
     * Runs the reconciliation every two seconds instead of hourly, and drops the grace period to
     * zero so the seeded parties qualify immediately.
     *
     * Every value is a LITERAL. A `QuarkusTestProfile` loads in a different classloader from the
     * test class, so a companion object initialises twice — a randomised id generated here would
     * hand the scheduler one value and the assertion another.
     */
    class FastScanProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.kyc.orphan-detection.enabled" to "true",
            "openbank.kyc.orphan-detection.cron" to "*/2 * * * * ?",
            "openbank.kyc.orphan-detection.grace-period" to "PT0S",
            // The dispatcher would publish to a Kafka broker no test JVM runs; irrelevant to this
            // claim and pure log noise.
            "openbank.outbox.dispatch-enabled" to "false",
            // Random free HTTP port instead of the shared 8081 test default. This IT drives beans
            // and JDBC, never HTTP, so the port is incidental — but a parallel Gradle build on the
            // same machine binding 8081 first makes Quarkus fail to boot with QuarkusBindException,
            // which surfaces as this class failing for a reason that has nothing to do with it.
            "quarkus.http.test-port" to "0",
        )

        /** Scopes the stub party register to THIS profile, so no other test class sees it. */
        override fun getEnabledAlternatives(): Set<Class<*>> = setOf(StubPartyDirectory::class.java)
    }

    /**
     * A two-party register: one that never got a KYC case (the #5698 defect) and one that did.
     * Both ids are hardcoded literals for the classloader reason above.
     */
    @Alternative
    @Priority(1)
    @ApplicationScoped
    class StubPartyDirectory : PartyDirectoryPort {
        override suspend fun listParties(page: Int, size: Int): PartyDirectoryPage {
            if (page > 0) return PartyDirectoryPage(emptyList(), TOTAL)
            return PartyDirectoryPage(
                items = listOf(
                    PartySummary(UUID.fromString(STRANDED_PARTY_ID), "PENDING_KYC", CREATED_AT),
                    PartySummary(UUID.fromString(HANDLED_PARTY_ID), "PENDING_KYC", CREATED_AT),
                ),
                total = TOTAL,
            )
        }
    }

    @Inject
    lateinit var registry: MeterRegistry

    @Inject
    lateinit var dataSource: DataSource

    private fun gauge(name: String): Double = registry.find(name).tag("service", "kyc").gauge()?.value() ?: Double.NaN

    /** Seeds a KYC case for the handled party over plain JDBC — a reactive repo cannot be called here. */
    private fun seedCaseForHandledParty() {
        dataSource.connection.use { c ->
            c.prepareStatement(
                """
                INSERT INTO kyc_cases (case_id, party_id, status, risk_level, checks_json, created_at, updated_at)
                VALUES (?, ?, 'OPEN', 'MEDIUM', '[]', NOW(), NOW())
                """.trimIndent(),
            ).use { st ->
                st.setObject(1, UUID.randomUUID())
                st.setObject(2, UUID.fromString(HANDLED_PARTY_ID))
                st.executeUpdate()
            }
        }
    }

    /** Polls until a scheduler-driven pass has published a denominator, or the budget runs out. */
    private fun awaitScan(): Boolean = awaitGauge("openbank.kyc.orphan.detection.parties.scanned") {
        it >= TOTAL.toDouble()
    }

    /**
     * Polls [name] until [predicate] holds, then returns the value it settled on (or the last value
     * seen when the budget runs out, so a timeout still fails with the real number rather than a
     * bare "timed out").
     *
     * Polling the CONDITION rather than "some pass has completed" is load-bearing. Both test methods
     * share one JVM, one database and one 2s cron, so a pass that ran before this test's JDBC seed
     * committed has already satisfied "a scan happened" while still reporting the pre-seed count.
     * Waiting on the post-seed condition makes each method independent of the other's ordering.
     */
    private fun awaitGaugeValue(name: String, predicate: (Double) -> Boolean): Double {
        val deadline = System.nanoTime() + SCAN_BUDGET_NANOS
        var last = gauge(name)
        while (System.nanoTime() < deadline) {
            last = gauge(name)
            if (predicate(last)) return last
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        return last
    }

    private fun awaitGauge(name: String, predicate: (Double) -> Boolean): Boolean =
        predicate(awaitGaugeValue(name, predicate))

    @Test
    fun `the scheduled reconciliation runs and reports only the party with no KYC case`() {
        seedCaseForHandledParty()

        assertThat(awaitScan())
            .describedAs(
                "a scheduler-dispatched pass must publish parties_scanned=$TOTAL. Still 0 means the " +
                    "cron never produced a working pass — the HR000068 signature of a non-suspend " +
                    "@Scheduled method (#2148/#2187), which is invisible to any direct call",
            )
            .isTrue()

        assertThat(awaitGaugeValue("openbank.kyc.orphaned.parties") { it == 1.0 })
            .describedAs(
                "exactly one of the two seeded parties has no kyc_cases row, so the orphan gauge " +
                    "must read 1 — 0 would mean the batched IN projection matched everything, 2 " +
                    "that it matched nothing",
            )
            .isEqualTo(1.0)

        assertThat(gauge("openbank.kyc.orphaned.parties.oldest.age.seconds"))
            .describedAs("the stranded party was created well in the past, so its age must be positive")
            .isGreaterThan(0.0)
    }

    @Test
    fun `the liveness heartbeat records a success once a pass completes`() {
        assertThat(awaitScan()).isTrue()

        val recorded = registry.find("openbank.workflow.success.recorded")
            .tag("workflow", "kyc-orphaned-party-detection")
            .gauge()?.value()

        assertThat(recorded)
            .describedAs(
                "ADR-0237: without a heartbeat, a reconciliation that silently stops running is " +
                    "invisible — and a control that stops looking publishes the same 'no orphans' " +
                    "as a healthy one",
            )
            .isEqualTo(1.0)
    }

    @Test
    fun `the gauges are exposed under the exact metric names the alert rule queries`() {
        assertThat(awaitScan()).isTrue()

        // Quarkus injects a CompositeMeterRegistry; the Prometheus one that renders the exposition
        // format (and therefore decides the series names) is nested inside it.
        val prometheus = when (val r = registry) {
            is PrometheusMeterRegistry -> r
            is CompositeMeterRegistry -> r.registries.filterIsInstance<PrometheusMeterRegistry>().single()
            else -> error("no PrometheusMeterRegistry available, cannot verify exposed metric names")
        }
        val scrape = prometheus.scrape()

        // The Micrometer names are dotted; Prometheus sees them underscored. `KycPartiesWithoutCase`
        // in prometheus-rules-onboarding.yaml queries the UNDERSCORED form, and nothing else in the
        // repo can check that the two agree — a rule naming a series that is never emitted collects
        // an empty vector and reports "no orphans" forever, which is how the control-liveness
        // sentinel's own mechanism 3 was silently dead (#2187 follow-up).
        assertThat(scrape)
            .describedAs("the series `KycPartiesWithoutCase` alerts on must actually be emitted")
            .contains("openbank_kyc_orphaned_parties{")
        assertThat(scrape)
            .describedAs("triage series named in the alert's description")
            .contains("openbank_kyc_orphaned_parties_oldest_age_seconds{")
        assertThat(scrape)
            .describedAs("the denominator that separates 'no orphans' from 'scanned nothing'")
            .contains("openbank_kyc_orphan_detection_parties_scanned{")
    }

    private companion object {
        const val STRANDED_PARTY_ID = "fad8c8db-0000-4000-8000-000000005698"
        const val HANDLED_PARTY_ID = "58fb3ae8-0000-4000-8000-000000005698"
        const val TOTAL = 2L
        val CREATED_AT: Instant = Instant.parse("2026-06-07T10:00:00Z")

        /** Generous vs the 2s cron so a slow CI runner cannot flake the wait. */
        const val SCAN_BUDGET_NANOS = 60_000_000_000L
        const val POLL_INTERVAL_MILLIS = 250L
    }
}
