// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.contract

import au.com.dius.pact.provider.IConsumerInfo
import au.com.dius.pact.provider.junit5.PactVerificationContext
import com.openbank.libs.testing.containers.PostgresTestResource
import io.mockk.every
import io.mockk.mockk
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestIdentityAssociation
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

/** Exercises the broker verifier's real callback while avoiding a live Pact Broker dependency. */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_product_catalog_broker_callback")],
    restrictToAnnotatedClass = true,
)
@TestSecurity(user = "pact-verifier", roles = ["ROLE_OPERATOR", "CATALOG_SCOPE_READ"])
class ProductCatalogPactBrokerIdentityCallbackIT {
    @Inject
    lateinit var testIdentityAssociation: TestIdentityAssociation

    @Test
    fun `pension broker callback scopes the actual HTTP replay and restores identity`() {
        val originalIdentity = testIdentityAssociation.testIdentity
        val path = unknownPensionApprovalPath()

        // The broker verifier's class-level operator identity cannot read pension approvals.
        given().get(path).then().statusCode(403)

        val verifier = ProductCatalogPactBrokerProviderVerificationTest().apply {
            testIdentityAssociation = this@ProductCatalogPactBrokerIdentityCallbackIT.testIdentityAssociation
        }
        verifier.verifyPacts(
            pensionContext {
                // This is a real request into the running Quarkus endpoint, through the exact
                // verifyPacts callback changed by the candidate; 404 proves auth passed the gate.
                given().get(path).then().statusCode(404)
            },
        )

        assertThat(testIdentityAssociation.testIdentity).isSameAs(originalIdentity)
    }

    @Test
    fun `pension broker callback restores identity when interaction verification throws`() {
        val originalIdentity = testIdentityAssociation.testIdentity
        val verifier = ProductCatalogPactBrokerProviderVerificationTest().apply {
            testIdentityAssociation = this@ProductCatalogPactBrokerIdentityCallbackIT.testIdentityAssociation
        }
        val context = pensionContext {
            throw IllegalStateException("synthetic broker interaction failure")
        }

        assertThrows<IllegalStateException> { verifier.verifyPacts(context) }
        assertThat(testIdentityAssociation.testIdentity).isSameAs(originalIdentity)
    }

    private fun pensionContext(verify: () -> Unit): PactVerificationContext {
        val consumer = mockk<IConsumerInfo>()
        every { consumer.name } returns "openbank-pension-service"
        val context = mockk<PactVerificationContext>()
        every { context.consumer } returns consumer
        every { context.verifyInteraction() } answers { verify() }
        return context
    }

    private fun unknownPensionApprovalPath(): String =
        "/api/v2/offerings/${UUID.randomUUID()}/revisions/${UUID.randomUUID()}/pension-approvals"
}
