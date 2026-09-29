// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.domain.model

import java.time.OffsetDateTime

private const val ANNUAL_REVIEW_MONTHS = 12L
private const val SEMIANNUAL_REVIEW_MONTHS = 6L
private const val QUARTERLY_REVIEW_MONTHS = 3L

/**
 * The grantor-selected review context for a delegation. It is deliberately NOT an authorisation
 * attribute: it controls only when the grantor is invited to review an already-active grant.
 *
 * COMPANY has no reliable size signal in the party system, so SME versus CORPORATE is never
 * inferred from a registration number, legal form or account balance. The human acting for the
 * business chooses it explicitly and the choice is retained as audit evidence on the grant.
 */
enum class DelegationRecertificationAudience(val reviewMonths: Long) {
    PERSONAL(ANNUAL_REVIEW_MONTHS),
    FOP(ANNUAL_REVIEW_MONTHS),
    SME(SEMIANNUAL_REVIEW_MONTHS),
    CORPORATE(QUARTERLY_REVIEW_MONTHS),
    ;

    fun nextDueAt(from: OffsetDateTime): OffsetDateTime = from.plusMonths(reviewMonths)
}
