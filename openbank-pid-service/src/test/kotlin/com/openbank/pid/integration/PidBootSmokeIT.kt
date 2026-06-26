// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.pid.integration

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.http.ContentType
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Boot smoke test guarding the "released-but-never-booted" defect class.
 *
 * pid-service is a released component (version.txt) with no GitOps deployment and previously had
 * zero @QuarkusTest, so boot/config defects could only surface in production. The same class bit
 * psd2-service (#1163 missing runtime DB extensions, #1170 a duplicate YAML key dropping the HTTP
 * port). This IT boots the full app on a Testcontainers Postgres, runs Flyway, and asserts the
 * readiness probe is UP and the service-info endpoint answers — the two signals that prove the
 * wiring, config and migrations survive a real boot. Mirrors clearing/interest/sdd's per-job
 * Testcontainers IT (issue #578).
 *
 * pid wires TWO outgoing Kafka emitters — `@Channel("party-events-out")` and the outbox dispatcher's
 * `@Channel("pid-events-out")` — both swapped to the in-memory connector so no broker is needed and
 * the readiness probe carries no Kafka health check.
 */
@QuarkusTest
@QuarkusTestResource(PidBootSmokeIT.InMemoryKafkaResource::class)
@QuarkusTestResource(com.openbank.pid.it.PostgresTestResource::class)
class PidBootSmokeIT {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("party-events-out", "pid-events-out")

        override fun stop() = InMemoryConnector.clear()
    }

    @Test
    fun `the app boots and the readiness probe reports UP`() {
        Given { this } When { get("/q/health/ready") } Then { statusCode(200) }
    }

    @Test
    fun `the service-info endpoint answers on the configured HTTP port`() {
        val body = (Given { this } When { get("/api/v1/info") } Then { statusCode(200) }).extract().body().asString()
        assertThat(body).contains("openbank-pid-service")
    }

    /**
     * Boot smoke is necessary but not sufficient: health/info never open a Hibernate Reactive
     * session, so the whole persistence layer can be dead while the probes stay green. That is
     * exactly what shipped — every DB-touching endpoint returned 422 "No current Mutiny.Session"
     * because the repository never wrapped its Panache calls (#1308), and the identity endpoints
     * 403'd because pid required the wrong role (#1301). Neither defect is observable without an
     * authenticated request that actually reaches the DB. This drives `/resolve` end-to-end on the
     * empty Testcontainers schema: a namesake query (no RČ ⇒ tier-2 match-key path) opens a session,
     * runs a real query, and must resolve to `NO_MATCH`. @TestSecurity supplies an M2M-equivalent
     * `ROLE_OPERATOR`, so a 200 here also guards the RBAC contract the live M2M token depends on.
     */
    @Test
    @TestSecurity(user = "boot-smoke", roles = ["ROLE_OPERATOR"])
    fun `resolve reaches the database and returns a decision — guards the reactive session and RBAC`() {
        val body = (
            Given {
                contentType(ContentType.JSON)
                body("""{"givenName":"Boot","familyName":"Smoke","birthdate":"1990-01-01"}""")
            } When {
                post("/api/v1/parties/resolve")
            } Then {
                statusCode(200)
            }
            ).extract().body().asString()
        assertThat(body).contains("NO_MATCH")
    }
}
