// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.application

import com.openbank.campaign.application.port.out.SegmentEvaluationPort
import com.openbank.campaign.application.usecase.SegmentQuery
import com.openbank.campaign.domain.model.Segment
import com.openbank.campaign.domain.model.SegmentCatalog
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class SegmentQueryTest {

    private val fixedNow = Instant.parse("2026-08-01T09:00:00Z")
    private val cohort = List(3) { UUID.randomUUID() }

    private var evaluated: Segment? = null
    private val evaluation = object : SegmentEvaluationPort {
        override suspend fun evaluate(segment: Segment): List<UUID> {
            evaluated = segment
            return cohort
        }
    }

    private val query = SegmentQuery(evaluation, Clock.fixed(fixedNow, ZoneOffset.UTC))

    @Test
    fun `the catalogue is what the console lists`() {
        val listed = query.list()

        assertEquals(SegmentCatalog.ALL.size, listed.size)
        assertTrue(listed.any { it.name == "actives" && it.version == 1 })
    }

    @Test
    fun `rules are described in words, not enum-speak`() {
        val actives = query.list().first { it.name == "actives" }

        assertEquals(listOf("party status is ACTIVE"), actives.rules)
    }

    /**
     * ADR-0201 D1: the cohort previewed and the cohort sent to must be the same set or provably a
     * different version. That only holds if the preview runs the SAME evaluation enrolment runs — a
     * preview computed a different way would agree with the send only by luck.
     */
    @Test
    fun `preview evaluates the real segment, not a copy of its rules`(): Unit = runBlocking {
        val preview = query.preview("actives", 1)

        assertNotNull(preview)
        assertEquals(3, preview!!.size)
        // assertSame, not assertEquals: Segment is a data class, so a rebuilt copy carrying the
        // same rules would satisfy structural equality and this test would pass against exactly
        // the "preview computed a different way" it exists to rule out.
        assertSame(SegmentCatalog.find("actives", 1), evaluated)
    }

    /** Without asOf, a cohort size is a number with no claim attached — the layer moves underneath it. */
    @Test
    fun `preview is stamped with the instant it was taken`(): Unit = runBlocking {
        assertEquals(fixedNow, query.preview("actives", 1)!!.asOf)
    }

    @Test
    fun `an unknown segment previews as absent rather than empty`(): Unit = runBlocking {
        // Distinct from a real segment matching nobody: "0 people" and "no such segment" are
        // different answers, and a console that renders both as an empty cohort hides a typo.
        assertNull(query.preview("no-such-segment", 1))
        assertNull(query.preview("actives", 99))
    }
}
