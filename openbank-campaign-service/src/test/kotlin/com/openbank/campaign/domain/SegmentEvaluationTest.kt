// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.domain

import com.openbank.campaign.domain.model.Segment
import com.openbank.campaign.domain.model.SegmentRule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SegmentEvaluationTest {

    @Test
    fun `rules render as parameterised SQL - values never interpolate into the query text`() {
        val segment = Segment(
            "saver-high-balance",
            3,
            listOf(
                SegmentRule.PartyStatusIs("ACTIVE"),
                SegmentRule.TenureAtLeastDays(30),
            ),
        )
        val (where, params) = segment.toWhereClause()

        assertEquals(2, params.size)
        assertEquals("ACTIVE", params["p0_status"])
        assertEquals(30L, params["p1_days"])
        assertTrue(where.contains("{p0_status:String}"))
        assertTrue(where.contains("{p1_days:UInt32}"))
        // The injection test: the rule VALUE must not appear in the query text at all.
        assertFalse(where.contains("'ACTIVE'"), "rule values must travel as bind parameters, never as literals")
    }

    @Test
    fun `segment names are kebab-case`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            Segment("Saver High Balance", 1, listOf(SegmentRule.HasAccount))
        }
    }

    @Test
    fun `a segment requires at least one rule`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            Segment("empty", 1, emptyList())
        }
    }
}
