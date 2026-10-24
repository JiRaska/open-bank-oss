// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.flags

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/**
 * Evaluates a flag without blocking the caller's event loop. A synchronous provider may perform
 * network I/O even when its sidecar is local. Cancellation interrupts that pending I/O; subsequent
 * business logic resumes in the caller's coroutine context, never on the I/O dispatcher.
 */
suspend fun FeatureClient.enabledSuspending(flag: String, ctx: EvalContext = EvalContext.EMPTY): Boolean =
    runInterruptible(Dispatchers.IO) { enabled(flag, ctx) }
