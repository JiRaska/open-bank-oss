// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.domain.model

import java.time.Instant
import java.util.UUID

/**
 * Per-(account, payee) payment history (ADR-0084 §3 v4 — "new-payee + high-amount" signal).
 * A payee is "established" once at least one prior payment has been recorded for the pair; a
 * missing row (null from the repository) means this payee has never been paid before.
 */
data class PayeeHistory(
    val accountId: UUID,
    val payeeIdentifier: String,
    val firstSeenAt: Instant,
    val lastPaidAt: Instant,
    val paymentCount: Long,
)
