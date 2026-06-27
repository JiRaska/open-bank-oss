// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.application.port.out

import com.openbank.notification.domain.model.PushPlatform
import com.openbank.notification.domain.model.PushResult
import io.smallrye.mutiny.Uni

/**
 * Outbound port: deliver one push message to one device token. Implementations are the
 * per-platform adapters (FCM, APNs) selected by the router. Returns a `Uni` so it composes
 * inside the consumer's reactive chain without blocking the Kafka polling thread.
 */
interface PushSender {
    fun send(message: PushMessage): Uni<PushResult>
}

/** A rendered push, ready for transport. `data` carries optional silent key/value payload. */
data class PushMessage(
    val platform: PushPlatform,
    val token: String,
    val title: String,
    val body: String,
    val data: Map<String, String> = emptyMap(),
)
