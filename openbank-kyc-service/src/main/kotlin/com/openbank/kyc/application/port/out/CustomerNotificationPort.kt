// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.kyc.application.port.out

import java.util.UUID

/**
 * Telling the customer how their own identity verification ended (#8432).
 *
 * notification-service has declared `KYC_APPROVED` and `KYC_REJECTED` since it was written —
 * rendered, given required variables, and classified SECURITY so a customer cannot mute them — and
 * until now **nothing emitted either**. A customer was never told the outcome of their own KYC:
 * `GET /customer/v1/kyc` reports it accurately, but only to someone who thinks to go and look.
 *
 * Deliberately narrow. This port carries the two OUTCOME notifications and nothing else;
 * `KYC_DOCUMENT_REQUIRED` is not here because there is no transition to fire it from and nowhere
 * for the customer to put a document — a push asking for something that cannot be supplied is worse
 * than the silence it replaces (see #8432 and ADR-0256, still `proposed`).
 */
interface CustomerNotificationPort {

    /** The party's verification passed. Carries no variables — the template declares none. */
    suspend fun notifyKycApproved(partyId: UUID)

    /**
     * The party's verification failed.
     *
     * [reason] is the operator's audit-trail reason, which the template renders to the customer
     * verbatim. It is already length-guarded by `KycService.validateReason` for the ČNB trail;
     * callers must not pass anything the reviewer did not write.
     */
    suspend fun notifyKycRejected(partyId: UUID, reason: String)
}
