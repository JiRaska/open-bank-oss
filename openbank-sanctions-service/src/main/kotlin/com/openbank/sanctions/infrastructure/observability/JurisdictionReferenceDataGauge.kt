// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.infrastructure.observability

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.logging.Log
import io.quarkus.runtime.Startup
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import javax.sql.DataSource

/**
 * Makes the age of the jurisdiction reference data observable (issue #15723).
 *
 * `sanctions_jurisdictions` is not imported from a feed: its rows arrive by reviewed migration,
 * and the lists they are copied from change several times a year. Nothing fails when such data
 * ages — a country added to a published list simply keeps screening CLEAR — so the table carries
 * the publication date and the date it was last compared with its source, and this bean publishes
 * both per `source_list`:
 *
 *  - [ROWS] — active rows. `0` for a list this service expects ([EXPECTED_SOURCE_LISTS]) and does
 *    not hold, which is a statement, not an absence: an unpopulated list matches nothing.
 *  - [PUBLICATION_AGE_SECONDS] — age of the newest active publication.
 *  - [VERIFICATION_AGE_SECONDS] — time since the list was last compared with its source. This is
 *    the one the alert reads: an old publication is normal when the publisher has issued nothing
 *    newer, an old verification means nobody has looked.
 *
 * ### The value on a cold pod
 *
 * The ages are computed at scrape time from the dates in the table, never from the pod's start,
 * so a pod that booted a second ago reports the true age (hundreds of days, if that is what it
 * is). That is deliberate and the opposite of a heartbeat gauge seeded at registration: a restart
 * must not make stale reference data look fresh. The table is read once, at startup — it changes
 * only by migration, and a migration is a deploy, which starts a new pod.
 *
 * `@Startup`, because an `@ApplicationScoped` bean is created lazily and nothing else would ever
 * touch this one. The read is plain JDBC on the startup thread, after Flyway has migrated.
 */
@Startup
@ApplicationScoped
class JurisdictionReferenceDataGauge(
    private val dataSource: DataSource,
    private val registry: MeterRegistry,
    private val clock: Clock,
) {

    private class Publication(val rows: Long, val publishedOn: LocalDate, val verifiedOn: LocalDate)

    /** Null until the table was read: the row count is then unknown (NaN), never zero. */
    @Volatile
    private var publications: Map<String, Publication>? = null

    // TooGenericExceptionCaught: a failed read must not stop the service from screening names; it
    // leaves the row gauges at NaN, which JurisdictionReferenceDataUnknown alerts on.
    @Suppress("TooGenericExceptionCaught")
    @PostConstruct
    fun register() {
        val loaded = try {
            load()
        } catch (e: Exception) {
            Log.error("Jurisdiction reference data could not be read; its row and age gauges are unknown", e)
            null
        }
        publications = loaded
        for (sourceList in EXPECTED_SOURCE_LISTS + loaded.orEmpty().keys) {
            Gauge.builder(ROWS) { publications?.let { (it[sourceList]?.rows ?: 0L).toDouble() } ?: Double.NaN }
                .tag(SOURCE_LIST_TAG, sourceList)
                .strongReference(true)
                .register(registry)
        }
        loaded.orEmpty().forEach { (sourceList, publication) ->
            ageGauge(PUBLICATION_AGE_SECONDS, sourceList, publication.publishedOn)
            ageGauge(VERIFICATION_AGE_SECONDS, sourceList, publication.verifiedOn)
        }
    }

    private fun ageGauge(name: String, sourceList: String, since: LocalDate) {
        val start = since.atStartOfDay(ZoneOffset.UTC).toInstant()
        Gauge.builder(name) { Duration.between(start, clock.instant()).seconds.toDouble() }
            .tag(SOURCE_LIST_TAG, sourceList)
            .strongReference(true)
            .register(registry)
    }

    private fun load(): Map<String, Publication> = dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(ACTIVE_PUBLICATIONS_SQL).use { rows ->
                buildMap {
                    while (rows.next()) {
                        put(
                            rows.getString("source_list"),
                            Publication(
                                rows = rows.getLong("active_rows"),
                                publishedOn = rows.getObject("published_on", LocalDate::class.java),
                                verifiedOn = rows.getObject("verified_on", LocalDate::class.java),
                            ),
                        )
                    }
                }
            }
        }
    }

    companion object {
        /**
         * Meter names (Micrometer, dotted). Prometheus scrapes them as
         * `openbank_sanctions_jurisdiction_rows`, `…_publication_age_seconds` and
         * `…_verification_age_seconds` — the series
         * `openbank-infra/gitops/components/observability/prometheus-rules-sanctions-jurisdictions.yaml`
         * alerts on. `JurisdictionReferenceDataIT` checks the scraped names, so the two ends
         * cannot drift apart unnoticed.
         */
        const val ROWS: String = "openbank.sanctions.jurisdiction.rows"
        const val PUBLICATION_AGE_SECONDS: String = "openbank.sanctions.jurisdiction.publication.age_seconds"
        const val VERIFICATION_AGE_SECONDS: String = "openbank.sanctions.jurisdiction.verification.age_seconds"
        const val SOURCE_LIST_TAG: String = "source_list"

        /**
         * The publications this service is meant to hold. One that has no rows still gets a
         * [ROWS] series reading 0, so "not loaded" is reported instead of being indistinguishable
         * from "not instrumented".
         */
        val EXPECTED_SOURCE_LISTS: List<String> = listOf(
            "EU_HIGH_RISK_THIRD_COUNTRIES",
            "FATF_CALL_FOR_ACTION",
            "FATF_INCREASED_MONITORING",
        )

        private const val ACTIVE_PUBLICATIONS_SQL =
            "SELECT source_list, COUNT(*) AS active_rows, MAX(published_on) AS published_on, " +
                "MAX(verified_on) AS verified_on FROM sanctions_jurisdictions WHERE active GROUP BY source_list"
    }
}
