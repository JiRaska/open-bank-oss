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
    fun `anonymous request still reaches the handler in advisory mode`() {
        // The test profile runs without enforced OIDC, so the request carries no identity
        // at all — the strictest input the interceptor can see. Advisory mode must not
        // 403/500 it; the handler's own not-found path answers.
        Given { this } When {
            get("/api/v1/consents/${UUID.randomUUID()}")
        } Then {
            statusCode(404)
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
