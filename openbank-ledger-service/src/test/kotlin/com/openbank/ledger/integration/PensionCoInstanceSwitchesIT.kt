// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.ledger.integration

import com.openbank.ledger.it.PostgresTestResource
import io.agroal.api.AgroalDataSource
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.scheduler.Scheduler
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * The pension company's ledger instance (ADR-0337, #12500), proven by EFFECT on a booted service.
 *
 * The profile is the instance's Rollout env, read from the committed manifest
 * (`openbank-infra/gitops/components/ledger-pension-co/ledger-service.yaml`) — not retyped — with
 * the real scheduler switched ON (the `%test` profile disables it fleet-wide, which would make every
 * "did not run" assertion vacuous). The one value substituted is the Flyway filesystem location:
 * the pod mounts the chart-of-accounts ConfigMap at `/flyway/pension-co`; here the SAME ConfigMap's
 * SQL is written to a directory under `build/` and mounted by path, so the callback under test is
 * the one that ships.
 *
 * Asserted:
 *  - the scheduler is running (the instance-local jobs are scheduled) and NO bank-only job — tie-out,
 *    its freshness watchdog, FX revaluation, accounting-day calendar, outbox dispatcher — is;
 *  - none of them left a mark after the scheduler had time to fire;
 *  - none of them registered a workflow-liveness heartbeat (it could only ever go stale);
 *  - the afterMigrate callback ran from the mounted location: 30 PS accounts present and enabled,
 *    every bank account seeded by the shared migrations disabled;
 *  - a second migrate (what the next boot does) re-runs it idempotently: same counts, no error.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(PensionCoInstanceSwitchesIT.PensionCoInstanceProfile::class)
class PensionCoInstanceSwitchesIT {

    class PensionCoInstanceProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> {
            val env = instanceEnv()
            assertThat(env.keys.filter { it in ENV_TO_PROPERTY || it.isSwitchLike() })
                .describedAs(
                    "every scheduler/outbox/messaging/flyway switch in the manifest must be mapped here " +
                        "(a new one would otherwise be silently left out of this proof)",
                )
                .containsExactlyInAnyOrderElementsOf(ENV_TO_PROPERTY.keys)
            val overrides = ENV_TO_PROPERTY.entries.associate { (envName, prop) -> prop to env.getValue(envName) }
                .toMutableMap()
            overrides["quarkus.flyway.locations"] = overrides.getValue("quarkus.flyway.locations")
                .replace(MOUNT_PATH, chartOfAccountsDir().absolutePath)
            overrides["quarkus.scheduler.enabled"] = "true"
            return overrides
        }

