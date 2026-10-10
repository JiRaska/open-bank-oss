// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactFilter
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import com.openbank.pension.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Git-pact provider replay for pension-service's reporting read model (#12425):
 * tax-reporting-service's consumer pact for `GET /api/v1/pension/reporting/participant-aggregates`,
 * replayed on every PR AS tax-reporting's own client — the identity pension_rest_ext.rego admits.
 * The 401 interaction is replayed by [PensionReportingNegativeAuthProviderVerificationTest].
 *
 * Filtered to tax-reporting's states only, so it never competes with another consumer's replay of
 * this provider. Until the consumer pact is committed (the stacked tax-reporting PR),
 * `@IgnoreNoPactsToVerify` keeps it green rather than absent.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestSecurity(user = "service-account-openbank-tax-reporting", roles = ["ROLE_API"])
@Provider("openbank-pension-service")
@PactFolder("../pacts")
@PactFilter(PensionPactFolderProviderVerificationTest.AGGREGATES_STATE)
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
class PensionPactFolderProviderVerificationTest {

    companion object {
        /** Must match TaxReportingPensionPactConsumerTest (openbank-tax-reporting-service). */
        const val AGGREGATES_STATE = "pension participant activity may exist for the third quarter of 2026"
    }

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

    @BeforeEach
    fun configureTarget(context: PactVerificationContext?) {
        context?.target = HttpTestTarget("localhost", testPort.toInt())
        context?.addStateChangeHandlers(this)
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verifyPacts(context: PactVerificationContext?) {
        context?.verifyInteraction()
    }

    @State(AGGREGATES_STATE)
    fun anyActivity() {
        // Intentionally no seed: an aggregate read answers 200 with the same SHAPE over an empty
        // book and a full one (zero counts and sums are figures, every age band is always
        // present), and the pact pins the shape, not the values. The exact values are pinned by
        // ParticipantReportingApiIT's golden month.
    }
}
