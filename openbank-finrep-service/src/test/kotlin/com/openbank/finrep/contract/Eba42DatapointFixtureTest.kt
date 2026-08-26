// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.finrep.contract

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.finrep.application.port.out.TrialBalanceLineDto
import com.openbank.finrep.application.port.out.TrialBalanceSnapshot
import com.openbank.finrep.domain.mapper.F0101Mapper
import com.openbank.finrep.domain.mapper.F0102Mapper
import com.openbank.finrep.domain.mapper.F0103Mapper
import com.openbank.finrep.domain.mapper.F0200Mapper
import com.openbank.finrep.domain.model.EbaReportingFramework42
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/** Regression lock derived from the pinned official EBA Framework 4.2 annotated templates. */
class Eba42DatapointFixtureTest {
    private val fixture = ObjectMapper().readTree(
        requireNotNull(javaClass.getResourceAsStream("/eba/reporting-framework-4.2-datapoints.json")),
    )

    @Test
    fun `official package identity and supported mapper coordinates stay pinned`() {
        assertThat(fixture["reportingFramework"].asText()).isEqualTo("4.2")
        assertThat(fixture["dpmVersion"].asText()).isEqualTo("4.2.1")
        assertThat(fixture["taxonomyVersion"].asText()).isEqualTo("4.2.0.0")
        assertThat(EbaReportingFramework42.REPORTING_FRAMEWORK_VERSION)
            .isEqualTo(fixture["reportingFramework"].asText())
        assertThat(EbaReportingFramework42.DPM_VERSION).isEqualTo(fixture["dpmVersion"].asText())
        assertThat(EbaReportingFramework42.TAXONOMY_VERSION).isEqualTo(fixture["taxonomyVersion"].asText())
        assertThat(fixture["taxonomyPackageSha256"].asText())
            .isEqualTo("0bf9e33720de1472e809417999cfd29e4b5eadb31750c7770622e502590bbdd0")
        assertThat(fixture["supportedFacts"].map { it["datapointId"]?.takeUnless { id -> id.isNull }?.asInt() })
            .containsExactly(32354, 32592, 32464, null, 57025)
        assertThat(fixture["supportedFacts"].map { it["meaning"].asText() })
            .containsExactly(
                "TOTAL ASSETS",
                "TOTAL LIABILITIES",
                "TOTAL EQUITY",
                "TOTAL EQUITY AND LIABILITIES",
                "PROFIT OR LOSS FOR THE YEAR",
            )

        val snapshot = TrialBalanceSnapshot(
            lines = listOf(
                line("ASSET", "500000"),
                line("LIABILITY", "-300000"),
                line("INCOME", "-260000"),
                line("EXPENSE", "60000"),
            ),
            ledgerReportsBalanced = true,
        )
        val asOf = LocalDate.of(2026, 6, 30)
        val actual = listOf(
            F0101Mapper.map(snapshot, asOf),
            F0102Mapper.map(snapshot, asOf),
            F0103Mapper.map(snapshot, asOf),
            F0200Mapper.map(snapshot, asOf),
        ).flatMap { template -> template.cells.map { Triple(template.templateId, it.rowRef, it.colRef) } }
        val expected = fixture["supportedFacts"].map {
            Triple(it["templateId"].asText(), it["rowRef"].asText(), it["colRef"].asText())
        }

        assertThat(actual).containsExactlyElementsOf(expected)
    }

    private fun line(accountType: String, net: String) = TrialBalanceLineDto(
        code = accountType,
        accountType = accountType,
        net = BigDecimal(net),
        currency = "CZK",
    )
}
