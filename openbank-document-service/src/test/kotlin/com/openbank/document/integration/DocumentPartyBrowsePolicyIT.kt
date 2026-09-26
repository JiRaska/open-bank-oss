// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.integration

import com.openbank.document.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import org.junit.jupiter.api.Test

/**
 * The browse contract is POLICY-ALIGNED (#8082).
 *
 * `listByParty` carried only `@RolesAllowed`, while its by-id siblings `getDocument` and
 * `getContent` were `@Authorize`-gated. That asymmetry is the defect: a role check is not a policy
 * decision. It cannot see which party is being browsed, so the one endpoint that returns a party's
 * WHOLE document file was the one endpoint the policy decision point never saw.
 *
 * Proving an authorization decision is *reached* needs a decision that can go the other way. A
 * denying PDP with enforcement on is the only assertion that distinguishes "the interceptor
 * consulted the policy and it said no" from "there is no interceptor". A test that only exercises
 * the allow path is green whether or not `@Authorize` is present at all — which is precisely how
 * this endpoint shipped ungated past a full test suite.
 *
 * Falsification: against the pre-fix endpoint (no `@Authorize`) this returns 200, because nothing
 * asks the PDP anything. The sibling assertion below pins that the gate is on THIS endpoint rather
 * than the whole resource — an incidental class-level annotation would deny the templates route too.
 */
@QuarkusTest
@QuarkusTestResource(DocumentPartyBrowsePolicyIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresRedisTestResource::class)
@TestProfile(DocumentPartyBrowsePolicyIT.DenyingPolicyProfile::class)
class DocumentPartyBrowsePolicyIT {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("document-events-out")

        override fun stop() = InMemoryConnector.clear()
    }

    /**
     * Denies every policy decision, with `authz.enforce` pinned on. Enforcement is set explicitly
     * rather than relied upon: it resolves to true today via `${AUTHZ_ENFORCE:true}`, and a test
     * whose meaning depends on an unpinned default silently becomes vacuous the day the default
     * moves — it would then assert a 200 that proves nothing. Literals only; a profile loads in a
     * different classloader from the test class.
     */
    class DenyingPolicyProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "test.authz.allow" to "false",
            "authz.enforce" to "true",
        )
    }

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `a denied policy decision blocks the party browse with 403`() {
        // A well-formed request from a caller who passes the ROLE gate. The role check is not what
        // is being tested — it is what makes the 403 attributable to the policy decision, since
        // a role failure would have answered 403 at a different layer with no PDP call at all.
        Given {
            queryParam("partyRef", "party-1")
        } When {
            get("/api/v1/documents")
        } Then {
            statusCode(403)
        }
    }

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `the gate is on the browse endpoint, not blanket over the resource`() {
        // listTemplates is deliberately un-@Authorize'd: it returns the template catalogue, not any
        // party's data. If it also 403'd, the denial above would be evidence of a class-level gate
        // rather than of the annotation this issue is about.
        Given {
            queryParam("limit", 1)
        } When {
            get("/api/v1/documents/templates")
        } Then {
            statusCode(200)
        }
    }
}
