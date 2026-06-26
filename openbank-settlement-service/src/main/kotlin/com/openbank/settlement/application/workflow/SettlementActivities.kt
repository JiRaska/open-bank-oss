// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.settlement.application.workflow

import io.temporal.activity.ActivityInterface
import java.util.UUID

@ActivityInterface
interface SettlementActivities {
    fun debitPayer(settlementId: UUID)
    fun creditPayee(settlementId: UUID)
    fun bookToLedger(settlementId: UUID)
    fun reverseDebit(settlementId: UUID)
    fun reverseCredit(settlementId: UUID)
    fun reverseBookToLedger(settlementId: UUID)
    fun rejectSettlement(settlementId: UUID)
}
