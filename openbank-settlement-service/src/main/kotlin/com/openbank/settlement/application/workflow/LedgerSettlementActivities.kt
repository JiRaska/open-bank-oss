// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.settlement.application.workflow

import com.openbank.settlement.domain.model.SettlementStatus
import io.temporal.activity.ActivityInterface
import java.util.UUID

@ActivityInterface
interface LedgerSettlementActivities {
    fun reserveSettlementCover(id: UUID)
    fun recordProjectionOutcomeUnknown(id: UUID, ledgerStarted: Boolean): SettlementStatus
}