        private fun String.isSwitchLike() = startsWith("OPENBANK_") ||
            startsWith("LEDGER_") ||
            startsWith("MP_MESSAGING_") ||
            startsWith("QUARKUS_FLYWAY_")
    }

    @Inject
    lateinit var scheduler: Scheduler

    @Inject
    lateinit var registry: MeterRegistry

    @Inject
    lateinit var dataSource: AgroalDataSource

    @Inject
    lateinit var flyway: Flyway

    private fun scheduledClasses(): List<String> = scheduler.scheduledJobs.map { it.methodDescription ?: it.id }

    @Test
    fun `the real scheduler runs the instance-local jobs and none of the bank-only ones`() {
        assertThat(scheduler.isRunning).isTrue()
        val jobs = scheduledClasses()
        assertThat(jobs).describedAs("positive control: the scheduler is live here").anySatisfy {
            assertThat(it).contains("JournalPartitionMaintainer")
        }
        BANK_ONLY.forEach { cls ->
            assertThat(jobs).describedAs("$cls is switched off on this instance").noneSatisfy {
                assertThat(it).contains(cls)
            }
        }
    }

    @Test
    fun `no bank-only job leaves a mark once the scheduler has had time to fire`() {
        Thread.sleep(SETTLE_MILLIS)
        assertThat(count("SELECT count(*) FROM ledger_tieout_runs")).isZero()
        assertThat(count("SELECT count(*) FROM ledger_accounting_day")).isZero()
    }

    @Test
    fun `no switched-off job registers a liveness heartbeat`() {
        SWITCHABLE_WORKFLOWS.forEach { assertThat(registry.livenessAge(it)).describedAs(it).isNull() }
        assertThat(registry.livenessAge("ledger-journal-partition-maintenance")).isNotNull()
    }

    @Test
    fun `the mounted chart of accounts is applied, and re-applied idempotently on the next migrate`() {
        assertChart()
        flyway.migrate()
        assertChart()
    }

    private fun assertChart() {
        assertThat(count("SELECT count(*) FROM gl_accounts WHERE code LIKE 'PS%' AND is_enabled"))
            .isEqualTo(PS_ACCOUNTS)
        assertThat(count("SELECT count(*) FROM gl_accounts WHERE code LIKE 'PS%'")).isEqualTo(PS_ACCOUNTS)
        assertThat(count("SELECT count(*) FROM gl_accounts WHERE code NOT LIKE 'PS%'"))
            .describedAs("the shared migrations did seed bank accounts — otherwise 'disabled' proves nothing")
            .isPositive()
        assertThat(count("SELECT count(*) FROM gl_accounts WHERE code NOT LIKE 'PS%' AND is_enabled")).isZero()
    }

    private fun count(sql: String): Long = dataSource.connection.use { c ->
        c.createStatement().use { s ->
            s.executeQuery(sql).use {
                it.next()
                it.getLong(1)
            }
        }
    }

    companion object {
        private const val COMPONENT = "../openbank-infra/gitops/components/ledger-pension-co"
        private const val MOUNT_PATH = "/flyway/pension-co"
        private const val PS_ACCOUNTS = 30L
        private const val SETTLE_MILLIS = 8_000L

        /** Rollout env name -> the property it sets (SmallRye env mapping, spelled out). */
        private val ENV_TO_PROPERTY = mapOf(
            "OPENBANK_OUTBOX_DISPATCH_ENABLED" to "openbank.outbox.dispatch-enabled",
            "OPENBANK_OUTBOX_POLL_INTERVAL" to "openbank.outbox.poll-interval",
            "MP_MESSAGING_OUTGOING_LEDGER_EVENTS_OUT_TOPIC" to "mp.messaging.outgoing.ledger-events-out.topic",
            "OPENBANK_LEDGER_TIEOUT_CRON" to "openbank.ledger.tieout.cron",
            "OPENBANK_LEDGER_TIEOUT_FRESHNESS_CRON" to "openbank.ledger.tieout.freshness-cron",
            "OPENBANK_LEDGER_FX_REVALUATION_CRON" to "openbank.ledger.fx-revaluation.cron",
            "OPENBANK_LEDGER_ACCOUNTING_DAY_CRON" to "openbank.ledger.accounting-day.cron",
            // application.yaml reads it as the expression ${LEDGER_DAY_LOCK_MODE:shadow}.
            "LEDGER_DAY_LOCK_MODE" to "LEDGER_DAY_LOCK_MODE",
            "QUARKUS_FLYWAY_LOCATIONS" to "quarkus.flyway.locations",
        )

        private val BANK_ONLY = listOf(
            "TieOutScheduler",
            "TieOutFreshnessWatchdog",
            "FxRevaluationScheduler",
            "AccountingDayScheduler",
            "LedgerOutboxDispatcher",
        )

        @Suppress("UNCHECKED_CAST")
        private fun instanceEnv(): Map<String, String> {
            val docs = File("$COMPONENT/ledger-service.yaml").reader().use { r -> Yaml().loadAll(r).toList() }
            val rollout = docs.filterIsInstance<Map<String, Any?>>().single { it["kind"] == "Rollout" }
            val containers = ((rollout["spec"] as Map<String, Any?>)["template"] as Map<String, Any?>)
                .let { (it["spec"] as Map<String, Any?>)["containers"] as List<Map<String, Any?>> }
            val env = containers.single { it["name"] == "ledger-service" }["env"] as List<Map<String, Any?>>
            return env.filter { it["value"] != null }.associate { it["name"] as String to it["value"].toString() }
        }

        /** Writes the ConfigMap's callback SQL where Flyway's filesystem location will find it. */
        @Suppress("UNCHECKED_CAST")
        private fun chartOfAccountsDir(): File {
            val cm = File("$COMPONENT/chart-of-accounts.yaml").reader().use { r -> Yaml().loadAll(r).toList() }
                .filterIsInstance<Map<String, Any?>>().single { it["kind"] == "ConfigMap" }
            val data = cm["data"] as Map<String, String>
            val dir = File("build/pension-co-flyway").apply {
                deleteRecursively()
                mkdirs()
            }
            data.forEach { (name, sql) -> File(dir, name).writeText(sql) }
            return dir
        }
    }
}
