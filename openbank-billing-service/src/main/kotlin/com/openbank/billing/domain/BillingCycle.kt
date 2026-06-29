// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.domain

import java.time.LocalDate

/**
 * A billing cycle — the period a batch of fee assessments belongs to (ADR-0143). The
 * scheduled trigger that opens/closes cycles is phase 2c; this is the value object the
 * assessment is keyed by.
 */
data class BillingCycle(val cycleId: String, val periodStart: LocalDate, val periodEnd: LocalDate)
