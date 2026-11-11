// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.contract

import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import com.openbank.kyb.integration.KybBootSmokeIT
import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest

@QuarkusTest
@QuarkusTestResource(KybBootSmokeIT.InMemoryKafkaResource::class)
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_kyb_statutory_pact")],
    restrictToAnnotatedClass = true,
)
@Provider("openbank-kyb-service")
@PactFolder("../pacts")
class KybStatutoryPactFolderProviderVerificationTest : KybStatutoryPactVerification() {
    @State(KYB_CURRENT_STATE, KYB_STALE_STATE)
    fun delegation() = currentEvidenceAs("openbank-delegation")

    @State(KYB_REFUSED_STATE)
    fun sharedBackend() = currentEvidenceAs("openbank-services")
}
