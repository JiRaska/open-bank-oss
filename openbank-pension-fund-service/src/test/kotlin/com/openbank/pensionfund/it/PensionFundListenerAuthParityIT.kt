// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.it

import com.openbank.libs.testing.mtls.ListenerAuthParityConformance
import com.openbank.libs.testing.mtls.ProductionListenerProfile
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile

/** Real listener and OIDC bearer parity for the production pension-fund API policy (#12511). */
@QuarkusTest
@TestProfile(PensionFundListenerAuthParityIT.ProductionProfile::class)
@QuarkusTestResource(PostgresTestResource::class, restrictToAnnotatedClass = true)
class PensionFundListenerAuthParityIT : ListenerAuthParityConformance() {
    class ProductionProfile : ProductionListenerProfile()

    override val path: String = "/api/v1/funds"
}
