// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.case

import java.time.Instant

/** Immutable timeline event produced by a successful case transition. */
data class CaseTimelineEvent(
    val caseId: CaseId,
    val caseType: CaseType,
    val fromStatus: CaseStatus,
    val toStatus: CaseStatus,
    val reasonCode: CaseReasonCode,
    val actor: String,
    val occurredAt: Instant,
    val metadata: Map<String, String> = emptyMap(),
)
