// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.infrastructure.rest

import com.openbank.libs.authz.Authorize
import jakarta.annotation.security.RolesAllowed
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * #10486 batch 6: `GET /api/v1/parties/{id}` admits ROLE_API so delegation-service can read a party
 * as its OWN principal. ROLE_API is held by every service account, so the endpoint must carry an OPA
 * action (enforced in pid-service) that grants it by identity — a bare ROLE_API with no @Authorize
 * would open it to all of them. The same holds for every PartyResource endpoint that admits ROLE_API.
 */
class PartyReadAuthorizationTest {

    @Test
    fun `getById admits ROLE_API and carries the party read action`() {
        val m = PartyResource::class.java.declaredMethods.first { it.name == "getById" }
        assertThat(m.getAnnotation(RolesAllowed::class.java).value).contains("ROLE_API")
        val authorize = m.getAnnotation(Authorize::class.java)
        assertThat(authorize).isNotNull
        assertThat(authorize.action).isEqualTo("party.read")
        assertThat(authorize.resource).isEqualTo("#id")
    }

    @Test
    fun `every PartyResource endpoint that admits ROLE_API is decided by OPA`() {
        PartyResource::class.java.declaredMethods
            .filter { it.getAnnotation(RolesAllowed::class.java)?.value?.contains("ROLE_API") == true }
            .forEach { m ->
                assertThat(m.getAnnotation(Authorize::class.java)).describedAs(m.name).isNotNull
            }
    }
}
