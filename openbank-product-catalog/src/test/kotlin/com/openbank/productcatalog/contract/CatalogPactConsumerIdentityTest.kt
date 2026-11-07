// SPDX-License-Identifier: Apache-2.0
package com.openbank.productcatalog.contract

import io.mockk.every
import io.mockk.mockk
import io.quarkus.security.identity.SecurityIdentity
import io.quarkus.security.runtime.QuarkusSecurityIdentity
import io.quarkus.test.security.TestIdentityAssociation
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.jwt.JsonWebToken
import org.junit.jupiter.api.Test

class CatalogPactConsumerIdentityTest {
    private val operator = QuarkusSecurityIdentity.builder()
        .setPrincipal { "pact-verifier" }
        .addRole("ROLE_OPERATOR")
        .build()
    private var current: SecurityIdentity = operator
    private val association = mockk<TestIdentityAssociation> {
        every { testIdentity } answers { current }
        every { setTestIdentity(any()) } answers {
            current = firstArg()
            self as TestIdentityAssociation
        }
    }

    @Test
    fun pensionGetsOnlyConfiguredMachineReaderIdentityAndRestoresOperator() {
        withCatalogPactConsumerIdentity(association, "openbank-pension-service") {
            val token = current.principal as JsonWebToken
            assertThat(token.getClaim<String>("azp")).isEqualTo("openbank-pension")
            assertThat(token.getClaim<String>("preferred_username")).isEqualTo("service-account-openbank-pension")
            assertThat(current.roles).containsExactlyInAnyOrder("ROLE_API", "CATALOG_SCOPE_READ")
        }
        assertThat(current).isSameAs(operator)
    }

    @Test
    fun unauthorizedConsumerDoesNotGainMachineReaderIdentity() {
        withCatalogPactConsumerIdentity(association, "unrelated-service") {
            assertThat(current).isSameAs(operator)
            assertThat(current.hasRole("ROLE_API")).isFalse()
        }
    }

    @Test
    fun failedVerificationCannotLeakMachineIdentityIntoAnotherConsumer() {
        assertThatThrownBy {
            withCatalogPactConsumerIdentity(association, "openbank-pension-service") {
                error("verification failed")
            }
        }.isInstanceOf(IllegalStateException::class.java)
        assertThat(current).isSameAs(operator)
    }
}
