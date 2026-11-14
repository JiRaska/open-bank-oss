// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.it

import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.sanctions.infrastructure.observability.JurisdictionReferenceDataGauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.composite.CompositeMeterRegistry
import io.micrometer.prometheus.PrometheusMeterRegistry
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/**
 * The jurisdiction reference data as it SHIPS — the rows the migrations load, not a fixture —
 * through real HTTP and Postgres (issue #15723).
 *
 * Until V22 the table was empty, so the `country` / `bankIdentifier` inputs could match nothing
 * and every test of them had to insert its own synthetic row. These tests insert nothing: a
 * listed country must match, and an unlisted one must not, on a database that only ran Flyway.
 *
 * The second half is the part that keeps the data honest over time. Reference data copied from a
 * published list ages silently, so its publication and verification dates are data and their age
 * is a gauge. The gauge is asserted against the dates in the table on a freshly booted
 * application: at t=0 it must already read the true age, not zero and not the pod's uptime.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
class JurisdictionReferenceDataIT {

    @Inject
    lateinit var dataSource: DataSource

    @Inject
    lateinit var registry: MeterRegistry

    private fun screen(extra: String, major: Int = 3) = Given {
        contentType("application/json")
        body("""{"idempotencyKey":"${UUID.randomUUID()}","entityType":"INDIVIDUAL","name":"$HARMLESS"$extra}""")
    } When {
        post("/api/v$major/sanctions/screen")
    }

    private fun <T> query(sql: String, read: (java.sql.ResultSet) -> T): List<T> = dataSource.connection.use { c ->
        c.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                buildList { while (rs.next()) add(read(rs)) }
            }
        }
    }

    private fun gauge(name: String, sourceList: String): Double? =
        registry.find(name).tag(JurisdictionReferenceDataGauge.SOURCE_LIST_TAG, sourceList).gauge()?.value()

    // ── Screening against the shipped rows ────────────────────────────────────

    @Test
    fun `must-hit - a listed country goes to review with a JURISDICTION match naming its source`() {
        screen(""","profile":"PAYMENT","country":"$LISTED"""") Then {
            statusCode(201)
            body("status", equalTo("POTENTIAL_HIT"))
            body("overallScore", equalTo(0.65f))
            body("matches", hasSize<Any>(1))
            body("matches[0].matchKind", equalTo("JURISDICTION"))
            body("matches[0].matchedId", equalTo("country:$LISTED:$EU_LIST:HIGH_RISK:v1"))
            body("matches[0].programs", hasItem("HIGH_RISK:v1"))
            body("matches[0].programs", hasItem(EU_LIST))
        }
    }

    @Test
    fun `must-clear - an unlisted country does not match`() {
        screen(""","profile":"PAYMENT","country":"$UNLISTED","bankIdentifier":"TEST${UNLISTED}PP"""") Then {
            statusCode(201)
            body("status", equalTo("CLEAR"))
            body("matches", hasSize<Any>(0))
        }
    }

    @Test
    fun `must-hit - a BIC whose country part is listed matches without any country field`() {
        screen(""","profile":"PAYMENT","bankIdentifier":"TEST${LISTED}NAXXX"""") Then {
            statusCode(201)
            body("status", equalTo("POTENTIAL_HIT"))
            body("matches[0].matchKind", equalTo("JURISDICTION"))
            body("matches[0].matchedId", equalTo("country:$LISTED:$EU_LIST:HIGH_RISK:v1"))
        }
    }

    @Test
    fun `the CUSTOMER profile consults the same rows`() {
        screen(""","profile":"CUSTOMER","country":"$LISTED"""") Then {
            statusCode(201)
            body("status", equalTo("POTENTIAL_HIT"))
            body("pepStatus", equalTo("CLEAR"))
        }
    }

    // ── What shipped, and where it came from ──────────────────────────────────

    @Test
    fun `every shipped row is a cited row of a known publication, and nothing is an embargo`() {
        val shipped = query(
            "SELECT source_list, measure, list_type, COUNT(*) AS n FROM sanctions_jurisdictions " +
                "WHERE active AND source_list <> 'SYNTHETIC_TEST' GROUP BY source_list, measure, list_type",
        ) { "${it.getString(1)}/${it.getString(2)}/${it.getString(3)}=${it.getLong(4)}" }
        // The FATF rows (V24) are asserted in detail by FatfJurisdictionStatementsIT.
        assertThat(shipped).containsExactlyInAnyOrder(
            "$EU_LIST/HIGH_RISK/FATF_HIGH_RISK=$EU_ROWS",
            "FATF_CALL_FOR_ACTION/HIGH_RISK/FATF_HIGH_RISK=3",
            "FATF_INCREASED_MONITORING/RISK_FACTOR/FATF_HIGH_RISK=18",
        )

        val uncited = query(
            "SELECT country_code FROM sanctions_jurisdictions WHERE source_list = '$EU_LIST' " +
                "AND (source_ref NOT LIKE '%2016/1675%' OR source_ref NOT LIKE '%' || published_on::text || '%')",
        ) { it.getString(1) }
        assertThat(uncited)
            .describedAs("each row names the document and the publication date it was taken from")
            .isEmpty()
    }

    // ── Staleness is observable, and correct on a cold pod ────────────────────

    @Test
    fun `the age gauges read the true age of the stored dates, not the uptime of the pod`() {
        val (publishedOn, verifiedOn) = query(
            "SELECT MAX(published_on), MAX(verified_on) FROM sanctions_jurisdictions " +
                "WHERE active AND source_list = '$EU_LIST'",
        ) { it.getObject(1, LocalDate::class.java) to it.getObject(2, LocalDate::class.java) }.single()

        fun ageOf(date: LocalDate) =
            Duration.between(date.atStartOfDay(ZoneOffset.UTC).toInstant(), Instant.now()).seconds.toDouble()

        assertThat(gauge(JurisdictionReferenceDataGauge.PUBLICATION_AGE_SECONDS, EU_LIST))
            .describedAs("age of the newest active publication, derived from published_on")
            .isCloseTo(ageOf(publishedOn), within(TOLERANCE_SECONDS))
        assertThat(gauge(JurisdictionReferenceDataGauge.VERIFICATION_AGE_SECONDS, EU_LIST))
            .isCloseTo(ageOf(verifiedOn), within(TOLERANCE_SECONDS))
        // A boot-seeded or zero-seeded gauge would read seconds here. The publication is months old.
        assertThat(gauge(JurisdictionReferenceDataGauge.PUBLICATION_AGE_SECONDS, EU_LIST))
            .isGreaterThan(ONE_DAY_SECONDS)
        assertThat(gauge(JurisdictionReferenceDataGauge.ROWS, EU_LIST)).isEqualTo(EU_ROWS.toDouble())
    }

    @Test
    fun `every expected list is loaded, so no expected list reports zero rows`() {
        for (expected in JurisdictionReferenceDataGauge.EXPECTED_SOURCE_LISTS) {
            assertThat(gauge(JurisdictionReferenceDataGauge.ROWS, expected))
                .describedAs("%s is expected and shipped by a migration", expected)
                .isGreaterThan(0.0)
            assertThat(gauge(JurisdictionReferenceDataGauge.VERIFICATION_AGE_SECONDS, expected)).isNotNull()
        }
    }

    @Test
    fun `the gauges are exposed under the names the alert rules query`() {
        val prometheus = when (val r = registry) {
            is PrometheusMeterRegistry -> r
            is CompositeMeterRegistry -> r.registries.filterIsInstance<PrometheusMeterRegistry>().single()
            else -> error("no PrometheusMeterRegistry available, cannot verify exposed metric names")
        }
        val scrape = prometheus.scrape()
        assertThat(scrape).contains("openbank_sanctions_jurisdiction_rows{")
        assertThat(scrape).contains("openbank_sanctions_jurisdiction_publication_age_seconds{")
        assertThat(scrape).contains("openbank_sanctions_jurisdiction_verification_age_seconds{")
        assertThat(scrape).contains("source_list=\"$EU_LIST\"")
    }

    private companion object {
        const val HARMLESS = "Harmless Nobodyson"
        const val EU_LIST = "EU_HIGH_RISK_THIRD_COUNTRIES"
        const val EU_ROWS = 26L

        // KE is listed in the Annex loaded by V22; PT is an EU member state and cannot be a
        // high-risk THIRD country. If a later migration retires KE, this constant moves with it.
        const val LISTED = "KE"
        const val UNLISTED = "PT"
        const val TOLERANCE_SECONDS = 120.0
        const val ONE_DAY_SECONDS = 86_400.0
    }
}
