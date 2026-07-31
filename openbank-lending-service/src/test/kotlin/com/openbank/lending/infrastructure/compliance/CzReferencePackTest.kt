// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.compliance

import com.openbank.libs.lending.compliance.CompliancePackCompiler
import com.openbank.libs.lending.compliance.CompliancePackParser
import com.openbank.libs.lending.compliance.PackProductType
import com.openbank.libs.lending.origination.OriginationState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Guards the CZ reference pack against content rot: it must always parse, compile and
 * carry the real statutory duties of Czech consumer credit (257/2016 Sb. + CCD2).
 * A pack edit that breaks any of these fails CI before a compliance review sees it.
 */
class CzReferencePackTest {

    private val pack by lazy {
        val json = requireNotNull(javaClass.getResource("/compliance-packs/cz-consumer-credit-v1.json")) {
            "cz-consumer-credit-v1.json missing from resources"
        }.readText()
        CompliancePackCompiler.compile(CompliancePackParser.fromJson(json))
    }

    @Test
    fun `pack compiles with CZ consumer credit identity`() {
        assertThat(pack.pack.jurisdiction).isEqualTo("CZ")
        assertThat(pack.pack.productType).isEqualTo(PackProductType.CONSUMER_CREDIT)
        assertThat(pack.contentHash).hasSize(64)
    }

    @Test
    fun `statutory withdrawal window is 14 days and disclosed in Czech as RPSN`() {
        assertThat(pack.pack.coolingOffDays).isEqualTo(14)
        assertThat(pack.pack.aprDisclosure.label).isEqualTo("RPSN")
        assertThat(pack.pack.aprDisclosure.locale).isEqualTo("cs-CZ")
    }

    @Test
    fun `early repayment compensation is capped at one percent`() {
        assertThat(pack.pack.earlyRepaymentCompensationCap).isEqualByComparingTo(BigDecimal("0.01"))
    }

    @Test
    fun `default trigger follows the CNB 90-day election`() {
        assertThat(pack.pack.terminationRules.defaultDpdThreshold).isEqualTo(90)
        assertThat(pack.pack.terminationRules.permittedGrounds)
            .contains(com.openbank.libs.lending.compliance.TerminationGround.DEFAULT_DPD)
    }

    @Test
    fun `affordability floor and majority check are mandatory checks`() {
        val ids = pack.pack.mandatoryChecks.map { it.id }
        assertThat(ids).contains("cz-dsti-cap", "cz-plnoletost")
    }

    @Test
    fun `pre-contractual information requires acknowledgement and Czech language`() {
        val preContractual = pack.pack.disclosures.single { it.id == "predsmluvni-informace" }
        assertThat(preContractual.requiresAcknowledgement).isTrue()
        assertThat(preContractual.languages).contains("cs")
    }

    @Test
    fun `document collection is a mandatory origination step in CZ`() {
        assertThat(pack.pack.requiredSteps).contains(OriginationState.DOCS_REQUIRED)
    }
}
