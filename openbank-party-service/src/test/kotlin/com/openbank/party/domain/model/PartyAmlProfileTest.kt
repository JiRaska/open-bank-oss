// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * Each validation rule is proven in isolation: the baseline declaration is valid, and every case
 * breaks exactly one rule — so a rule deleted from [AmlProfileDeclaration.validate] turns exactly
 * its own case red instead of hiding behind a sibling rule.
 */
class PartyAmlProfileTest {

    companion object {
        fun valid() = AmlProfileDeclaration(
            purposes = setOf(AccountPurpose.EVERYDAY_BANKING),
            purposeNote = null,
            incomeSources = setOf(IncomeSource.EMPLOYMENT),
            incomeNote = null,
            occupation = Occupation.EMPLOYED,
            occupationNote = null,
            expectedMonthlyTurnover = ExpectedMonthlyTurnover.UP_TO_30K,
            cashIntensive = false,
            pep = PepDeclaration(isPep = false, category = null, detail = null),
            taxResidencies = listOf(TaxResidency("CZ", null)),
            usPerson = false,
            truthful = true,
        )

        @JvmStatic
        fun violations(): List<Array<Any>> = listOf(
            arrayOf("purpose must name", valid().copy(purposes = emptySet())),
            arrayOf("purposeNote is required", valid().copy(purposes = setOf(AccountPurpose.OTHER))),
            arrayOf("incomeSources must name", valid().copy(incomeSources = emptySet())),
            arrayOf(
                "incomeNote is required",
                valid().copy(incomeSources = setOf(IncomeSource.OTHER), incomeNote = " "),
            ),
            arrayOf("occupationNote is required", valid().copy(occupation = Occupation.OTHER)),
            arrayOf("pep.category is required", valid().copy(pep = PepDeclaration(true, null, null))),
            arrayOf(
                "must be empty when pep.isPep is false",
                valid().copy(pep = PepDeclaration(false, PepCategory.MP, null)),
            ),
            arrayOf("must be empty when pep.isPep is false", valid().copy(pep = PepDeclaration(false, null, "mayor"))),
            arrayOf("taxResidencies must name", valid().copy(taxResidencies = emptyList())),
            arrayOf("not an ISO 3166-1", valid().copy(taxResidencies = listOf(TaxResidency("XX", null)))),
            arrayOf("not an ISO 3166-1", valid().copy(taxResidencies = listOf(TaxResidency("cz", null)))),
            arrayOf(
                "tin is only accepted for a non-CZ",
                valid().copy(taxResidencies = listOf(TaxResidency("CZ", "123"))),
            ),
            arrayOf("exceeds 64", valid().copy(taxResidencies = listOf(TaxResidency("DE", "9".repeat(65))))),
            arrayOf(
                "must not repeat",
                valid().copy(taxResidencies = listOf(TaxResidency("CZ", null), TaxResidency("CZ", null))),
            ),
            arrayOf("usPerson must be true", valid().copy(taxResidencies = listOf(TaxResidency("US", null)))),
            arrayOf("purposeNote exceeds", valid().copy(purposeNote = "x".repeat(501))),
            arrayOf("pep.detail exceeds", valid().copy(pep = PepDeclaration(true, PepCategory.MP, "x".repeat(501)))),
            arrayOf("truthful must be true", valid().copy(truthful = false)),
        )
    }

    @Test
    fun `the baseline declaration is valid, so every violation below is caused by its one change`() {
        assertThatCode { valid().validate() }.doesNotThrowAnyException()
        assertThatCode {
            valid().copy(
                purposes = setOf(AccountPurpose.OTHER),
                purposeNote = "crypto",
                occupation = Occupation.OTHER,
                occupationNote = "artist",
                pep = PepDeclaration(true, PepCategory.FAMILY_MEMBER, "spouse of an MP"),
                taxResidencies = listOf(
                    TaxResidency("CZ", null),
                    TaxResidency("US", "123-45"),
                    TaxResidency("DE", null),
                ),
                usPerson = true,
            ).validate()
        }.doesNotThrowAnyException()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("violations")
    fun `each rule rejects its own violation as a 400-mapped IllegalArgumentException`(
        expected: String,
        declaration: AmlProfileDeclaration,
    ) {
        assertThatThrownBy { declaration.validate() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .isInstanceOf(InvalidAmlProfileException::class.java)
            .hasMessageContaining(expected)
    }

    @Test
    fun `a CZ-only person is NON_REPORTABLE and NON_US with no PEP facts`() {
        val facts = AmlProfiles.derive(valid())
        assertThat(facts).isEqualTo(AmlDerivedFacts(false, null, FatcaStatus.NON_US, CrsStatus.NON_REPORTABLE))
    }

    @Test
    fun `any residency outside CZ makes the person CRS REPORTABLE, even alongside CZ`() {
        val foreignOnly = valid().copy(taxResidencies = listOf(TaxResidency("SK", null)))
        val dual = valid().copy(taxResidencies = listOf(TaxResidency("CZ", null), TaxResidency("DE", "DE123")))

        assertThat(AmlProfiles.derive(foreignOnly).crsStatus).isEqualTo(CrsStatus.REPORTABLE)
        assertThat(AmlProfiles.derive(dual).crsStatus).isEqualTo(CrsStatus.REPORTABLE)
    }

    @Test
    fun `usPerson maps to FATCA US_PERSON and a PEP declaration carries its category`() {
        val facts = AmlProfiles.derive(
            valid().copy(usPerson = true, pep = PepDeclaration(true, PepCategory.JUDGE, null)),
        )
        assertThat(facts.fatcaStatus).isEqualTo(FatcaStatus.US_PERSON)
        assertThat(facts.pepFlag).isTrue()
        assertThat(facts.pepCategory).isEqualTo(PepCategory.JUDGE)
    }

    @Test
    fun `each risk fact routes to EDD on its own and a plain declaration does not`() {
        val highRisk = setOf("IR")
        assertThat(AmlProfiles.riskFactors(valid(), highRisk)).isEmpty()
        assertThat(AmlProfiles.riskFactors(valid().copy(pep = PepDeclaration(true, PepCategory.MP, null)), highRisk))
            .containsExactly(AmlRiskFactor.PEP)
        assertThat(AmlProfiles.riskFactors(valid().copy(usPerson = true), highRisk))
            .containsExactly(AmlRiskFactor.US_PERSON)
        assertThat(AmlProfiles.riskFactors(valid().copy(cashIntensive = true), highRisk))
            .containsExactly(AmlRiskFactor.CASH_INTENSIVE)
        assertThat(
            AmlProfiles.riskFactors(
                valid().copy(taxResidencies = listOf(TaxResidency("CZ", null), TaxResidency("IR", null))),
                highRisk,
            ),
        ).containsExactly(AmlRiskFactor.HIGH_RISK_COUNTRY)
        assertThat(
            AmlProfiles.riskFactors(
                valid().copy(expectedMonthlyTurnover = ExpectedMonthlyTurnover.OVER_500K),
                highRisk,
            ),
        ).containsExactly(AmlRiskFactor.HIGH_TURNOVER)
        assertThat(
            AmlProfiles.riskFactors(
                valid().copy(expectedMonthlyTurnover = ExpectedMonthlyTurnover.UP_TO_500K),
                highRisk,
            ),
        ).isEmpty()
    }
}
