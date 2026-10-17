// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.pensionfund.domain

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.pensionfund.infrastructure.catalog.FundRevisionTerms
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

/** ADR-0341: the fee is exactly the pinned revision's value, and anything but a published retirement revision fails. */
class FundPinnedFeeTest {
    private val mapper = ObjectMapper()
    private val id = UUID.fromString("30000000-0000-0000-0000-000000000341")

    private fun revision(
        state: String = "PUBLISHED",
        schema: String = "org.openbank.retirement.pension-savings",
        version: Int = 2,
        fee: String? = "0.80",
        revisionId: UUID = id,
    ): com.fasterxml.jackson.databind.JsonNode {
        val feeJson = fee?.let { """"feeSchedule":{"managementFeeAnnualPercent":"$it","performanceFeePercent":"0"}""" } ?: """"x":1"""
        return mapper.readTree(
            """{"id":"$revisionId","state":"$state","schemaRef":{"id":"$schema","version":$version},""" +
                """"content":{"attributes":{$feeJson}}}""",
        )
    }

    @Test
    fun `fee fraction equals the revision percent divided by 100`() {
        assertThat(FundRevisionTerms.parse(id, revision(fee = "0.80")).managementFeeRate)
            .isEqualByComparingTo(BigDecimal("0.008"))
        assertThat(FundRevisionTerms.parse(id, revision(fee = "1.25")).managementFeeRate)
            .isEqualByComparingTo(BigDecimal("0.0125"))
        assertThat(FundRevisionTerms.parse(id, revision(fee = "0")).managementFeeRate).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    fun `a draft, a foreign schema, an old schema version, a missing fee or another revision fail closed`() {
        listOf(
            revision(state = "DRAFT"),
            revision(schema = "org.openbank.banking.deposit"),
            revision(version = 1),
            revision(fee = null),
            revision(revisionId = UUID.randomUUID()),
        ).forEach { bad ->
            assertThatThrownBy { FundRevisionTerms.parse(id, bad) }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}
