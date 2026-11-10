// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.integration

import com.openbank.libs.authz.OpaSidecarPolicyDecisionPoint
import com.openbank.libs.authz.PolicyDecisionPoint
import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.arc.ClientProxy
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Every `@Authorize` endpoint of this service resolves a `PolicyDecisionPoint` at request time.
 * With no such bean, `AuthorizeInterceptor` fails closed and the deployed service answered 503
 * "policy decision point not configured" on every statutory-return and filing route, while the
 * whole suite stayed green: the test profile sets `authz.enforce=false`, so no test ever asked
 * for a decision. This asserts the bean itself, which is what the deployed pod was missing.
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_tax_reporting_pdp_it")],
    restrictToAnnotatedClass = true,
)
class PolicyDecisionPointWiringIT {

    @Inject
    lateinit var pdp: Instance<PolicyDecisionPoint>

    @Test
    fun `the service binds the libs-runtime OPA sidecar policy decision point`() {
        assertThat(pdp.isResolvable)
            .describedAs("no PolicyDecisionPoint bean: every @Authorize route fails closed with 503")
            .isTrue()
        assertThat(ClientProxy.unwrap(pdp.get())).isInstanceOf(OpaSidecarPolicyDecisionPoint::class.java)
    }
}
