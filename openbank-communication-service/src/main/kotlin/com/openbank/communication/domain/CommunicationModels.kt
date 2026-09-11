// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.communication.domain

import java.time.Instant
import java.util.UUID

/**
 * ADR-0285 D2: one persona per channel and audience. The catalogue is closed and
 * deploy-time-declared (Flyway-seeded), mirroring the ADR-0176 D2 "catalogue of meanings"
 * argument one level up — a business editor shapes a persona's STYLE, never invents a new
 * persona out of thin air, since a persona also gates which language the core enforces (D2:
 * "the core enforces the language; the style layer chooses how, not whether").
 */
data class Persona(
    val id: UUID,
    val key: String,
    val displayName: String,
    val channel: String,
    val language: String,
    val description: String,
)

enum class StyleVersionStatus { DRAFT, IN_REVIEW, PUBLISHED, RETIRED }

/**
 * D1's editable "style" layer for one persona: tone, formality, form of address, length,
 * vocabulary, signature. Immutable and append-only once IN_REVIEW (D4) — a correction is a new
 * version, never an edit of a published one.
 */
data class StyleVersion(
    val id: UUID,
    val personaId: UUID,
    val version: Int,
    val status: StyleVersionStatus,
    val tone: String,
    val formality: String,
    val formOfAddress: String,
    val maxLength: Int?,
    val preferredTerms: Map<String, String>,
    val forbiddenTerms: List<String>,
    val signature: String?,
    val maker: String,
    val createdAt: Instant,
    val decidedBy: String?,
    val decidedAt: Instant?,
    val publishedAt: Instant?,
    val retiredAt: Instant?,
)

/** The composed, published style a consumer fetches and caches (D5's `GET .../published`). */
data class PublishedStyle(
    val personaKey: String,
    val styleVersion: Int,
    val tone: String,
    val formality: String,
    val formOfAddress: String,
    val maxLength: Int?,
    val preferredTerms: Map<String, String>,
    val forbiddenTerms: List<String>,
    val signature: String?,
    val publishedAt: Instant,
)

/**
 * The outcome of handing a persona-published event to the transport. Mirrors
 * `openbank-referral-service`'s `ReferralPublishOutcome`: a skipped/unwired publish MUST NOT
 * share a signal with a real delivery, or an off-by-default adapter is indistinguishable from a
 * working one and no telemetry anywhere disagrees.
 */
enum class PersonaPublishOutcome {
    HANDED_TO_TRANSPORT,
    TRANSPORT_NOT_WIRED,
    ;

    val isHandedOff: Boolean get() = this == HANDED_TO_TRANSPORT
}

class PersonaNotFoundException(message: String) : RuntimeException(message)

class StyleVersionNotFoundException(message: String) : RuntimeException(message)

class StyleVersionConflictException(message: String) : RuntimeException(message)

class StyleVersionValidationException(message: String) : RuntimeException(message)

/**
 * D3's deterministic linter rejected a draft. Carries every violated rule (not just the first)
 * so an editor sees the whole problem in one round trip, not one rejection per save.
 */
class StyleLintRejectedException(val violations: List<String>) :
    RuntimeException("style text rejected by lint: ${violations.joinToString("; ")}")
