// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.warmup

/** One isolated warm-up step. [action] returns a short human-readable outcome for the log. */
data class WarmupStep(val name: String, val action: () -> String)

data class WarmupStepResult(val name: String, val succeeded: Boolean, val millis: Long, val detail: String)

/**
 * Runs every step, each in its own try/catch(Throwable): one failing step (a missing datasource,
 * a native-library `Error`, a refused self-call) never stops the ones after it. Warm-up is
 * best-effort by definition — its only observable contract is "the gate opens".
 */
object WarmupRunner {
    fun run(steps: List<WarmupStep>, log: (WarmupStepResult) -> Unit = {}): List<WarmupStepResult> = steps.map { step ->
        val t0 = System.nanoTime()
        val result = try {
            val detail = step.action()
            WarmupStepResult(step.name, true, (System.nanoTime() - t0) / NANOS_PER_MILLI, detail)
        } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
            WarmupStepResult(step.name, false, (System.nanoTime() - t0) / NANOS_PER_MILLI, t.toString())
        }
        log(result)
        result
    }

    private const val NANOS_PER_MILLI = 1_000_000L
}
