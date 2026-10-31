// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.application

import java.util.UUID

/**
 * Creation invariant for a deployment configured for one provider. This does not establish
 * caller authorization or isolate existing data, databases, workflow queues or fund holdings.
 */
class ProviderBoundary(val providerEntityId: UUID) {
    fun requireProvider(requestedProviderEntityId: UUID) {
        require(requestedProviderEntityId == providerEntityId) {
            "providerEntityId does not match the configured pension provider"
        }
    }
}
