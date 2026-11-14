// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.it

import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.sanctions.infrastructure.observability.JurisdictionReferenceDataGauge
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/**
 * The FATF statements of 30 October 2026 as V24 ships them, on a database that only ran Flyway,
 * screened through real HTTP (issue #15723, ADR-0032 amendment of 2026-11-14).
 *
 * The legal model under test: a call-for-action country goes to review (never a block — no
 * embargo is declared by a FATF statement), and a grey-listed country is a customer-risk input
 * that raises NO screen finding. KE is on both the grey list and the binding EU list, so it must
 * still go to review, and only because of the EU row; KW is on the grey list alone, so it must
 * screen CLEAR. Either assertion alone is satisfied by a broken implementation.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
class FatfJurisdictionStatementsIT {

    @Inject
    lateinit var dataSource: DataSource

    @Inject
    lateinit var registry: MeterRegistry

    private class Screened(val status: String, val matchedIds: List<String>)

    /** [profile] null = a profile-less v3 screen, which consults every list. */
    private fun screen(profile: String?, country: String): Screened {
        val profileField = profile?.let { ""","profile":"$it"""" } ?: ""
        val response = Given {
            contentType("application/json")
            body(
                """{"idempotencyKey":"${UUID.randomUUID()}","entityType":"INDIVIDUAL",""" +
                    """"name":"$HARMLESS"$profileField,"country":"$country"}""",
            )
        } When {
            post("/api/v3/sanctions/screen")
        } Then {
            statusCode(201)
        } Extract {
            jsonPath()
        }
        return Screened(response.getString("status"), response.getList("matches.matchedId"))
    }

    private fun <T> query(sql: String, read: (java.sql.ResultSet) -> T): List<T> = dataSource.connection.use { c ->
        c.createStatement().use { st ->
            st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(read(rs)) } }
        }
    }

    private fun gauge(name: String, sourceList: String): Double? =
        registry.find(name).tag(JurisdictionReferenceDataGauge.SOURCE_LIST_TAG, sourceList).gauge()?.value()

    // ── Screening outcome per profile ─────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = ["PAYMENT", "CUSTOMER", ""])
    fun `KP - countermeasure jurisdiction goes to review with FATF provenance, never a block`(profile: String) {
        val result = screen(profile.ifEmpty { null }, "KP")
        assertThat(result.status).isEqualTo("POTENTIAL_HIT")
        assertThat(result.matchedIds).containsExactlyInAnyOrder(
            "country:KP:FATF_CALL_FOR_ACTION:HIGH_RISK:v1",
            "country:KP:EU_HIGH_RISK_THIRD_COUNTRIES:HIGH_RISK:v1",
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["PAYMENT", "CUSTOMER", ""])
    fun `MM - enhanced due diligence jurisdiction goes to review`(profile: String) {
        val result = screen(profile.ifEmpty { null }, "MM")
        assertThat(result.status).isEqualTo("POTENTIAL_HIT")
        assertThat(result.matchedIds).contains("country:MM:FATF_CALL_FOR_ACTION:HIGH_RISK:v1")
    }

    @ParameterizedTest
    @ValueSource(strings = ["PAYMENT", "CUSTOMER", ""])
    fun `KE - grey-listed and EU-listed goes to review only because of the EU row`(profile: String) {
        val result = screen(profile.ifEmpty { null }, "KE")
        assertThat(result.status).isEqualTo("POTENTIAL_HIT")
        assertThat(result.matchedIds).containsExactly("country:KE:EU_HIGH_RISK_THIRD_COUNTRIES:HIGH_RISK:v1")
    }

    @ParameterizedTest
    @ValueSource(strings = ["PAYMENT", "CUSTOMER", ""])
    fun `KW - grey-listed only is a risk factor and screens CLEAR`(profile: String) {
        val result = screen(profile.ifEmpty { null }, "KW")
        assertThat(result.status).isEqualTo("CLEAR")
        assertThat(result.matchedIds).isEmpty()
    }

    @ParameterizedTest
    @ValueSource(strings = ["PAYMENT", "CUSTOMER", ""])
    fun `PT - a country on no list screens CLEAR`(profile: String) {
        val result = screen(profile.ifEmpty { null }, "PT")
        assertThat(result.status).isEqualTo("CLEAR")
        assertThat(result.matchedIds).isEmpty()
    }

    // ── What V24 loaded ───────────────────────────────────────────────────────

    @Test
    fun `both FATF lists are loaded with the October 2026 publication date and the right measures`() {
        val loaded = query(
            "SELECT source_list, measure, country_code FROM sanctions_jurisdictions " +
                "WHERE active AND source_list LIKE 'FATF_%' AND published_on = DATE '2026-10-30' ORDER BY 1, 3",
        ) { "${it.getString(1)}/${it.getString(2)}/${it.getString(3)}" }
        assertThat(loaded.filter { it.startsWith("FATF_CALL_FOR_ACTION/") }).containsExactly(
            "FATF_CALL_FOR_ACTION/HIGH_RISK/IR",
            "FATF_CALL_FOR_ACTION/HIGH_RISK/KP",
            "FATF_CALL_FOR_ACTION/HIGH_RISK/MM",
        )
        assertThat(loaded.filter { it.startsWith("FATF_INCREASED_MONITORING/") }.map { it.substringAfterLast('/') })
            .containsExactlyElementsOf(GREY_LIST)
        assertThat(loaded.filter { it.startsWith("FATF_INCREASED_MONITORING/") })
            .allMatch { it.startsWith("FATF_INCREASED_MONITORING/RISK_FACTOR/") }
    }

    @Test
    fun `the four jurisdictions removed by the October 2026 plenary are on neither FATF list`() {
        val present = query(
            "SELECT country_code FROM sanctions_jurisdictions WHERE active AND source_list LIKE 'FATF_%' " +
                "AND country_code IN ('BG', 'CI', 'CD', 'MC')",
        ) { it.getString(1) }
        assertThat(present).isEmpty()
    }

    @Test
    fun `source_ref separates countermeasures from enhanced due diligence and cites the hashed annex`() {
        val refs = query(
            "SELECT country_code, source_ref FROM sanctions_jurisdictions WHERE source_list = 'FATF_CALL_FOR_ACTION'",
        ) { it.getString(1) to it.getString(2) }.toMap()
        assertThat(refs["KP"]).contains(": countermeasures;").contains(CALL_FOR_ACTION_SHA256)
        assertThat(refs["IR"]).contains(": countermeasures;").contains(CALL_FOR_ACTION_SHA256)
        assertThat(refs["MM"]).contains("enhanced due diligence, not countermeasures").contains(CALL_FOR_ACTION_SHA256)
        val greyRefs = query(
            "SELECT source_ref FROM sanctions_jurisdictions WHERE source_list = 'FATF_INCREASED_MONITORING'",
        ) { it.getString(1) }
        assertThat(greyRefs).isNotEmpty.allMatch { it.contains(INCREASED_MONITORING_SHA256) }
    }

    @Test
    fun `the gauges report both lists as loaded, with the true age of the publication`() {
        fun ageOf(date: LocalDate) =
            Duration.between(date.atStartOfDay(ZoneOffset.UTC).toInstant(), Instant.now()).seconds.toDouble()
        assertThat(gauge(JurisdictionReferenceDataGauge.ROWS, "FATF_CALL_FOR_ACTION")).isEqualTo(3.0)
        assertThat(gauge(JurisdictionReferenceDataGauge.ROWS, "FATF_INCREASED_MONITORING"))
            .isEqualTo(GREY_LIST.size.toDouble())
        for (list in listOf("FATF_CALL_FOR_ACTION", "FATF_INCREASED_MONITORING")) {
            assertThat(gauge(JurisdictionReferenceDataGauge.PUBLICATION_AGE_SECONDS, list))
                .isCloseTo(ageOf(PUBLISHED), within(TOLERANCE_SECONDS))
            assertThat(gauge(JurisdictionReferenceDataGauge.VERIFICATION_AGE_SECONDS, list)).isNotNull()
        }
    }

    private companion object {
        const val HARMLESS = "Harmless Nobodyson"
        const val CALL_FOR_ACTION_SHA256 = "9d0f1c1743e6f01384128d6ba7163e780a572061b13e447a75642b65d21404cf"
        const val INCREASED_MONITORING_SHA256 = "0d1c527b8ded0a2b576a92267007c359032ce2915bfc75ab403b7e7cb66a8341"
        val PUBLISHED: LocalDate = LocalDate.of(2026, 10, 30)
        const val TOLERANCE_SECONDS = 120.0

        /** The 18 jurisdictions of the 30 October 2026 statement, sorted by ISO code. */
        val GREY_LIST = listOf(
            "AO", "BA", "BO", "CM", "HT", "IQ", "KE", "KW", "LA",
            "LB", "NP", "PG", "SS", "SY", "VE", "VG", "VN", "YE",
        )
    }
}
