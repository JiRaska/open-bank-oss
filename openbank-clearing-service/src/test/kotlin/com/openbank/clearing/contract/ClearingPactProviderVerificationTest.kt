// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clearing.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import com.openbank.libs.testing.containers.PostgresRedpandaRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith
import java.util.UUID
import javax.sql.DataSource

/**
 * Provider-side Pact verification for clearing-service — its FIRST provider-side contract class
 * of any kind (ADR-0063 git-pact: the pact JSON is read from the repo-root `pacts/` dir via
 * [PactFolder], no Pact Broker and no CI secret involved, so this always runs — locally and on
 * every path-scoped CI build of this module). Until now clearing only ever CONSUMED
 * (ClearingLedgerPostJournalPactConsumerTest against ledger); nothing replayed a consumer
 * contract against its REST surface.
 *
 * Every interaction is replayed against a real Quarkus instance on Testcontainers
 * (Postgres + Redpanda + Redis — the stack the service boots against in every other
 * `@QuarkusTest`), so what is proven is the live HTTP + JSON shape, not a hand-written
 * restatement of it.
 *
 * Auth: `%test` disables OIDC and this service's `authz.enforce` defaults to `false` (advisory,
 * see application.yaml), so class-level [TestSecurity] with the roles the resource's
 * `@RolesAllowed` lists is sufficient — no fail-closed 503 trap the way sanctions-service has.
 *
 * IMPORTANT: if the consumer changes its contract, regenerate the pact
 * (`./gradlew :openbank-agent-service:test --tests "*AgentClearingReadPactConsumerTest*"`) and
 * commit `pacts/openbank-agent-service-openbank-clearing-service.json` in the same PR, or this
 * test verifies a stale contract. `pact-drift-check.yml` gates that.
 *
 * [IgnoreNoPactsToVerify] makes a missing/unreadable pact folder a skip rather than a failure —
 * relevant only if `pacts/` is ever emptied ahead of a broker migration.
 *
 * Any `@State` added here MUST be added to [ClearingPactBrokerProviderVerificationTest] too —
 * the broker serves every consumer's pact for this provider, and a missing state fails with
 * MissingStateChangeMethod and publishes a FAILURE, which blocks can-i-deploy on a healthy pair.
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresRedpandaRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_clearing_it")],
)
@TestSecurity(user = "pact-verifier", roles = ["ROLE_API", "ROLE_OPERATOR"])
@Provider("openbank-clearing-service")
@PactFolder("../pacts")
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
class ClearingPactProviderVerificationTest {

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
     * State: "a clearing batch with one item exists for the pact agent".
     *
     * Seeded by [AgentClearingPactSeed] via direct JDBC — a `@State` callback carries no Vert.x
     * context and this service's writes go through reactive Panache (the documented HR000068
     * trap). The batch is SETTLED so a settlement cycle can never pick it up mid-replay.
     */
    @State("a clearing batch with one item exists for the pact agent")
    fun statePactBatchExists() = AgentClearingPactSeed.seedPactBatch(dataSource)

    /**
     * State: "no clearing batch exists with the requested id".
     *
     * No seeding: the pact-pinned UUID (`abababab-…`) is never inserted by any state, so the
     * fresh container already satisfies it — the agent consumer's 404 fail-closed leg for
     * `clearing_get_batch`.
     */
    @State("no clearing batch exists with the requested id")
    fun statePactUnknownBatchNotFound() {
        // Assert the negative provider state before Pact replays its own 404 interaction.
        val missingId = UUID.fromString("abababab-abab-4aba-8aba-babababababa")
        io.restassured.RestAssured.given().get("/api/v1/clearing/batches/$missingId").then().statusCode(404)
    }
}
