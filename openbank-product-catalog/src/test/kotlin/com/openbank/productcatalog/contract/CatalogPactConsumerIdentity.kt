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
    verify: () -> T,
): T {
    val original = association.testIdentity
    try {
        if (consumer == "openbank-pension-service") {
            association.setTestIdentity(pensionIdentity())
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
