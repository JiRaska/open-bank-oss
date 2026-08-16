// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.persistence.outbox

/**
 * Framework-free observation hook for [OutboxDispatch.dispatchOnce] (issue #5091 phase 1).
 *
 * This module has zero framework imports by design (ADR-0002/ADR-0122, `check-domain-purity.py`),
 * so it cannot call `DomainMetrics` (openbank-libs-runtime, depends on Micrometer) directly. This
 * interface is the port: `dispatchOnce` calls it on every dispatch outcome it can see, and the
 * runtime-side [AbstractOutboxDispatcher] implements it by delegating to `DomainMetrics` — the
 * only place in this module's call graph allowed to know a metrics library exists.
 *
 * A single abstract method by design (`fun interface`, SAM-convertible) — callers construct one
 * inline as a lambda: `OutboxDispatchObserver { entry -> metrics.outboxDispatched(...) }`.
 * [NOOP] is the explicit "observe nothing" implementation, not a default method body, since a
 * `fun interface`'s one method cannot itself carry a default (it would no longer be a single
 * abstract member and SAM conversion would stop working). Adding a genuinely new observation
 * point later (e.g. once phase 2 makes `onDead` possible) means a new interface, not a new method
 * on this one — see `DomainMetrics`'s own file header for why growing an existing single-method
 * contract is avoided here.
 *
 * **Deliberately does NOT have an `onDead` callback.** `OutboxRepository.markFailed` returns
 * `Unit` today, so `dispatchOnce` has no way to know whether a given failure was the one that
 * tipped a row into terminal `DEAD` versus just incremented its attempt counter — emitting a dead
 * signal from here now would be a guess, not an observation. That needs `markFailed`'s signature
 * to change across all ~31 `OutboxRepository` implementations first (issue #5091 phase 2,
 * deliberately scoped separately — a much larger and more invasive change than this one).
 */
fun interface OutboxDispatchObserver {
    /** Called after [OutboxRepository.markSent] succeeds for [entry] — a real, confirmed dispatch. */
    fun onDispatched(entry: OutboxEntry)

    companion object {
        val NOOP: OutboxDispatchObserver = OutboxDispatchObserver { }
    }
}
