// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.web

import jakarta.enterprise.context.RequestScoped

/**
 * Request-scoped carrier for [SyntheticTaintRequestFilter]'s authenticated trust decision.
 *
 * The filter writes both this non-null Boolean and the JAX-RS request property. Ledger reads
 * their logical OR; false in one carrier does not override true in the other. The initial false
 * value is not an absent/inactive-CDI-context fallback. Controlled HTTP property-loss and
 * overlapping-request tests verify the holder's role, not the older deployed loss mechanism.
 */
@RequestScoped
class SyntheticTaintContext {
    var tainted: Boolean = false
}
