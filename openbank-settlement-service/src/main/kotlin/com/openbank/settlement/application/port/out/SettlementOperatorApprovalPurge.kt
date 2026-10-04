// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.application.port.out

import java.time.OffsetDateTime

/**
 * Retention delete for settlement operator approvals (`settlement_operator_approvals`, V6).
 * Deletes at most [batchSize] rows whose authorization expired strictly before [cutoff], oldest
 * first, and returns how many were deleted. Never touches a still-live approval.
 */
interface SettlementOperatorApprovalPurge {
    suspend fun purgeTerminalExpiredBefore(cutoff: OffsetDateTime, batchSize: Int): Int
}
