// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.integration

import com.openbank.ledger.it.PostgresRedpandaTestResource
import com.openbank.libs.testing.mtls.ListenerAuthParityConformance
import com.openbank.libs.testing.mtls.ProductionListenerProfile
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile

/** The production listener must never turn a trusted workload certificate into a ledger API identity. */
@QuarkusTest
@TestProfile(ListenerAuthParityIT.LedgerListenerProfile::class)
@QuarkusTestResource(PostgresRedpandaTestResource::class)
class ListenerAuthParityIT : ListenerAuthParityConformance() {
    override val path = "/api/v1/ledger/accounting-days/current"

    class LedgerListenerProfile : ProductionListenerProfile()
}
