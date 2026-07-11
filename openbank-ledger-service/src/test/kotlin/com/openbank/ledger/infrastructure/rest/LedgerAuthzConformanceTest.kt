// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure.rest

import com.openbank.libs.testing.authz.RestAuthzConformanceTest

/**
 * The generic half of the K7/ADR-0018 regression guard (issue #467, `openbank-libs-testing`
 * pilot): no endpoint on either ledger resource may be `@PermitAll`, and every endpoint must
 * carry `@RolesAllowed`. Service-specific role assertions (which roles a given endpoint must
 * carry) stay local — see [LedgerSecurityContractTest] and [YearCloseSecurityContractTest].
 */
class LedgerAuthzConformanceTest : RestAuthzConformanceTest() {
    override val resourceClasses = listOf(LedgerResource::class, YearCloseResource::class)
}
