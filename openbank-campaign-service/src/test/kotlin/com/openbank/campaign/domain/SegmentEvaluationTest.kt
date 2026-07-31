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
import org.junit.jupiter.api.assertThrows

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

    /**
     * Regression for #2891. The DSL used to read `state`, `first_seen`, `has_account` and
     * `consent_scopes` — none of which exist in silver_current_state, whose columns are the event
     * envelope plus a `payload` JSON.
     *
     * This asserts the query TEXT, which is all the unit layer can do, and that is explicitly not
     * enough: the same limitation is why #2891 shipped. It cannot catch a valid-looking query that
     * ClickHouse refuses — proven while writing this fix, when the rewritten rule still used
     * `jsonExtractString` and ClickHouse answered `Function with name 'jsonExtractString' does not
     * exist` (it is `JSONExtractString`). A test asserting the lowercase spelling would have been
     * green against a query that cannot run. Both rules were therefore executed against the sandbox
     * ClickHouse by hand before merge. An automated executable check does not exist yet — tracked in
     * #2891.
     */
    @Test
    fun `rules only reference columns the silver layer actually has`() {
        val silverColumns = setOf(
            "aggregate_type", "aggregate_id", "event_id", "aggregate_version",
            "event_type", "occurred_at", "source_service", "schema_version", "payload",
        )
        val vanished = setOf("state", "first_seen", "has_account", "consent_scopes")

        val (where, _) = Segment(
            "supported-rules",
            1,
            listOf(SegmentRule.PartyStatusIs("ACTIVE"), SegmentRule.TenureAtLeastDays(0)),
        ).toWhereClause()

        vanished.forEach { column ->
            assertFalse(
                Regex("(?<![a-z_])$column(?![a-z_])").containsMatchIn(where),
                "rule SQL references `$column`, which silver_current_state does not have (#2891)",
            )
        }
        assertTrue(silverColumns.any { where.contains(it) }, "rule SQL must read a real silver column")
    }

    @Test
    fun `party status reads the payload JSON, not a status column`() {
        val (where, _) = Segment("actives", 1, listOf(SegmentRule.PartyStatusIs("ACTIVE"))).toWhereClause()
        assertTrue(where.contains("JSONExtractString(payload, 'status')"), "actual: $where")
    }

    /**
     * silver_current_state keeps only the latest event per aggregate, so tenure has to reach into
     * the bronze log for the party's first-seen timestamp.
     */
    @Test
    fun `tenure derives first-seen from bronze, which silver cannot answer`() {
        val (where, _) = Segment("tenured", 1, listOf(SegmentRule.TenureAtLeastDays(30))).toWhereClause()
        assertTrue(where.contains("bronze_events"), "actual: $where")
        assertTrue(where.contains("min(occurred_at)"), "actual: $where")
    }

    /**
     * The two rules whose data does not exist anywhere in analytics are rejected where the segment is
     * DEFINED. Rendering them into SQL that ClickHouse refuses is what made #2891 look like an empty
     * cohort instead of a broken query.
     */
    @Test
    fun `rules whose data the analytics layer does not carry are rejected at construction`() {
        val noAccountLink = assertThrows<IllegalArgumentException> {
            Segment("has-account", 1, listOf(SegmentRule.HasAccount))
        }
        assertTrue(noAccountLink.message!!.contains("partyId"), "actual: ${noAccountLink.message}")

        val noConsentEvents = assertThrows<IllegalArgumentException> {
            Segment("consented", 1, listOf(SegmentRule.HasActiveConsentScope("MARKETING_COMMS_EMAIL")))
        }
        assertTrue(noConsentEvents.message!!.contains("consent events"), "actual: ${noConsentEvents.message}")
    }

    @Test
    fun `segment names are kebab-case`() {
        assertThrows<IllegalArgumentException> {
            Segment("Saver High Balance", 1, listOf(SegmentRule.PartyStatusIs("ACTIVE")))
        }
    }

    @Test
    fun `a segment requires at least one rule`() {
        assertThrows<IllegalArgumentException> {
            Segment("empty", 1, emptyList())
        }
    }
}
