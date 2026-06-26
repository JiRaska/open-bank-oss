// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.libs.domain.case

/** Reusable case categories shared across services. */
enum class CaseType {
    PID_VERIFICATION,
    GENERIC_REVIEW,
    COMPLIANCE,
    DISPUTE,
    FRAUD_INVESTIGATION,
    CUSTOMER_REQUEST,
}
