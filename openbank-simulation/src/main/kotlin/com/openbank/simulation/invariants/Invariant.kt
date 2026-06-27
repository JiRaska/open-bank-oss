// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.invariants

import com.openbank.simulation.runner.World

/** A global assertion checked after every simulated step (ADR-0100 Layer 3). */
interface Invariant {
    val name: String

    /** Returns a [Violation] if the invariant is broken in [world], or null if it holds. */
    fun check(world: World): Violation?
}

/** A broken invariant: which one, and a human-readable detail for the failing trace. */
data class Violation(val invariant: String, val detail: String)
