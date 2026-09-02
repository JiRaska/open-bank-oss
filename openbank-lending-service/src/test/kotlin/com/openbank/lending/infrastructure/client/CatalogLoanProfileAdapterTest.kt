// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.lending.infrastructure.client

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.libs.lending.AmortizationMethod
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class CatalogLoanProfileAdapterTest {
    private val offeringId = UUID.fromString("10000000-0000-0000-0000-000000000011")

    @Test
    fun `published loan revision yields exact catalog terms`() {
        val profile = revision().toProfile(offeringId)

        assertThat(profile.currency).isEqualTo("EUR")
        assertThat(profile.nominalAnnualRate).isEqualByComparingTo("0.0699")
        assertThat(profile.method).isEqualTo(AmortizationMethod.ANNUITY)
        assertThat(profile.minPrincipal).isEqualByComparingTo("1000")
        assertThat(profile.maxPrincipal).isEqualByComparingTo("50000")
        assertThat(profile.snapshot.revisionId).isEqualTo(UUID.fromString("20000000-0000-0000-0000-000000000011"))
    }

    @Test
    fun `client supplied numeric encoding is rejected before it reaches money math`() {
        val malformed = revision(rate = "1e3")

        assertThatThrownBy { malformed.toProfile(offeringId) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("canonical decimal")
    }

    private fun revision(rate: String = "0.0699"): CatalogLoanRevisionResponse {
        val attributes = jacksonObjectMapper().readTree(
            """{"currency":"EUR","tenorMonths":60,"amortizationMethod":"ANNUITY","nominalAnnualRate":"$rate","minPrincipalAmount":"1000","maxPrincipalAmount":"50000"}""",
        )
        return CatalogLoanRevisionResponse(
            id = UUID.fromString("20000000-0000-0000-0000-000000000011"),
            offeringId = offeringId,
            schemaRef = CatalogLoanSchemaRefResponse("org.openbank.banking.loan", 2),
            state = "PUBLISHED",
            content = CatalogLoanRevisionContentResponse(attributes),
            contentHash = "a".repeat(64),
        )
    }
}
