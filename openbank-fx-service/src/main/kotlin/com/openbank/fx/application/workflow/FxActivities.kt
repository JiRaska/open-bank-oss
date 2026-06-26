// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.fx.application.workflow

import com.openbank.fx.domain.screening.ScreeningDecision
import io.temporal.activity.ActivityInterface
import java.util.UUID

@ActivityInterface
interface FxActivities {
    fun screenConversion(conversionId: UUID): ScreeningDecision
    fun settleConversion(conversionId: UUID)
    fun blockConversion(conversionId: UUID)
    fun holdConversion(conversionId: UUID)
    fun shadowFraudScore(conversionId: UUID)
}
