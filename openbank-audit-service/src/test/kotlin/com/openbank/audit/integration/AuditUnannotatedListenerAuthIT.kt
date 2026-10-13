// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.integration

import com.openbank.audit.it.PostgresTestResource
import com.openbank.libs.testing.mtls.ListenerAuthParityConformance
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.core.Response

/** A future unannotated audit route still requires the caller's bearer identity on both listeners. */
@QuarkusTest
@TestProfile(AuditListenerAuthParityIT.Profile::class)
@QuarkusTestResource(PostgresTestResource::class)
class AuditUnannotatedListenerAuthIT : ListenerAuthParityConformance() {
    override val path = "/api/v1/audit/listener-auth-probe"
}

/** Test-only route deliberately has no class or method security annotation. */
@Path("/api/v1/audit/listener-auth-probe")
class AuditListenerAuthProbe {
    @GET
    fun probe(): Response = Response.ok().build()
}
