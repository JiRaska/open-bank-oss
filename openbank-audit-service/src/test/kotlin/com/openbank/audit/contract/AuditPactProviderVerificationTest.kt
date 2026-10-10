// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactFilter
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import com.openbank.audit.domain.model.AuditEntry
import com.openbank.audit.domain.model.OccurredAtSource
import com.openbank.audit.infrastructure.persistence.AuditRepository
import com.openbank.audit.integration.AuditAuthzWiringIT
import com.openbank.audit.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith
import java.time.Instant
import java.util.UUID

/**
 * Replays consumer pacts naming openbank-audit-service (ADR-0063 git-pact) — today lending's
 * evidence-bundle read (#11900). Runs on every PR: `@PactFolder`, no broker gate. The 401 twin is
 * `AuditNegativeAuthProviderVerificationTest`, which boots without an identity.
 *
 * Shares `AuditAuthzWiringIT.EnforcedProfile` (enforced authz + a stub OPA granting the evidence
 * route) so the replay costs no extra Quarkus boot.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(AuditAuthzWiringIT.EnforcedProfile::class)
@TestSecurity(user = "pact-credit-risk-person", roles = ["ROLE_CREDIT_RISK"])
@Provider("openbank-audit-service")
@PactFolder("../pacts")
@PactFilter("^(?!" + NEGATIVE_AUTH_STATE + "\$).*\$")
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
class AuditPactProviderVerificationTest {

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

    @Inject
    lateinit var repository: AuditRepository

    @BeforeEach
    fun configureTarget(context: PactVerificationContext?) {
        if (context == null) return
        context.target = HttpTestTarget("localhost", testPort.toInt())
        context.addStateChangeHandlers(this)
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verifyPacts(context: PactVerificationContext?) {
        context?.verifyInteraction()
    }

    @State(EVIDENCE_STATE)
    fun evidenceExists() = AuditPactStates.seedEvidence(repository)
}

const val NEGATIVE_AUTH_STATE = "no valid caller identity is presented"
const val EVIDENCE_STATE = "the audit chain holds evidence for the pact loan application"

/** State seeding shared by the folder- and broker-sourced verifiers, so the two cannot drift. */
object AuditPactStates {
    /** Must equal PACT_APPLICATION_ID in lending's AuditEvidencePactConsumerTest. */
    const val PACT_APPLICATION_ID = "d7d7d7d7-d7d7-4d7d-8d7d-d7d7d7d7d7d7"

    fun seedEvidence(repository: AuditRepository) {
        // Idempotent: pact-jvm invokes a state setup callback twice per interaction.
        if (onEventLoop { repository.evidenceFor(PACT_APPLICATION_ID) }.entries.isNotEmpty()) return
        val entry = AuditEntry(
            id = UUID.randomUUID(),
            eventType = "lending.application.submitted",
            aggregateType = "LOAN_APPLICATION",
            aggregateId = PACT_APPLICATION_ID,
            actorId = "pact-applicant",
            actorType = "HUMAN",
            payload = """{"aggregateId":"$PACT_APPLICATION_ID"}""",
            sourceService = "lending-service",
            correlationId = PACT_APPLICATION_ID,
            occurredAt = Instant.parse("2026-09-01T10:00:00Z"),
            recordedAt = Instant.parse("2026-09-01T10:00:01Z"),
            occurredAtSource = OccurredAtSource.EVENT,
        )
        onEventLoop { repository.save(entry) }
    }

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }
}
