// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.integration

import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.libs.testing.mtls.ListenerAuthParityConformance
import com.openbank.libs.testing.mtls.ProductionListenerProfile
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile

@QuarkusTest
@TestProfile(KybListenerAuthParityIT.Profile::class)
@QuarkusTestResource(KybBootSmokeIT.InMemoryKafkaResource::class)
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_kyb_listener_it")],
    restrictToAnnotatedClass = true,
)
class KybListenerAuthParityIT : ListenerAuthParityConformance() {
    override val path = "/api/v1/kyb/cases/00000000-0000-4000-8000-00000000c0de"
    override val authenticatedStatus = 404

    class Profile : ProductionListenerProfile()
}
