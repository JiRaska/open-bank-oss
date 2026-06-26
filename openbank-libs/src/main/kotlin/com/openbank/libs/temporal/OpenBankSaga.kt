// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.libs.temporal

/**
 * Lightweight saga compensation container. Compensations are registered in order
 * and executed in reverse (LIFO) so each step's undo runs before the step that
 * preceded it.
 *
 * Usage:
 * ```kotlin
 * val result = saga {
 *     val a = stepA()
 *     addCompensation { undoA(a) }
 *     val b = stepB(a)
 *     addCompensation { undoB(b) }
 * }
 * // on any exception the caller catches and calls result.compensate()
 * ```
 */
class OpenBankSaga {
    private val compensations = ArrayDeque<() -> Unit>()

    /** Register a compensation action; called in reverse-registration order on [compensate]. */
    fun addCompensation(action: () -> Unit) {
        compensations.addFirst(action)
    }

    /** Execute all registered compensations in reverse order. */
    fun compensate() {
        compensations.forEach { it() }
    }
}

/** DSL entry point: build and return an [OpenBankSaga] with the supplied block applied. */
inline fun saga(block: OpenBankSaga.() -> Unit): OpenBankSaga = OpenBankSaga().apply(block)
