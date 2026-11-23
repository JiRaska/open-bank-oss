// SPDX-License-Identifier: Apache-2.0
package com.openbank.productcatalog.contract

import io.mockk.every
import io.mockk.mockk
import io.quarkus.security.runtime.QuarkusSecurityIdentity
import io.quarkus.test.security.TestIdentityAssociation
import org.eclipse.microprofile.jwt.JsonWebToken

/**
 * Both folder and broker replays use the scoped service identity of the consumer.
 * Real token authentication and authorization remain covered by CatalogPensionOidcApprovalIT.
 */
fun <T> withCatalogPactConsumerIdentity(
    association: TestIdentityAssociation,
    consumer: String?,
    interaction: String? = null,
    verify: () -> T,
): T {
    val original = association.testIdentity
    try {
        if (consumer == "openbank-pension-service") {
            association.setTestIdentity(pensionIdentity())
        }
        // The internal passport admission resource pins the account-service client by azp and
        // preferred_username (403 otherwise); only those interactions carry that machine identity.
        if (consumer == "openbank-account-service" && interaction?.startsWith("POST passport admission") == true) {
            association.setTestIdentity(accountIdentity())
        }
        return verify()
    } finally {
        association.setTestIdentity(original)
    }
}

private fun pensionIdentity(): QuarkusSecurityIdentity {
    val token = mockk<JsonWebToken>()
    every { token.name } returns "service-account-openbank-pension"
    every { token.subject } returns "pact-pension-service"
    every { token.getClaim<String>("azp") } returns "openbank-pension"
    every { token.getClaim<String>("preferred_username") } returns "service-account-openbank-pension"
    return QuarkusSecurityIdentity.builder()
        .setPrincipal(token)
        .addRole("ROLE_API")
        .addRole("CATALOG_SCOPE_READ")
        .build()
}

private fun accountIdentity(): QuarkusSecurityIdentity {
    val token = mockk<JsonWebToken>()
    every { token.name } returns "service-account-openbank-account"
    every { token.subject } returns "pact-account-service"
    every { token.getClaim<String>("azp") } returns "openbank-account"
    every { token.getClaim<String>("preferred_username") } returns "service-account-openbank-account"
    return QuarkusSecurityIdentity.builder()
        .setPrincipal(token)
        .addRole("ROLE_API")
        .build()
}
