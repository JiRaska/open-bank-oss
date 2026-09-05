// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.infrastructure.kafka

/**
 * The failure this service's consumer tests inject: a dependency being down, not a bad event.
 *
 * Lived in ConsumerRetryTest.kt until that service-local retry helper was replaced by the shared
 * [com.openbank.libs.messaging.EventRetry] (#5698). The helper's own behaviour is covered by
 * EventRetryTest in openbank-libs-runtime; this type stays because the consumer tests use it to
 * assert what matters HERE — that the failure reaches the connector instead of being acked away.
 *
 * Named rather than a bare RuntimeException so detekt's TooGenericExceptionThrown does not have to
 * be suppressed at every throw site.
 */
internal class TransientDbFailure : RuntimeException("connection refused")
