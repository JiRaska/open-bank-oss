// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.communication.domain

import java.time.Instant
import java.util.UUID

/** D7's step-tree shape: a call script is an ordered sequence of these. */
enum class CallScriptStepKind { GREETING, VERIFICATION, RESOLUTION, MANDATORY_SENTENCE, CLOSE }

data class CallScriptStep(
    val order: Int,
    val kind: CallScriptStepKind,
    val text: String,
    /**
     * D7: "mandatory sentences" are a distinct step kind already — this flag additionally marks a
     * step of ANY kind (e.g. a compliance disclosure inside RESOLUTION) as non-optional, so agent
     * guidance can render "must say this" independently of where it sits in the script.
     */
    val mandatory: Boolean,
)

/** D7's "approved answers": a situation and the bank's reviewed response to it. */
data class ApprovedAnswer(val situation: String, val answer: String)

enum class PlaybookVersionStatus { DRAFT, IN_REVIEW, PUBLISHED, RETIRED }

/**
 * One persona's whole playbook (call script + approved answers) as a single versioned unit —
 * simpler than versioning each answer independently, and still satisfies D4's lifecycle
 * (DRAFT -> IN_REVIEW -> PUBLISHED -> RETIRED) and D3's two controls (lint on save,
 * commstyle.publish four-eyes on publish — the SAME action name D3 names for style, since D3's own
 * text is "publishing a style OR PLAYBOOK version": no new rules.yaml/rest.rego entry needed).
 */
data class PlaybookVersion(
    val id: UUID,
    val personaId: UUID,
    val version: Int,
    val status: PlaybookVersionStatus,
    val callScript: List<CallScriptStep>,
    val approvedAnswers: List<ApprovedAnswer>,
    val maker: String,
    val createdAt: Instant,
    val decidedBy: String?,
    val decidedAt: Instant?,
    val publishedAt: Instant?,
    val retiredAt: Instant?,
)

/** The composed, published playbook a consumer (agent-assist view, retrieval) reads. */
data class PublishedPlaybook(
    val personaKey: String,
    val playbookVersion: Int,
    val callScript: List<CallScriptStep>,
    val approvedAnswers: List<ApprovedAnswer>,
    val publishedAt: Instant,
)

class PlaybookVersionNotFoundException(message: String) : RuntimeException(message)

class PlaybookVersionConflictException(message: String) : RuntimeException(message)

class PlaybookVersionValidationException(message: String) : RuntimeException(message)
