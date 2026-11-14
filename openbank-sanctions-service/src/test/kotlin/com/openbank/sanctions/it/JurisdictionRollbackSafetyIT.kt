// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.it

import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.sanctions.domain.model.SanctionsListType
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import javax.sql.DataSource

/**
 * Rollback safety of V24: the release BEFORE it must run unchanged against the migrated schema
 * and data, with no manual step.
 *
 * That release reads `sanctions_jurisdictions` only through `SanctionsJurisdictionRepositoryImpl`
 * (unchanged since V21) and converts `measure` with `JurisdictionMeasure.valueOf` over an enum of
 * EMBARGO and HIGH_RISK only. Both are reproduced here literally — the SQL text, the list-type
 * filter and the conversion are copied from origin/main as of f5acbef2f — over JDBC, so the test
 * does not depend on the current release's enum at all. A RISK_FACTOR row that this read path
 * selects throws (the whole screen fails); V24 therefore stores the grey list under a list_type no
 * screen consults, and these tests prove the old path never selects it.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class JurisdictionRollbackSafetyIT {

    @Inject
    lateinit var dataSource: DataSource

    /** origin/main's `JurisdictionMeasure`, verbatim. */
    private enum class MainJurisdictionMeasure { EMBARGO, HIGH_RISK }

    /**
     * origin/main's `SanctionsJurisdictionRepositoryImpl.findActive` SQL and row conversion, then
     * its `ScreeningProfileEvaluator` scoring (EMBARGO 1.0, HIGH_RISK 0.65) and `statusOf`.
     */
    private fun mainScreen(country: String, lists: List<SanctionsListType>): Pair<String, Int> {
        val sql = """
            SELECT list_type, source_list, country_code, measure, version
            FROM sanctions_jurisdictions
            WHERE active = true
              AND country_code = ANY(?)
              AND list_type = ANY(?)
            ORDER BY list_type, country_code, source_list
        """.trimIndent()
        val scores = dataSource.connection.use { c ->
            c.prepareStatement(sql).use { st ->
                st.setArray(1, c.createArrayOf("varchar", arrayOf(country)))
                st.setArray(2, c.createArrayOf("varchar", lists.map { it.name }.toTypedArray()))
                st.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            SanctionsListType.valueOf(rs.getString("list_type"))
                            add(
                                when (MainJurisdictionMeasure.valueOf(rs.getString("measure"))) {
                                    MainJurisdictionMeasure.EMBARGO -> 1.0
                                    MainJurisdictionMeasure.HIGH_RISK -> 0.65
                                },
                            )
                        }
                    }
                }
            }
        }
        val status = when {
            scores.any { it >= 0.85 } -> "HIT"
            scores.any { it >= 0.65 } -> "POTENTIAL_HIT"
            else -> "CLEAR"
        }
        return status to scores.size
    }

    private val payment = listOf(
        SanctionsListType.OFAC_SDN,
        SanctionsListType.EU_CONSOLIDATED,
        SanctionsListType.UN_CONSOLIDATED,
        SanctionsListType.HM_TREASURY,
        SanctionsListType.CNB_DOMESTIC,
        SanctionsListType.CZ_TERRORISTS,
        SanctionsListType.FATF_HIGH_RISK,
    )

    @Test
    fun `the previous release returns its main-branch outcome on the migrated data and does not throw`() {
        for (lists in listOf(payment, SanctionsListType.entries)) {
            // On main (EU list only): KP, KE review; KW, PT clear.
            assertThat(mainScreen("KP", lists).first).isEqualTo("POTENTIAL_HIT")
            assertThat(mainScreen("KE", lists)).isEqualTo("POTENTIAL_HIT" to 1)
            assertThat(mainScreen("KW", lists)).isEqualTo("CLEAR" to 0)
            assertThat(mainScreen("PT", lists)).isEqualTo("CLEAR" to 0)
        }
    }

    @Test
    fun `evidence - a RISK_FACTOR row the previous release selects makes its whole screen throw`() {
        dataSource.connection.use { c ->
            c.createStatement().use {
                it.execute(
                    "INSERT INTO sanctions_jurisdictions (list_type, source_list, country_code, measure, version, " +
                        "source_ref, published_on, verified_on) VALUES ('FATF_HIGH_RISK', 'SYNTHETIC_TEST', 'XG', " +
                        "'RISK_FACTOR', 1, 'rollback-it fixture', DATE '2026-01-01', DATE '2026-01-01')",
                )
            }
        }
        try {
            assertThatThrownBy { mainScreen("XG", payment) }.isInstanceOf(IllegalArgumentException::class.java)
        } finally {
            dataSource.connection.use { c ->
                c.createStatement().use {
                    it.execute("DELETE FROM sanctions_jurisdictions WHERE source_ref = 'rollback-it fixture'")
                }
            }
        }
    }
}
