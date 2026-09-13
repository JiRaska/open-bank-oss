// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.domain.model.gamification

import java.time.Instant

/**
 * One reviewed challenge definition (ADR-0220 D3, same review discipline as `SurfaceCatalog` /
 * campaign `TemplateCatalog`): a party who completes [earnSource] earns [rewardPoints].
 *
 * [genuineExpiry] is D3 rule 3's provenance field, made structural rather than a display-layer
 * convention: "No fake urgency: countdowns only on genuinely expiring offers." A challenge with a
 * [deadline] but `genuineExpiry = false` cannot be constructed at all (see [init]) — there is no
 * way to attach a deadline to a challenge without also declaring, at the same call site, that the
 * expiry is real. The KMP client is expected to render a countdown if and only if [deadline] is
 * non-null; because [deadline] can never be non-null without [genuineExpiry] being `true`, "does
 * this challenge have a deadline" and "may the client show urgency for it" collapse into the same
 * safe check for the renderer, instead of two flags that could disagree.
 */
data class Challenge(
    val id: String,
    val earnSource: EarnSource,
    val rewardPoints: Points,
    val deadline: Instant? = null,
    val genuineExpiry: Boolean = false,
) {
    init {
        require(id.isNotBlank()) { "challenge id must not be blank" }
        require(deadline == null || genuineExpiry) {
            "challenge '$id' has a deadline but genuineExpiry=false — ADR-0220 D3 rule 3 forbids a " +
                "countdown that is not genuine; either drop the deadline or declare genuineExpiry=true"
        }
    }
}

/**
 * The reviewed challenge catalogue. Adding an entry is a pull request, same discipline as
 * `SurfaceCatalog`/campaign `TemplateCatalog` — never a runtime or admin-ui action, and never a
 * challenge whose [Challenge.earnSource] rewards credit uptake (ADR-0220's "Alternatives
 * considered" rejects that absolutely; [EarnSource]'s closed catalogue makes it impossible to
 * reference a credit-uptake reason here even if someone tried).
 */
object ChallengeCatalog {
    val ALL: Map<String, Challenge> = mapOf(
        "COMPLETE_BUDGETING_COURSE" to Challenge(
            id = "COMPLETE_BUDGETING_COURSE",
            earnSource = EarnSource.EducationalContentCompletion,
            rewardPoints = Points.of(BUDGETING_COURSE_POINTS),
        ),
    )

    fun exists(id: String): Boolean = id in ALL

    private const val BUDGETING_COURSE_POINTS = 50
}
