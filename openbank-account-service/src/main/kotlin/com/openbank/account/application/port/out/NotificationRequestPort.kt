// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.application.port.out

import java.math.BigDecimal
import java.util.UUID

/**
 * Emits a customer-facing notification request (ADR-0070 push pipeline). notification-service
 * consumes these, persists the notification (in-app feed) and fans out a push to the party's
 * registered devices. Best-effort and fire-and-forget from the caller's perspective.
 */
interface NotificationRequestPort {
    /** Notify the party that an incoming credit (e.g. the welcome bonus) landed on their account. */
    suspend fun notifyIncomingCredit(partyId: UUID, amount: BigDecimal, currency: String)
}
