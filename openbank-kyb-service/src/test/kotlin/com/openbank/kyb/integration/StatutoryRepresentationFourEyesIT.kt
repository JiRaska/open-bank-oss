// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.integration

import com.openbank.kyb.application.usecase.SeparationOfDutiesException
import com.openbank.kyb.application.usecase.StatutoryRepresentationPublicationService
import com.openbank.libs.testing.containers.PostgresTestResource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.quarkus.security.runtime.QuarkusSecurityIdentity
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestIdentityAssociation
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.eclipse.microprofile.jwt.JsonWebToken
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Four-eyes on statutory-rule publication, over real HTTP: the authenticated principal is what
 * reaches the service as the publisher, and the separation-of-duties refusal answers 403.
 */
@QuarkusTest
@QuarkusTestResource(KybBootSmokeIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class StatutoryRepresentationFourEyesIT {

    @Inject
    lateinit var testIdentity: TestIdentityAssociation

    private val service = mockk<StatutoryRepresentationPublicationService>()
    private val caseId = UUID.randomUUID()
    private val attestationId = UUID.randomUUID()

    @BeforeEach
    fun installService() {
        coEvery { service.publish(caseId, attestationId, ATTESTER) } throws
            SeparationOfDutiesException("same person")
        coEvery { service.publish(caseId, attestationId, SECOND) } returns attestationId
        QuarkusMock.installMockForType(service, StatutoryRepresentationPublicationService::class.java)
    }

    @Test
    @TestSecurity(user = ATTESTER, roles = ["ROLE_KYC"])
    fun `the attester publishing their own rule is refused with 403`() {
        useStaffJwt(ATTESTER)
        publish().statusCode(403).body("error", equalTo("SEPARATION_OF_DUTIES"))
        coVerify(exactly = 1) { service.publish(caseId, attestationId, ATTESTER) }
    }

    @Test
    @TestSecurity(user = SECOND, roles = ["ROLE_KYC"])
    fun `a second staff member publishes the attested rule`() {
        useStaffJwt(SECOND)
        publish().statusCode(200).body("attestationId", equalTo(attestationId.toString()))
    }

    private fun publish() = Given {
        contentType("application/json")
        body("""{"attestationId":"$attestationId"}""")
    } When {
        post("/api/v1/kyb/cases/$caseId/statutory-representation")
    } Then {
        // assertions applied by the caller
    }

    private fun useStaffJwt(username: String) {
        val jwt = object : JsonWebToken {
            private val claims = mapOf(
                "sub" to "fixture-subject",
                "preferred_username" to username,
                "azp" to "openbank-admin-ui",
            )

            override fun getName(): String = username
            override fun getClaimNames(): Set<String> = claims.keys

            @Suppress("UNCHECKED_CAST")
            override fun <T : Any?> getClaim(claimName: String): T = claims[claimName] as T
        }
        testIdentity.setTestIdentity(QuarkusSecurityIdentity.builder().setPrincipal(jwt).addRole("ROLE_KYC").build())
    }

    private companion object {
        const val ATTESTER = "fixture-attesting-reviewer"
        const val SECOND = "fixture-publishing-reviewer"
    }
}
