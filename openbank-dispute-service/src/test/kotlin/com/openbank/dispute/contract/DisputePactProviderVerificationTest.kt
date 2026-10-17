// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.dispute.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import com.openbank.dispute.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith
import java.util.UUID
import javax.sql.DataSource

/**
 * Provider-side Pact verification for dispute-service — its FIRST provider-side contract class
 * of any kind (ADR-0063 git-pact: the pact JSON is read from the repo-root `pacts/` dir via
 * [PactFolder], no Pact Broker and no CI secret involved, so this always runs — locally and on
 * every path-scoped CI build of this module). Until now dispute only ever CONSUMED; nothing
 * replayed a consumer contract against its REST surface.
 *
 * Every interaction is replayed against a real Quarkus instance on the shared Testcontainers
 * Postgres (`PostgresTestResource`, the same resource every other dispute `@QuarkusTest`
 * declares — a dedicated second container set in the same JVM breaks the request session of the
 * app booted after it, see the clearing pact PR), so what is proven is the live HTTP + JSON
 * shape, not a hand-written restatement of it.
 *
 * Auth: this service's `authz.enforce` defaults to TRUE, and the `@Authorize` interceptor on
 * `DisputeResource` fails closed (503) without an OPA sidecar — hence
 * [DisputePactAuthzOffProfile], the same escape `ComplaintRevisionIT` uses. Class-level
 * [TestSecurity] then satisfies the resource's `@RolesAllowed` with the operator/API roles the
 * agent's charter maps to.
 *
 * IMPORTANT: if the consumer changes its contract, regenerate the pact
 * (`./gradlew :openbank-agent-service:test --tests "*AgentDisputeReadPactConsumerTest*"`) and
 * commit `pacts/openbank-agent-service-openbank-dispute-service.json` in the same PR, or this
 * test verifies a stale contract. `pact-drift-check.yml` gates that.
 *
 * Any `@State` added here MUST be added to [DisputePactBrokerProviderVerificationTest] too —
 * the broker serves every consumer's pact for this provider, and a missing state fails with
 * MissingStateChangeMethod and publishes a FAILURE, which blocks can-i-deploy on a healthy pair.
 */
@QuarkusTest
@TestProfile(DisputePactAuthzOffProfile::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestSecurity(user = "pact-verifier", roles = ["ROLE_API", "ROLE_OPERATOR"])
@Provider("openbank-dispute-service")
@PactFolder("../pacts")
class DisputePactProviderVerificationTest {

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

    @Inject
    lateinit var dataSource: DataSource

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

    /**
     * State: "a dispute exists for the pact agent".
     *
     * Seeded by [AgentDisputePactSeed] via direct JDBC — a `@State` callback carries no Vert.x
     * context and this service's writes go through reactive Panache (the documented HR000068
     * trap).
     */
    @State("a dispute exists for the pact agent")
    fun statePactDisputeExists() = AgentDisputePactSeed.seedPactDispute(dataSource)

    /**
     * State: "no dispute exists with the requested id".
     *
     * No seeding: the pact-pinned UUID (`abababab-…`) is never inserted by any state, so the
     * fresh container already satisfies it — the agent consumer's 404 fail-closed leg for
     * `dispute_get`.
     */
    @State("no dispute exists with the requested id")
    fun statePactUnknownDisputeNotFound() {
        // Assert the negative provider state before Pact replays its own 404 interaction.
        val missingId = UUID.fromString("abababab-abab-4aba-8aba-babababababa")
        io.restassured.RestAssured.given().get("/api/v1/disputes/$missingId").then().statusCode(404)
    }
}
