// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.domain

import com.openbank.campaign.domain.model.Segment
import com.openbank.campaign.domain.model.SegmentRule
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * `SegmentRule.HasActiveConsentScope` depends on three literals that consent-service owns and this
 * module only quotes: the event-type discriminator `ConsentGranted`, the payload key `partyId`, and
 * the payload key `scopes`. Rename any of them there and this rule matches nobody — silently,
 * because the evaluator is fail-closed and an unresolvable party renders as "did not match".
 *
 * For this rule the silence is worse than for `HasAccount`. An empty cohort here reads as
 * "nobody has consented", which is a plausible and unalarming answer, so it can sit for months. The
 * same coupling exists for the scope VOCABULARY, which is why a caller passing a name
 * `ConsentScope` does not contain gets an empty cohort rather than an error — the rule can check
 * the shape of a scope but not its existence, and that limit is stated on the rule itself.
 *
 * ON THE ASSUMPTION, stated rather than hidden: a per-service container build copies only its own
 * module, so the sibling source is legitimately absent there and this test skips. This repository
 * is right to distrust a skip — a skipped test reads as a pass — and the mitigation is that the
 * full-fleet build, where this coupling can actually break, always has both modules.
 */
class HasActiveConsentScopeProducerPinTest {

    private val events =
        File("../openbank-consent-service/src/main/kotlin/com/openbank/consent/domain/event/ConsentEvents.kt")
    private val model = File("../openbank-consent-service/src/main/kotlin/com/openbank/consent/domain/model/Consent.kt")

    @Test
    fun `the grant event still declares the discriminator and payload keys this rule reads`() {
        assumeTrue(events.isFile, "sibling module not in this checkout (per-service build)")
        val source = events.readText()
        val granted = source.substringAfter("data class ConsentGranted(").substringBefore(") : DomainEvent")

        assertTrue(
            source.contains("""override val eventType = "ConsentGranted""""),
            "consent-service no longer emits the event type this rule filters on, so the rule now " +
                "matches nobody — and it would do so silently.",
        )
        assertTrue(granted.contains("val partyId:"), "ConsentGranted no longer carries partyId")
        assertTrue(granted.contains("val scopes:"), "ConsentGranted no longer carries scopes")
    }

    /**
     * The vocabulary half. A scope this module names in a segment must exist in consent-service's
     * enum, or the segment silently enrols nobody. Only the marketing scopes are asserted, because
     * they are the ones a campaign has any business targeting on — an AISP account-access scope is
     * not a marketing basis (ADR-0198).
     */
    @Test
    fun `the marketing scopes a campaign may target still exist in the producer's vocabulary`() {
        assumeTrue(model.isFile, "sibling module not in this checkout (per-service build)")
        val source = model.readText()
        for (scope in listOf("MARKETING_COMMS_EMAIL", "MARKETING_COMMS_PUSH", "MARKETING_COMMS_INAPP")) {
            assertTrue(source.contains("$scope,"), "ConsentScope no longer declares $scope")
            // And the rule accepts it: shape check and vocabulary agree, so a name that exists
            // upstream is never rejected here for the wrong reason.
            Segment("c", 1, listOf(SegmentRule.HasActiveConsentScope(scope)))
        }
    }
}
