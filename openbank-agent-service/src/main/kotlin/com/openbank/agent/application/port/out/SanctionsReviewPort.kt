// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.application.port.out

import com.fasterxml.jackson.databind.JsonNode

/**
 * The agent's one sanctions WRITE: submitting a review decision as the four-eyes MAKER (ADR-0155).
 * sanctions-service answers PENDING_APPROVAL with an approval id until a distinct human checker
 * approves; the identical request retried with that id then executes. Kept apart from
 * [DownstreamReadPort] so the read adapter stays structurally read-only.
 */
interface SanctionsReviewPort {

    /** Outcome of one review submission: the upstream HTTP status and its body. */
    data class Outcome(val status: Int, val body: JsonNode?)

    fun getCheck(checkId: String): JsonNode

    fun submitReview(checkId: String, newStatus: String, note: String, approvalId: String?): Outcome
}
