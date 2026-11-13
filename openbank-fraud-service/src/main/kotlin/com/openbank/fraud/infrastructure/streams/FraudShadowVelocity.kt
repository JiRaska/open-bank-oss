// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.infrastructure.streams

import io.quarkus.runtime.annotations.RegisterForReflection

/** Versioned bucket snapshot; occurredAt identifies the last contributor, not an as-of watermark. */
@RegisterForReflection
data class FraudShadowVelocity(
    val accountId: String,
    val eventId: String,
    val occurredAt: String,
    val sourceTopic: String,
    val sourcePartition: Int,
    val sourceOffset: Long,
    val window: String,
    val windowStart: String,
    val windowEndExclusive: String,
    val count: Long,
    val schemaVersion: Int = 1,
    val eventType: String = EVENT_TYPE,
) {
    companion object {
        const val EVENT_TYPE = "FraudShadowVelocity"
    }
}
