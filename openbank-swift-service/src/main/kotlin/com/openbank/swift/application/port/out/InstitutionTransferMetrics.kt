// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.application.port.out

import com.openbank.swift.domain.institution.InstitutionTransferRail

/** Why automation stopped on a bank-own transfer. A closed vocabulary: it becomes a metric label. */
enum class ReconciliationCause {
    /** The status query answered 404 without the scheme's typed "unknown UETR" body. */
    UNTYPED_NOT_FOUND,

    /** The status report named another MsgId or UETR than the one asked about. */
    MISMATCHED_STATUS,

    /** The scheme still did not know the UETR after the one permitted resend. */
    UNKNOWN_AFTER_RESEND,

    /** The scheme says it never received a transfer it had already accepted (SENT). */
    SENT_UNKNOWN_TO_SCHEME,
}

/** What one resolution-sweeper pass did to one due transfer. A closed vocabulary (metric label). */
enum class ResolutionOutcome { SETTLED, REJECTED, STILL_SENT, MANUAL_RECONCILIATION, UNRESOLVED, FAILED }

/** Signals of the bank-own transfer rail that an operator must see. */
interface InstitutionTransferMetrics {
    /** A transfer entered MANUAL_RECONCILIATION — automation stopped; a human must reconcile it. */
    fun manualReconciliation(rail: InstitutionTransferRail, cause: ReconciliationCause)

    /** One due transfer handled by the resolution sweeper, with the outcome it ended in. */
    fun resolution(outcome: ResolutionOutcome)
}
