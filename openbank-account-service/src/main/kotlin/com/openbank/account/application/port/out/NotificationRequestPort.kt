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

    /**
     * The party has an account they can actually use (#8432).
     *
     * Fired when an account becomes USABLE, not when its row is created. ADR-0267's onboarding
     * opens CURRENT + SAVINGS as `PENDING_ACTIVATION`, which `canDebit`/`canCredit` both refuse —
     * announcing those would tell a new customer twice about accounts that cannot move money, and
     * then say nothing at the moment they actually go live. So the trigger is an account opened
     * ACTIVE (the operator path) or an account activated by the KYC+AML gate.
     */
    suspend fun notifyAccountOpened(partyId: UUID, accountNumber: String)

    /** The account is closed. Terminal, and the customer should not have to discover it. */
    suspend fun notifyAccountClosed(partyId: UUID, accountNumber: String)

    /**
     * The account is frozen and no money can move.
     *
     * [reason] is the operator's, rendered to the customer by the template. This is the
     * notification a customer would want most and the one that has never been sent: an account
     * freeze is invisible until they try to pay and fail.
     */
    suspend fun notifyAccountFrozen(partyId: UUID, accountNumber: String, reason: String)
}
