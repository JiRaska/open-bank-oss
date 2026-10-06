// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.usecase

/**
 * An origination command refused for a control reason the caller can act on, carrying a stable
 * machine [code]. A subtype of [IllegalStateException] so every existing caller still sees a
 * refusal; the REST layer maps it to 409 with `{error: code, message}`. Every instance is also
 * recorded as `credit.application.transition.refused` evidence before it is thrown.
 */
class OriginationRefusedException(val code: String, message: String) : IllegalStateException(message) {
    companion object {
        /** The application sits in a decision state; it moves on only through the decision endpoint. */
        const val DECISION_REQUIRED: String = "FOUR_EYES_DECISION_REQUIRED"

        /** The application is past the decision point with no recorded decider; fail closed. */
        const val DECISION_MISSING: String = "FOUR_EYES_DECISION_MISSING"

        /** The acting principal is the proposer or the approver of this application. */
        const val SEGREGATION_OF_DUTIES: String = "SEGREGATION_OF_DUTIES"
    }
}
