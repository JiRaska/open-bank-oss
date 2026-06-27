// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.case

/** Generic reasons explaining why a case transition happened. */
enum class CaseReasonCode {
    CREATED,
    REVIEW_STARTED,
    INFORMATION_REQUESTED,
    INFORMATION_RECEIVED,
    EXTERNAL_DEPENDENCY,
    APPROVED,
    REJECTED,
    CLOSED,
    CANCELLED,
    REOPENED,
    MANUAL_UPDATE,
}
