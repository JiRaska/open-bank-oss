// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.domain.model

/**
 * Ledger-wide validation failure (422), replacing bare `IllegalArgumentException` / `require()`
 * across the domain and application layers (issue #526). A service-local
 * `ExceptionMapper<IllegalArgumentException>` collided non-deterministically with
 * `openbank-libs-runtime`'s own mapper for the identical JDK type — JAX-RS has no defined
 * tie-breaker between two providers registered for the same type, so the status code (422 here
 * vs libs' 400) was a per-request lottery, not the "intentional override" the removed mapper's
 * comment claimed. A dedicated type is unambiguous: JAX-RS always picks the most specific
 * matching provider.
 */
class LedgerValidationException(message: String) : RuntimeException(message)

/**
 * Ledger-wide conflict / invariant violation (409), replacing bare `IllegalStateException` /
 * `check()` across the domain and application layers (issue #526). Same collision class as
 * [LedgerValidationException], against libs-runtime's `ExceptionMapper<IllegalStateException>`
 * (422). Distinct from [com.openbank.ledger.application.usecase.JournalReversalConflictException]
 * and the other already-dedicated conflict types (#465-era), which stay separate because their
 * response bodies/semantics are more specific than a generic conflict.
 */
class LedgerConflictException(message: String) : RuntimeException(message)

/** `require()`-equivalent that throws [LedgerValidationException] instead of the JDK type. */
inline fun requireValid(condition: Boolean, lazyMessage: () -> String) {
    if (!condition) throw LedgerValidationException(lazyMessage())
}

/** `check()`-equivalent that throws [LedgerConflictException] instead of the JDK type. */
inline fun checkConflict(condition: Boolean, lazyMessage: () -> String) {
    if (!condition) throw LedgerConflictException(lazyMessage())
}
