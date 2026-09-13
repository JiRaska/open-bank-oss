// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.communication.domain

import java.time.Instant
import java.util.UUID

/**
 * D4's golden-set entry: "question / expected properties pairs authored in the same UI
 * (language, no figure from memory, tone markers, required compliance sentence present)".
 *
 * This type is deliberately data-only — CRUD and storage, nothing that replays it. D4 also
 * requires publishing a style or playbook draft to first replay it against the golden set
 * through "the real composing service" on a synthetic customer, and block publication on a
 * regression. That replay engine composes `core + style + playbook` at runtime — the same
 * cross-service wiring ADR-0285's own delivery phases stage separately (phase 4, after phases
 * 2-3's four-eyes-only publish) and that this change does NOT build: entries created here are
 * inert until a later change wires the replay gate. See the threat model's residual-risk entry.
 */
data class GoldenSetEntry(
    val id: UUID,
    val personaId: UUID,
    val question: String,
    /** BCP-47 tag the composed answer is expected to be in, e.g. "cs" or "en". */
    val expectedLanguage: String,
    /** D4: "no figure from memory" — the composed answer must not state an amount/rate as fact. */
    val expectNoFigureFromMemory: Boolean,
    /** D4: tone markers the composed answer is expected to carry (e.g. "brief", "formal address"). */
    val expectedToneMarkers: List<String>,
    /** D4: a compliance sentence the composed answer must contain verbatim, if any is required. */
    val requiredComplianceSentence: String?,
    val createdBy: String,
    val createdAt: Instant,
)

class GoldenSetEntryNotFoundException(message: String) : RuntimeException(message)

class GoldenSetEntryValidationException(message: String) : RuntimeException(message)
