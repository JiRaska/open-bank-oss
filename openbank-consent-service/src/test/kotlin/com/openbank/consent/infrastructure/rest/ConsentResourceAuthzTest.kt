// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.consent.infrastructure.rest

import com.openbank.consent.it.ConsentPostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Regression coverage for the `@Authorize(action = "consent.*", ...)` additions
 * (ADR-0034 Phase 5, ADR-0126 D5, issue #263). `authz.enforce` defaults to `false`
 * (advisory) and no OPA sidecar runs in the test profile, so these assert the
 * interceptor is a correct no-op in that state — not that a real policy decision
 * is enforced (that is the shared `AuthorizeInterceptor`'s own suite in
 * openbank-libs-runtime, plus the decision assertions in
 * openbank-infra/gitops/components/consent/gen-consent-opa-bundle.sh).
 *
 * `ConsentResource` now carries a class-level `@RolesAllowed` (the coarse gate `@Authorize`
 * was always meant to pair with, per libs-domain's own docs) — an anonymous request is
 * rejected before it ever reaches the `@Authorize` interceptor.
 */
@QuarkusTest
@QuarkusTestResource(ConsentResourceAuthzTest.InMemoryKafkaResource::class)
@QuarkusTestResource(ConsentPostgresRedisTestResource::class)
class ConsentResourceAuthzTest {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("consent-events-out")

        override fun stop() = InMemoryConnector.clear()
    }

    @Test
    fun `anonymous request is rejected before it reaches the Authorize interceptor`() {
        // Previously this reached the handler (404) — the class had @Authorize but no
        // @RolesAllowed pairing, so JAX-RS never rejected an unauthenticated caller.
        Given { this } When {
            get("/api/v1/consents/${UUID.randomUUID()}")
        } Then {
            statusCode(401)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `advisory mode does not block an annotated read - request reaches the handler`() {
        // Unknown consent id -> the handler's own not-found path answers, proving the
        // @Authorize interceptor (advisory, no sidecar) did not short-circuit the call.
        Given { this } When {
            get("/api/v1/consents/${UUID.randomUUID()}")
        } Then {
            statusCode(404)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `advisory mode does not block an annotated write - validation still answers`() {
        // Missing request body -> the handler's own requireNotNull answers 400, proving
        // the consent.validate @Authorize check did not interfere with request validation.
        Given { contentType("application/json") } When {
            post("/api/v1/consents/${UUID.randomUUID()}/validate")
        } Then {
            statusCode(400)
        }
    }
}
