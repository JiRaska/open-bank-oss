// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.resilience

/**
 * A transient failure (connect, timeout, 5xx) of a call that carried an `Idempotency-Key`, and is
 * therefore safe to repeat. The ONLY type in a money-sync `@Retry(retryOn = ...)`.
 */
class RetryableKeyedCallException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * A transient failure (connect, timeout, 5xx) of a call WITHOUT an `Idempotency-Key`. Repeating it
 * could execute the write twice, so a money-sync `@Retry` lists it in `abortOn`.
 */
class UpstreamCallException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
