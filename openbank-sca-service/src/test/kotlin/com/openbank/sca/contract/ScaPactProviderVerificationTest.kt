// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactBroker
import com.openbank.sca.application.port.out.ScaChallengeRepository
import com.openbank.sca.domain.model.ScaChallenge
import com.openbank.sca.domain.model.ScaMethod
import com.openbank.sca.domain.model.ScaPurpose
import com.openbank.sca.domain.model.ScaStatus
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import jakarta.inject.Inject
import kotlinx.coroutines.runBlocking
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Provider-side verification for the SCA challenge GET contract published by consent-service
 * (ADR-0063 P2 Batch B). Seeds a PENDING CONSENT_GRANT challenge so that
 * GET /api/v1/sca/challenges/{id} returns 200 with the expected shape.
 *
 * The challenge UUID must match ConsentScaChallengePactConsumerTest exactly.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.sca.it.PostgresRedisTestResource::class)
@TestSecurity(user = "pact-verifier", roles = ["ROLE_SERVICE", "ROLE_OPERATOR"])
@Provider("openbank-sca-service")
@PactBroker
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
@EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
class ScaPactProviderVerificationTest {

    companion object {
        private val CHALLENGE_ID = UUID.fromString("99999999-9999-9999-9999-999999999999")
        private val PARTY_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    }

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

    @Inject
    lateinit var challengeRepo: ScaChallengeRepository

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

    @State("a PENDING SCA challenge exists")
    fun statePendingChallengeExists(): Unit = runBlocking {
        challengeRepo.save(
            ScaChallenge(
                id = CHALLENGE_ID,
                partyId = PARTY_ID,
                purpose = ScaPurpose.CONSENT_GRANT,
                method = ScaMethod.PUSH_NOTIFICATION,
                status = ScaStatus.PENDING,
                expiresAt = OffsetDateTime.now().plusMinutes(5),
                createdAt = OffsetDateTime.now(),
            ),
        )
        Unit
    }
}
