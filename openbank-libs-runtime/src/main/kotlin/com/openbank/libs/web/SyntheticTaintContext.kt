// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.web

import jakarta.enterprise.context.RequestScoped

/**
 * The request-scoped carrier for [SyntheticTaintRequestFilter]'s trust decision (ADR-0252).
 *
 * The filter also sets [SYNTHETIC_TAINT_PROPERTY] on the JAX-RS `ContainerRequestContext`, but a
 * resource method that SUSPENDS (Kotlin `suspend fun`) reads that context after a coroutine
 * dispatch, and the property written pre-dispatch is not reliably visible there — measured live
 * on the sandbox ledger, where five fully trusted `x-openbank-synthetic: true` postings all
 * persisted `synthetic = false` (#14058). A `@RequestScoped` CDI bean is carried across that
 * dispatch by SmallRye context propagation — the same channel `SecurityIdentity` uses, which is
 * why resources already inject `SecurityIdentity` rather than trusting `@Context` in suspend
 * methods.
 *
 * Default `false` everywhere: fail-to-real, exactly like the filter's property.
 */
@RequestScoped
class SyntheticTaintContext {
    var tainted: Boolean = false
}
