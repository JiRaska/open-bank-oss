// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.domain.model

import java.time.LocalDate

/**
 * The one rule for a first due date the bank chooses itself (customer intake, the customer quote, and
 * the successor of an overturned decline review): the first day of the month after next, so the
 * borrower has a full month of runway before instalment one. Staff intake supplies its own date.
 */
object FirstDueDate {
    fun forApplicationOn(today: LocalDate): LocalDate = today.withDayOfMonth(1).plusMonths(2)
}
