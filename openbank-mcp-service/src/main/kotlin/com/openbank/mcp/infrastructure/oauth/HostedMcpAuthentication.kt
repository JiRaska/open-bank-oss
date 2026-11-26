// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp.infrastructure.oauth

import io.quarkus.security.identity.IdentityProviderManager
import io.quarkus.security.identity.SecurityIdentity
import io.quarkus.security.identity.request.AuthenticationRequest
import io.quarkus.vertx.http.runtime.security.ChallengeData
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism
import io.quarkus.vertx.http.runtime.security.HttpCredentialTransport
import io.smallrye.mutiny.Uni
import io.vertx.ext.web.RoutingContext
import jakarta.enterprise.context.ApplicationScoped

/**
 * Selects the dedicated HTTP permission without assigning a Quarkus identity. The hosted resource
 * validates opaque OAuth credentials itself, including live bank grant admission.
 */
@ApplicationScoped
internal class HostedMcpAuthentication : HttpAuthenticationMechanism {
    override fun authenticate(context: RoutingContext, manager: IdentityProviderManager): Uni<SecurityIdentity> =
        Uni.createFrom().nullItem()

    override fun getChallenge(context: RoutingContext): Uni<ChallengeData> = Uni.createFrom().nullItem()

    override fun getCredentialTypes(): Set<Class<out AuthenticationRequest>> = emptySet()

    override fun getCredentialTransport(context: RoutingContext): Uni<HttpCredentialTransport> =
        if (context.normalizedPath() == HostedOAuthAdmission.RESOURCE_PATH) {
            Uni.createFrom().item(HttpCredentialTransport(HttpCredentialTransport.Type.AUTHORIZATION, "hosted-mcp"))
        } else {
            Uni.createFrom().nullItem()
        }
}
