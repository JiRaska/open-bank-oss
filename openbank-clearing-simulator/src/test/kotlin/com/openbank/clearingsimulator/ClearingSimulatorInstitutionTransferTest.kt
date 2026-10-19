// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clearingsimulator

import com.openbank.clearingsimulator.application.ClearingSimulatorService
import com.openbank.clearingsimulator.domain.ClearingRoute
import com.openbank.libs.iso20022.Iso20022ValidationResult
import com.openbank.libs.iso20022.Iso20022Validator
import com.openbank.libs.iso20022.Pacs002Builder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ClearingSimulatorInstitutionTransferTest {
    private val service = ClearingSimulatorService().apply {
        clock = Clock.fixed(Instant.parse("2026-10-19T09:00:00Z"), ZoneOffset.UTC)
    }
    private val pacs002Validator = Iso20022Validator.forSchema(Pacs002Builder.SCHEMA_RESOURCE)

    private fun clear(route: ClearingRoute, xml: String) = service.clearInstitutionTransfer(route, xml).also {
        assertThat(pacs002Validator.validate(it.statusReportXml)).isEqualTo(Iso20022ValidationResult.Valid)
    }

    @Test
    fun `each route settles a well-formed pacs_009 it clears`() {
        for ((route, xml) in listOf(
            ClearingRoute.TARGET2 to InstitutionTransferFixtures.target2(),
            ClearingRoute.CERTIS to InstitutionTransferFixtures.certis(),
            ClearingRoute.SWIFT to InstitutionTransferFixtures.swift(),
        )) {
            val result = clear(route, xml)
            assertThat(result.settled).describedAs("route %s", route).isTrue()
            assertThat(
                result.statusReportXml,
            ).contains("<TxSts>ACSC</TxSts>", "<OrgnlEndToEndId>E2E-FI-0001</OrgnlEndToEndId>")
            assertThat(result.creditNotificationXml).isNull()
        }
    }

    @Test
    fun `a currency the route does not clear is rejected with AM03`() {
        val result = clear(ClearingRoute.TARGET2, InstitutionTransferFixtures.pacs009(currency = "CZK"))
        assertThat(result.settled).isFalse()
        assertThat(result.statusReportXml).contains("<TxSts>RJCT</TxSts>", "<Cd>AM03</Cd>")
    }

    @Test
    fun `a clearing system that does not belong to the route is rejected with AM03`() {
        assertThat(
            clear(ClearingRoute.SWIFT, InstitutionTransferFixtures.target2()).statusReportXml,
        ).contains("<Cd>AM03</Cd>")
        assertThat(clear(ClearingRoute.CERTIS, InstitutionTransferFixtures.swift(currency = "CZK")).statusReportXml)
            .contains("<Cd>AM03</Cd>")
    }

    @Test
    fun `the deterministic amount triggers reject a pacs_009 too`() {
        assertThat(clear(ClearingRoute.TARGET2, InstitutionTransferFixtures.target2("1000.01")).statusReportXml)
            .contains("<TxSts>RJCT</TxSts>", "<Cd>AC04</Cd>")
        assertThat(
            clear(ClearingRoute.SWIFT, InstitutionTransferFixtures.swift("1000.04")).statusReportXml,
        ).contains("<Cd>RR04</Cd>")
    }

    @Test
    fun `a pacs_008 or garbage on the pacs_009 route is rejected with FF01`() {
        for (bad in listOf("<not-xml", InstitutionTransferFixtures.target2().replace("OPENCZPPXXX", "bad bic"))) {
            val result = clear(ClearingRoute.TARGET2, bad)
            assertThat(result.settled).isFalse()
            assertThat(result.statusReportXml).contains("<TxSts>RJCT</TxSts>", "<Cd>FF01</Cd>")
        }
    }

    @Test
    fun `route names resolve case-insensitively and unknown ones do not`() {
        assertThat(ClearingRoute.fromPath("target2")).isEqualTo(ClearingRoute.TARGET2)
        assertThat(ClearingRoute.fromPath("Certis")).isEqualTo(ClearingRoute.CERTIS)
        assertThat(ClearingRoute.fromPath("sepa")).isNull()
    }
}
