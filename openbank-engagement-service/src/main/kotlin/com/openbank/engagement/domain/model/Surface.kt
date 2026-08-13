// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.domain.model

import java.util.UUID

/**
 * Named slots the app registers to render content into (ADR-0220 D1). A slot has no content of
 * its own — it is a place, not a message, exactly the distinction that keeps this from being the
 * removed `IN_APP` notification channel reborn under a new name (issue #2372, ADR-0200 D7).
 */
enum class SurfaceSlot { HOME_BANNER, HOME_CAROUSEL, STORIES, PRODUCT_FEED, REWARDS_HUB }

/**
 * Typed payload kinds. Never free-form markup — an allow-list of shapes is what makes this
 * auditable; arbitrary server HTML is the phishing-template-with-a-bank-logo risk ADR-0220's own
 * "Alternatives considered" names and rejects.
 */
enum class SurfaceContentType { BANNER, CARD, STORY, CAROUSEL, OFFER }

/**
 * One catalogue entry: a slot, a content type, and the closed set of variable names it declares.
 * Same discipline as `TemplateCatalog`/`SegmentCatalog` (ADR-0176 D4, ADR-0201 D1) — a marketer or
 * an agent cannot invent a payload from a text box, only reference one that was reviewed.
 *
 * `OFFER` is declared here as a content type because the type vocabulary is closed and future
 * proof, but no `OFFER` entries exist in [SurfaceCatalog] yet: ADR-0220 D4 requires every rendered
 * credit payload to carry the mandatory RPSN/APR representative example from a real ADR-0142
 * standing decision, and ADR-0142 does not exist in this codebase yet (`decision-status: proposed`).
 * Adding an `OFFER` entry before that would be exactly the "inauthentic placeholder" ADR-0220 D5
 * calls worse than a missing feature.
 */
data class SurfaceContent(
    val id: String,
    val slot: SurfaceSlot,
    val type: SurfaceContentType,
    val variables: Set<String>,
    /** Values exist only on a trusted, campaign-assigned placement; catalogue items declare keys. */
    val values: Map<String, String> = emptyMap(),
    /** Bank-owned app route, never a marketer-entered URL. */
    val deepLink: String? = null,
    /** Opaque campaign send-log id used only when the app reports an interaction back. */
    val interactionRef: UUID? = null,
) {
    init {
        require(id.isNotBlank()) { "content id must not be blank" }
        require(type != SurfaceContentType.OFFER) {
            "OFFER content cannot be catalogued until ADR-0142 exists — see SurfaceContent KDoc"
        }
    }
}

/**
 * The reviewed catalogue of renderable content. Adding an entry is a pull request, same as
 * `TemplateCatalog` — never a runtime or admin-ui action.
 */
object SurfaceCatalog {
    val ALL: Map<String, SurfaceContent> = mapOf(
        "SAVINGS_RATE_BANNER" to SurfaceContent(
            id = "SAVINGS_RATE_BANNER",
            slot = SurfaceSlot.HOME_BANNER,
            type = SurfaceContentType.BANNER,
            variables = setOf("rateHeadline"),
        ),
    )

    fun exists(id: String): Boolean = id in ALL

    fun forSlot(slot: SurfaceSlot): List<SurfaceContent> = ALL.values.filter { it.slot == slot }

    fun unknownVariables(id: String, variables: Map<String, String>): Set<String> =
        variables.keys - (ALL[id]?.variables ?: emptySet())
}
