// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package org.eclipse.microprofile.faulttolerance.exceptions

/**
 * Test-only stand-in for the MicroProfile exception of the same fully-qualified name (#4005).
 *
 * `openbank-libs-domain` has zero framework dependencies — not even `compileOnly` ones
 * (ADR-0002/ADR-0122, enforced by `check-domain-purity.py`) — so the real
 * `microprofile-fault-tolerance-api` artifact is on no classpath of this module, and there is
 * nothing here for this declaration to collide with. `OutboxDispatch` classifies by
 * `javaClass.name`, so the *name* is the whole contract under test: a stand-in in another package
 * would assert nothing about the real signal.
 *
 * The real class is exercised end-to-end, through a real CDI `@CircuitBreaker` interceptor and a
 * real Postgres, in `openbank-card-issuance-service`'s `CardOutboxBreakerOpenIT`.
 */
class CircuitBreakerOpenException(message: String) : RuntimeException(message)
