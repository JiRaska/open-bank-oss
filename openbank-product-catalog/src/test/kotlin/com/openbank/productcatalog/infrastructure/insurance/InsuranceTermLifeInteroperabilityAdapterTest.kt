// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.insurance

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.productcatalog.infrastructure.catalog.CatalogJson
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class InsuranceTermLifeInteroperabilityAdapterTest {
    private val mapper = jacksonObjectMapper()
    private val catalogJson = CatalogJson(mapper)
    private val adapter = InsuranceTermLifeInteroperabilityAdapter()

    @Test
    fun `round trips every version two reference-pack concern without changing exact money text`() {
        val attributes = catalogJson.toObject(mapper.readTree(TERM_LIFE_ATTRIBUTES))

        val acord = adapter.toAcordProfile(attributes)
        val tmf = adapter.toTmf620Profile(attributes)

        assertThat(acord)
            .containsEntry("productType", "TERM_LIFE")
            .containsKeys("coverage", "insuredEvents", "exclusions", "limits", "deductibles", "underwritingQuestions")
        assertThat((acord["coverage"] as Map<*, *>)["amount"]).isEqualTo("100000.00")
        assertThat(acord["insuredEvents"] as List<*>).hasSize(1)
        assertThat(tmf["@type"]).isEqualTo("ProductSpecification")
        val characteristicNames = (tmf["productSpecCharacteristic"] as List<*>)
            .map { (it as Map<*, *>)["name"] }
        assertThat(characteristicNames)
            .containsExactlyInAnyOrder(
                "coverage",
                "termYears",
                "premiumModel",
                "perils",
                "exclusions",
                "limits",
                "deductibles",
                "underwritingQuestions",
                "premium",
            )
        assertThat(adapter.fromAcordProfile(acord)).isEqualTo(attributes)
        assertThat(adapter.fromTmf620Profile(tmf)).isEqualTo(attributes)
    }

    @Test
    fun `rejects external fields and duplicate TMF characteristics instead of silently dropping them`() {
        val attributes = catalogJson.toObject(mapper.readTree(TERM_LIFE_ATTRIBUTES))
        val acord = adapter.toAcordProfile(attributes).toMutableMap().apply { put("beneficiary", "unknown") }
        val nestedAcord = adapter.toAcordProfile(attributes).toMutableMap().apply {
            val coverage = (getValue("coverage") as Map<String, Any>).toMutableMap()
            put("coverage", coverage.apply { put("beneficiary", "unknown") })
        }
        val tmf = adapter.toTmf620Profile(attributes).toMutableMap().apply {
            val characteristics = (getValue("productSpecCharacteristic") as List<Map<String, Any>>).toMutableList()
            put("productSpecCharacteristic", characteristics + characteristics.first())
        }

        assertThatThrownBy { adapter.fromAcordProfile(acord) }
            .hasMessageContaining("unsupported field")
        assertThatThrownBy { adapter.fromAcordProfile(nestedAcord) }
            .hasMessageContaining("unsupported field")
        assertThatThrownBy { adapter.fromTmf620Profile(tmf) }
            .hasMessageContaining("duplicated")
    }

    private companion object {
        const val TERM_LIFE_ATTRIBUTES =
            """{"coverage":{"amount":"100000.00","currency":"EUR"},"termYears":20,"premiumModel":"FIXED","premium":{"amount":"12.3400","currency":"EUR","cadence":"MONTHLY"},"perils":[{"code":"DEATH","description":"Death"}],"exclusions":[{"code":"FRAUD","description":"Fraud"}],"limits":[{"kind":"PER_EVENT","amount":"100000.00","currency":"EUR"}],"deductibles":[{"kind":"PER_CLAIM","amount":"0","currency":"EUR"}],"underwritingQuestions":[{"id":"smoker","question":"Do you smoke?","answerType":"BOOLEAN","required":true}]}"""
    }
}
