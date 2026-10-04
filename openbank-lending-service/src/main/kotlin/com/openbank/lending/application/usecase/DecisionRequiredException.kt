// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.usecase

import com.openbank.libs.lending.origination.OriginationState

/**
 * The application sits in a decision state, which the generic forward drive cannot leave:
 * it moves on only through a decision by a person other than [proposedBy]. A subtype of
 * [IllegalStateException] so every existing caller still sees a refusal; the REST layer maps
 * it to 409 [CODE] so the client can point the user at the decision action.
 */
class DecisionRequiredException(val state: OriginationState, val proposedBy: String) :
    IllegalStateException(
        "Application is in $state and awaits a four-eyes decision by someone other than the proposer " +
            "($proposedBy); use POST /api/v1/lending/applications/{id}/decision instead of advance",
    ) {
    companion object {
        const val CODE: String = "FOUR_EYES_DECISION_REQUIRED"
    }
}
