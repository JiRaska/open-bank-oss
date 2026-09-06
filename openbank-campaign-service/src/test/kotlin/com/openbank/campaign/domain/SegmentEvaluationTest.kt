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
     * Case fold, tracked against #4604 (the #4553 follow-up). bronze_events holds `ACCOUNT`/`Account`
     * and `Transaction`/`Consent`-only spellings for the same domains — #4576 stops NEW rows
     * splitting, but every row written before it keeps its original case, forever, until a backfill
     * runs. `PARTY` itself has not been observed mixed-case on the sandbox, but a literal
     * `aggregate_type = 'PARTY'` is one producer rename away from silently matching nothing — the
     * exact failure #4553 measured on a Grafana tile that had read 0 for its whole life. Fold, don't
     * trust the literal.
     */
    @Test
    fun `party status folds aggregate_type case, never trusting the producer's spelling`() {
        val (where, _) = Segment("actives", 1, listOf(SegmentRule.PartyStatusIs("ACTIVE"))).toWhereClause()
        assertTrue(where.contains("upper(aggregate_type) = 'PARTY'"), "actual: $where")
        assertFalse(where.contains("aggregate_type = 'PARTY'"), "unfolded literal reintroduced — actual: $where")
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

    @Test
    fun `tenure folds aggregate_type case in BOTH the outer filter and the bronze subquery`() {
        val (where, _) = Segment("tenured", 1, listOf(SegmentRule.TenureAtLeastDays(30))).toWhereClause()
        // Two occurrences: the outer silver_current_state filter and the bronze_events subquery.
        // A fold on only one leaves the other as the exact bug #4553 measured.
        assertEquals(2, Regex("upper\\(aggregate_type\\) = 'PARTY'").findAll(where).count(), "actual: $where")
        assertFalse(where.contains("aggregate_type = 'PARTY'"), "unfolded literal reintroduced — actual: $where")
    }

    @Test
    fun `HasActiveConsentScope renders a parameterised scope, never interpolated`() {
        val (where, params) = Segment(
            "consented",
            1,
            listOf(SegmentRule.HasActiveConsentScope("MARKETING_COMMS_EMAIL")),
        ).toWhereClause()

        assertEquals("MARKETING_COMMS_EMAIL", params["p0_scope"])
        assertFalse(where.contains("MARKETING_COMMS_EMAIL"), "scope interpolated into SQL — actual: $where")
        assertTrue(where.contains("{p0_scope:String}"), "actual: $where")
    }

    /**
     * The opposite source choice from `HasAccount`, and the reason is the question, not a habit.
     * "Is a consent active" is about CURRENT state, and silver keeps the latest event per
     * aggregate — so a revoked, superseded or expired consent drops out by construction because
     * its latest row is no longer a grant. Reading bronze here would match every consent ever
     * granted, including the ones the customer has since withdrawn.
     */
    @Test
    fun `HasActiveConsentScope reads current state, and only a live grant counts`() {
        val (where, _) = Segment(
            "consented",
            1,
            listOf(SegmentRule.HasActiveConsentScope("MARKETING_COMMS_PUSH")),
        ).toWhereClause()

        assertTrue(where.contains("silver_current_state"), "actual: $where")
        assertFalse(
            where.contains("bronze_events"),
            "reads the log, so a revoked consent still matches — actual: $where",
        )
        assertTrue(where.contains("event_type = 'ConsentGranted'"), "actual: $where")
    }

    @Test
    fun `a mis-shaped consent scope is rejected where the segment is defined`() {
        for (bad in listOf("marketing_comms_email", "MARKETING COMMS", "", "1MARKETING")) {
            assertThrows<IllegalArgumentException>("expected '$bad' to be rejected") {
                SegmentRule.HasActiveConsentScope(bad)
            }
        }
    }

    @Test
    fun `HasAccount is constructible now that the party link exists in the layer`() {
        val segment = Segment("has-account", 1, listOf(SegmentRule.HasAccount))
        val (where, params) = segment.toWhereClause()
        assertTrue(where.contains("aggregate_id IN ("), "renders no membership test — actual: $where")
        // No bind values: the rule carries no caller-supplied value, so there is nothing to
        // parameterise and nothing that could become SQL. The SHAPE of the membership test is
        // pinned by the delegation test below, not here.
        assertTrue(params.isEmpty(), "actual: $params")
    }

    /**
     * The property worth a test of its own: this rule must DELEGATE the account-to-party
     * resolution, not repeat it. `silver_party_accounts` (ADR-0210 D2) is that resolution, and its
     * own header explains the danger of a second copy — the resolution is the isolation boundary
     * of the Customer 360, so a caller that widens its private version shows another customer's
     * accounts. The first version of this rule inlined the same predicate; identical is exactly how
     * two copies begin.
     */
    @Test
    fun `HasAccount delegates to the shared party-accounts view instead of re-deriving it`() {
        val (where, _) = Segment("has-account", 1, listOf(SegmentRule.HasAccount)).toWhereClause()
        assertTrue(where.contains("openbank_analytics.silver_party_accounts"), "actual: $where")
        assertFalse(
            where.contains("JSONExtractString(payload, 'partyId')"),
            "re-derives the resolution the shared view owns — actual: $where",
        )
        assertFalse(where.contains("silver_current_state"), "resolves from the latest-state view — actual: $where")
    }

    /**
     * The load-bearing property of this rule, and the one worth a test of its own.
     *
     * Silver keeps only the LATEST event per aggregate, and of the four account events only
     * `AccountCreatedEvent` carries `partyId`. So an account that has ever changed status has a
     * silver row without the link, and a silver-based subquery would omit exactly the parties with
     * the most account activity — while a fail-closed evaluator renders that as "did not match",
     * which is indistinguishable from a correct answer. Reading bronze is what avoids it.
     */
    /**
     * An ACCOUNT row whose payload has no `partyId` must not contribute an empty string to the
     * IN-list: `aggregate_id IN ('')` matches nothing, but it also costs nothing to exclude, and
     * leaving it in makes the query's intent unreadable. Every account event other than
     * `AccountCreated` produces exactly such a row.
     */
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
