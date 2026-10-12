// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.integration

import com.openbank.libs.testing.containers.PostgresRedpandaRedisTestResource
import com.openbank.libs.testing.mtls.ListenerAuthParityConformance
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.core.Response

/**
 * #12511: a new, unannotated API route must still require a bearer on both listeners.
 * The production listener/auth settings come from application.yaml via ProductionListenerProfile.
 */
@QuarkusTest
@TestProfile(AccountListenerAuthParityIT.Profile::class)
@QuarkusTestResource(
    value = PostgresRedpandaRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_accounts_listener_it")],
)
class AccountUnannotatedListenerAuthIT : ListenerAuthParityConformance() {
    override val path = "/api/v1/account-listener-auth-probe"
}

/** Test-only route deliberately lacking both method and class security annotations. */
@Path("/api/v1/account-listener-auth-probe")
class AccountListenerAuthProbe {
    @GET
    fun probe(): Response = Response.ok().build()
}
