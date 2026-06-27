// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.case

/** Lifecycle status for a reusable case. */
enum class CaseStatus {
    DRAFT,
    OPEN,
    IN_REVIEW,
    WAITING_FOR_CUSTOMER,
    WAITING_FOR_EXTERNAL_PARTY,
    APPROVED,
    REJECTED,
    CLOSED,
    CANCELLED,
}
