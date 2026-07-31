// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.campaign.domain.model.SegmentRule
import com.openbank.campaign.infrastructure.persistence.SegmentRuleSerde
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The round-trip nobody had: reading a stored segment used to throw
 * `Cannot construct instance of SegmentRule (no Creators...)`, because Jackson cannot deserialise a
 * sealed hierarchy without type information. That surfaced only when a real segment row existed —
 * the first `POST /api/v1/campaigns` of the #2749 rollout, which loads the segment.
 */
class SegmentRuleSerdeTest {

    private val mapper = ObjectMapper()

    @Test
    fun `rules survive a write-read round trip`() {
        val rules = listOf(
            SegmentRule.PartyStatusIs("ACTIVE"),
            SegmentRule.TenureAtLeastDays(30),
        )

        val restored = SegmentRuleSerde.read(mapper, SegmentRuleSerde.write(mapper, rules))

        assertEquals(rules, restored)
    }

    @Test
    fun `the persisted shape carries a discriminator`() {
        val json = SegmentRuleSerde.write(mapper, listOf(SegmentRule.PartyStatusIs("ACTIVE")))
        assertTrue(json.contains("\"type\":\"PartyStatusIs\""), "actual: $json")
        assertTrue(json.contains("\"status\":\"ACTIVE\""), "actual: $json")
    }

    @Test
    fun `an unknown rule type fails loudly rather than yielding a half-built rule`() {
        val e = assertThrows<IllegalArgumentException> {
            SegmentRuleSerde.read(mapper, """[{"type":"WasDeletedInV3","status":"ACTIVE"}]""")
        }
        assertTrue(e.message!!.contains("WasDeletedInV3"), "actual: ${e.message}")
    }

    @Test
    fun `a rule missing its discriminator is rejected`() {
        val e = assertThrows<IllegalArgumentException> {
            SegmentRuleSerde.read(mapper, """[{"status":"ACTIVE"}]""")
        }
        assertTrue(e.message!!.contains("missing"), "actual: ${e.message}")
    }

    @Test
    fun `a rule missing a required field is rejected`() {
        val e = assertThrows<IllegalArgumentException> {
            SegmentRuleSerde.read(mapper, """[{"type":"PartyStatusIs"}]""")
        }
        assertTrue(e.message!!.contains("status"), "actual: ${e.message}")
    }

    /**
     * Unsupported rules cannot reach the database: `Segment` rejects them at construction, and
     * writing one anyway would store a rule that can never be read back (#2891).
     */
    @Test
    fun `an unsupported rule cannot be persisted`() {
        val e = assertThrows<IllegalArgumentException> {
            SegmentRuleSerde.write(mapper, listOf(SegmentRule.HasAccount))
        }
        assertTrue(e.message!!.contains("HasAccount"), "actual: ${e.message}")
    }

    @Test
    fun `a non-array document is rejected`() {
        assertThrows<IllegalArgumentException> {
            SegmentRuleSerde.read(mapper, """{"type":"PartyStatusIs","status":"ACTIVE"}""")
        }
    }
}
