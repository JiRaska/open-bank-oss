// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.usecase

/**
 * A decline-review command refused for a reason the caller can act on, with a stable machine [code].
 * A subtype of [IllegalStateException] so a caller that does not know it still sees a refusal.
 */
class DeclineReviewRefusedException(val code: String, message: String) : IllegalStateException(message) {
    companion object {
        /** No such application or review — also what a foreign application id reads as. */
        const val NOT_FOUND: String = "NOT_FOUND"

        /** Only a decline made by the decision engine is reviewable; a human decision is not looped back. */
        const val NOT_ENGINE_DECLINE: String = "NOT_ENGINE_DECLINE"

        /** The bounded window after the decline has elapsed. */
        const val WINDOW_ELAPSED: String = "REVIEW_WINDOW_ELAPSED"

        /** The review already has a decision. */
        const val ALREADY_DECIDED: String = "REVIEW_ALREADY_DECIDED"

        /** The reviewer is the applicant. */
        const val SELF_REVIEW: String = "SELF_REVIEW"
    }
}
