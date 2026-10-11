// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.infrastructure.rest

import io.quarkus.security.identity.SecurityIdentity

/** The authenticated principal, which is what four-eyes compares — never a client-supplied header. */
internal fun SecurityIdentity.actor(): String {
    val name = principal?.name
    require(!isAnonymous && !name.isNullOrBlank()) { "an authenticated principal is required for this action" }
    return name
}
