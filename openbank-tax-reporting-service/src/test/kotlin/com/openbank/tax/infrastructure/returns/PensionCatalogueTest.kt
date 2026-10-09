// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.infrastructure.returns

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.tax.domain.returns.Periodicity
import com.openbank.tax.domain.returns.ReturnScope
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Pins the shipped CZ pension catalogue to the obligations VERIFIED in
 * docs/research/cz-pension-regulatory-reporting.md (vyhl. 425/2012 §3, vyhl. 314/2013 as amended
 * by 217/2018). A change to a code, scope, periodicity or deadline must be a deliberate edit here.
 */
class PensionCatalogueTest {
    private val catalogue = CatalogueParser.parse(ObjectMapper(), "statutory-returns/cz/pension-cnb.v1.json")

    @Test
    fun `golden - every return with its scope, periodicity and deadline`() {
        val actual = catalogue.returns.map {
            "${it.code}|${it.scope}|${it.periodicity}|${it.deadlineDaysAfterPeriodEnd}"
        }
        assertThat(actual).containsExactlyInAnyOrder(
            "PSP10-12-PS|COMPANY|MONTH|20",
            "PSP10-12-FUND|FUND|MONTH|20",
            "PSP20-12-PS|COMPANY|MONTH|20",
            "PSP20-12-FUND|FUND|MONTH|20",
            "PSP30-12|FUND|MONTH|20",
            "PSP34-12-FUND|FUND|MONTH|20",
            "PSP34-12-PS|COMPANY|QUARTER|30",
            "PSP31-04|COMPANY|QUARTER|30",
            "PSP32-04|COMPANY|QUARTER|30",
            "PSP50-04|COMPANY|QUARTER|30",
            "PSP40-01|COMPANY|YEAR|30",
            "PEF12-04-PS|COMPANY|QUARTER|30",
            "PEF12-04-FUND|FUND|QUARTER|30",
            "PEF13-04|FUND|QUARTER|30",
            "PEF14-04|FUND|QUARTER|30",
            "PEF15-01|FUND|YEAR|30",
        )
    }

    @Test
    fun `the wire format is declared unverified, so no renderer may claim it`() {
        assertThat(catalogue.wireFormatVerified).isFalse()
        assertThat(catalogue.id).isEqualTo("cz-pension-cnb")
        assertThat(catalogue.version).isEqualTo(1)
    }

    @Test
    fun `annual participant return falls due on 30 January`() {
        val pef1501 = catalogue.definition("PEF15-01")!!
        assertThat(pef1501.scope).isEqualTo(ReturnScope.FUND)
        assertThat(
            pef1501.dueDate(com.openbank.tax.domain.returns.ReportingPeriod.parse(Periodicity.YEAR, "2025")).toString(),
        )
            .isEqualTo("2026-01-30")
    }
}
