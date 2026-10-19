// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clearingsimulator

import com.openbank.clearingsimulator.application.ClearingSimulatorService
import com.openbank.clearingsimulator.application.InstitutionTransferRegistry
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

    @Test
    fun `the same UETR twice returns the same pacs_002 and settles exactly once`() {
        val uetr = "1b4e28ba-2fa1-4d2e-883f-0016d3cca427"
        val xml = InstitutionTransferFixtures.pacs009(uetr = uetr)
        val first = service.clearInstitutionTransfer(ClearingRoute.TARGET2, xml, Instant.parse("2026-10-19T09:00:00Z"))
        val second = service.clearInstitutionTransfer(ClearingRoute.TARGET2, xml, Instant.parse("2026-10-19T09:05:00Z"))
        assertThat(second).isEqualTo(first)
        assertThat(service.institutionTransfers.settledCount()).isEqualTo(1)
        assertThat(service.institutionTransfers.statusOf(uetr)).isEqualTo(first)
        assertThat(service.institutionTransfers.statusOf("00000000-0000-4000-8000-000000000000")).isNull()
    }

    @Test
    fun `different UETRs settle separately and a message without one is not deduplicated`() {
        service.clearInstitutionTransfer(
            ClearingRoute.TARGET2,
            InstitutionTransferFixtures.pacs009(uetr = "2b4e28ba-2fa1-4d2e-883f-0016d3cca427"),
        )
        service.clearInstitutionTransfer(
            ClearingRoute.TARGET2,
            InstitutionTransferFixtures.pacs009(uetr = "3b4e28ba-2fa1-4d2e-883f-0016d3cca427"),
        )
        service.clearInstitutionTransfer(ClearingRoute.TARGET2, InstitutionTransferFixtures.pacs009())
        assertThat(service.institutionTransfers.settledCount()).isEqualTo(2)
    }

    @Test
    fun `the registry evicts the oldest entry beyond its capacity`() {
        val registry = InstitutionTransferRegistry(capacity = 1)
        val r = com.openbank.clearingsimulator.application.ClearingResult(
            "x",
            settled = true,
            creditNotificationXml = null,
        )
        registry.recordOnce("a", Instant.EPOCH) { InstitutionTransferRegistry.Recorded(r) }
        registry.recordOnce("b", Instant.EPOCH) { InstitutionTransferRegistry.Recorded(r) }
        assertThat(registry.statusOf("a")).isNull()
        assertThat(registry.statusOf("b")).isEqualTo(r)
    }

    @Test
    fun `remainder 03 is answered ACSP and settles asynchronously once, after the lag`() {
        val uetr = "6d4e28ba-2fa1-4d2e-883f-0016d3cca427"
        val t0 = Instant.parse("2026-10-19T09:00:00Z")
        val first = service.clearInstitutionTransfer(
            ClearingRoute.TARGET2,
            InstitutionTransferFixtures.pacs009(amount = "500.03", uetr = uetr),
            t0,
        )
        assertThat(first.statusReportXml).contains("<TxSts>ACSP</TxSts>")
        assertThat(first.settled).isFalse()
        val registry = service.institutionTransfers
        assertThat(registry.statusOf(uetr, t0.plusSeconds(29))!!.statusReportXml).contains("<TxSts>ACSP</TxSts>")
        assertThat(registry.settledCount()).isZero()
        val settled = registry.statusOf(uetr, t0.plusSeconds(30))!!
        assertThat(settled.statusReportXml).contains("<TxSts>ACSC</TxSts>", "<OrgnlUETR>$uetr</OrgnlUETR>")
        assertThat(pacs002Validator.validate(settled.statusReportXml)).isEqualTo(Iso20022ValidationResult.Valid)
        registry.statusOf(uetr, t0.plusSeconds(90))
        // A resubmission of the same UETR after settlement returns the settled answer, not a second settlement.
        val again = service.clearInstitutionTransfer(
            ClearingRoute.TARGET2,
            InstitutionTransferFixtures.pacs009(amount = "500.03", uetr = uetr),
            t0.plusSeconds(120),
        )
        assertThat(again.statusReportXml).contains("<TxSts>ACSC</TxSts>")
        assertThat(registry.settledCount()).isEqualTo(1)
    }

    @Test
    fun `remainder 03 does not defer a pacs_008 or a route reject`() {
        assertThat(
            clear(
                ClearingRoute.CERTIS,
                InstitutionTransferFixtures.pacs009(amount = "500.03", currency = "EUR"),
            ).statusReportXml,
        )
            .contains("<Cd>AM03</Cd>")
    }
}
