// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.domain.model

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Rolling velocity window type. Each maps to a time bucket. */
enum class VelocityWindow { H1, H24, D7 }

/** Per-account rolling aggregate for one velocity window (ADR-0084 §2). */
data class VelocityAggregate(
    val accountId: UUID,
    val window: VelocityWindow,
    val transactionCount: Long,
    val totalAmount: BigDecimal,
    val currency: String,
    val windowStart: Instant,
)
