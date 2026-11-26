// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to that license.

package com.openbank.mcp.infrastructure.customer

import io.quarkus.oidc.TenantResolver
import io.quarkus.oidc.runtime.OidcAuthenticationMechanism
import io.quarkus.security.identity.IdentityProviderManager
import io.quarkus.security.identity.SecurityIdentity
import io.quarkus.vertx.http.runtime.security.ChallengeData
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism
import io.quarkus.vertx.http.runtime.security.HttpCredentialTransport
import io.smallrye.mutiny.Uni
import io.vertx.ext.web.RoutingContext
import jakarta.enterprise.context.ApplicationScoped

private fun RoutingContext.isCustomerMcpPath(): Boolean = normalizedPath() == "/customer/mcp" ||
    normalizedPath().startsWith("/customer/mcp/")

@ApplicationScoped
class CustomerMcpTenantResolver : TenantResolver {
    override fun resolve(context: RoutingContext): String? {
        if (!context.isCustomerMcpPath()) return null
        val authorization = context.request().headers().getAll("Authorization").singleOrNull()
        return if (authorization?.startsWith("Bearer ", ignoreCase = true) == true) "customers-hosted" else "customers"
    }
}

/** Select only OIDC for this path. The TLS peer certificate remains a transport credential. */
@ApplicationScoped
class CustomerOidcAuthentication(private val oidc: OidcAuthenticationMechanism) : HttpAuthenticationMechanism {
    override fun authenticate(context: RoutingContext, manager: IdentityProviderManager): Uni<SecurityIdentity> =
        if (context.isCustomerMcpPath()) oidc.authenticate(context, manager) else Uni.createFrom().nullItem()

    override fun getChallenge(context: RoutingContext): Uni<ChallengeData> =
        if (context.isCustomerMcpPath()) oidc.getChallenge(context) else Uni.createFrom().nullItem()

    override fun getCredentialTypes() = oidc.credentialTypes

    override fun getCredentialTransport(context: RoutingContext): Uni<HttpCredentialTransport> =
        if (context.isCustomerMcpPath()) {
            Uni.createFrom().item(HttpCredentialTransport(HttpCredentialTransport.Type.AUTHORIZATION, "customer-oidc"))
        } else {
            Uni.createFrom().nullItem()
        }
}
