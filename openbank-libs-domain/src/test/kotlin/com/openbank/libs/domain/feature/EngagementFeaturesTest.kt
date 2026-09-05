// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.feature

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Duration
import java.time.Instant

class EngagementFeaturesTest {

    private val party = "11111111-1111-1111-1111-111111111111"
    private val now: Instant = Instant.parse("2026-09-05T12:00:00Z")

    private fun ev(type: String, ago: Duration) = FeatureEvent(party, type, now.minus(ago))

    @Test
    fun `recency is the age of the most recent customer-initiated engagement`() {
        val events = listOf(
            ev("EngagementEvent.CLICK", Duration.ofDays(9)),
            ev("EngagementEvent.CONVERSION", Duration.ofDays(2)),
            ev("EngagementEvent.CLICK", Duration.ofDays(30)),
        )
        assertEquals(2.0, ENGAGEMENT_RECENCY_DAYS.compute(now, events), 0.001)
    }

    /**
     * The load-bearing distinction. An impression is something the BANK did to the customer, so a
     * party who has only ever been shown banners has not engaged — and must not read as engaged
     * today, which is what including impressions would do. A dismissal is a real action whose
     * content is refusal, so counting it would rank a customer who keeps closing banners as one
     * who wants more.
     */
    @Test
    fun `an impression or a dismissal is not engagement`() {
        val passive = listOf(
            ev("EngagementEvent.IMPRESSION", Duration.ofHours(1)),
            ev("EngagementEvent.DISMISS", Duration.ofHours(2)),
        )
        assertEquals(NEVER_ENGAGED_DAYS, ENGAGEMENT_RECENCY_DAYS.compute(now, passive), 0.001)
        assertEquals(0.0, ENGAGEMENT_COUNT_D30.compute(now, passive), 0.001)
    }

    /**
     * A party with no engagement reads as maximally distant, never as 0. A never-seen value that
     * reads as the healthiest possible value is the failure this platform has already paid for
     * with a liveness gauge; the same trap is available here and this is what closes it.
     */
    @Test
    fun `a party who never engaged does not read as engaged today`() {
        assertEquals(NEVER_ENGAGED_DAYS, ENGAGEMENT_RECENCY_DAYS.compute(now, emptyList()), 0.001)
        assertTrue(NEVER_ENGAGED_DAYS > 365, "the sentinel must be unmistakably distant")
    }

    /**
     * Anti-leakage (ADR-0140), the same bound VelocityFeatures uses: an event AT `asOf` is not
     * knowable at `asOf`. Letting it in is how an offline training set learns from the future
     * while the online path cannot, which is exactly the skew the shared definition exists to
     * prevent.
     */
    @Test
    fun `an event at asOf is not visible to either feature`() {
        val atBoundary = listOf(FeatureEvent(party, "EngagementEvent.CLICK", now))
        assertEquals(NEVER_ENGAGED_DAYS, ENGAGEMENT_RECENCY_DAYS.compute(now, atBoundary), 0.001)
        assertEquals(0.0, ENGAGEMENT_COUNT_D30.compute(now, atBoundary), 0.001)
    }

    @Test
    fun `the 30-day count excludes anything older than the window`() {
        val events = listOf(
            ev("EngagementEvent.CLICK", Duration.ofDays(1)),
            ev("EngagementEvent.CONVERSION", Duration.ofDays(29)),
            ev("EngagementEvent.CLICK", Duration.ofDays(31)),
        )
        assertEquals(2.0, ENGAGEMENT_COUNT_D30.compute(now, events), 0.001)
    }

    @Test
    fun `neither feature reads another party's events`() {
        // compute() is handed one entity's events by the store, but a definition that ignored the
        // filter would still pass every test above. This pins that it does not invent cross-entity
        // behaviour of its own.
        val other = FeatureEvent("22222222-2222-2222-2222-222222222222", "EngagementEvent.CLICK", now.minusSeconds(60))
        assertEquals(1.0, ENGAGEMENT_COUNT_D30.compute(now, listOf(other)), 0.001)
    }

    /**
     * The wire vocabulary belongs to engagement-service and this module only quotes it. Rename the
     * enum or the outbox prefix there and both features silently read zero — a party who engages
     * daily reads as never engaged, and nothing errors. No schema registry covers these topics, so
     * this file-level pin is the only thing holding the two together.
     *
     * ON THE ASSUMPTION: a per-service container build copies only its own module, so the sibling
     * source is legitimately absent and this skips. A skip reads as a pass, which this repository
     * is right to distrust; the mitigation is that the full-fleet build always has both.
     */
    @Test
    fun `the engagement wire vocabulary this reads still matches the producer`() {
        val model =
            File("../openbank-engagement-service/src/main/kotlin/com/openbank/engagement/domain/model/Engagement.kt")
        val writer =
            File(
                "../openbank-engagement-service/src/main/kotlin/com/openbank/engagement/infrastructure/persistence/repository/EngagementEventRepositoryImpl.kt",
            )
        org.junit.jupiter.api.Assumptions.assumeTrue(
            model.isFile && writer.isFile,
            "sibling module not in this checkout (per-service build)",
        )

        val enum = model.readText()
        assertTrue(
            enum.contains("enum class EngagementEventType { IMPRESSION, CLICK, DISMISS, CONVERSION }"),
            "the engagement event vocabulary changed — these features may now read nothing",
        )

        assertTrue(
            writer.readText().contains("""eventType = "EngagementEvent.${'$'}{event.type.name}""""),
            "the outbox no longer prefixes the wire event type with 'EngagementEvent.', so these " +
                "features read zero for every party while erroring nowhere",
        )

        for (t in ENGAGEMENT_RECENCY_DAYS.eventTypes) {
            val name = t.removePrefix("EngagementEvent.")
            assertTrue(enum.contains(name), "the features read '$t' but the producer has no $name")
        }
    }
}
