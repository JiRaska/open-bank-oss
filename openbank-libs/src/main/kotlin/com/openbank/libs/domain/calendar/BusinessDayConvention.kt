// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.libs.domain.calendar

/**
 * Date-rolling conventions for moving a non-business day onto a business day.
 *
 * Mirrors the ISDA/market conventions used for value and settlement dates:
 * - [FOLLOWING]: roll forward to the next business day.
 * - [MODIFIED_FOLLOWING]: roll forward, but if that crosses into the next calendar month,
 *   roll backward instead (keeps the date in the same month).
 * - [PRECEDING]: roll backward to the previous business day.
 * - [MODIFIED_PRECEDING]: roll backward, but if that crosses into the previous month,
 *   roll forward instead.
 */
enum class BusinessDayConvention {
    FOLLOWING,
    MODIFIED_FOLLOWING,
    PRECEDING,
    MODIFIED_PRECEDING,
}
