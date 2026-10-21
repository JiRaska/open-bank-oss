// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.infrastructure.security

import io.quarkus.security.identity.AuthenticationRequestContext
import io.quarkus.security.identity.SecurityIdentity
import io.quarkus.security.identity.SecurityIdentityAugmentor
import io.quarkus.security.runtime.QuarkusSecurityIdentity
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.jwt.JsonWebToken

object FxRoles {
    const val READ = "FX_SCOPE_READ"
}

/**
 * Maps the provider-neutral `fx:read` OAuth scope to [FxRoles.READ] without trusting a tenant
 * claim or OPA. This is the ONLY path to FX_SCOPE_READ: the role is deliberately NOT declared
 * in the realm, so it cannot be assigned to a human in the admin console — an operator with
 * realm access must not be able to mint a token carrying FX_SCOPE_READ without holding the
 * fx:read scope on a client (the same reasoning as product-catalog's
 * CatalogScopeIdentityAugmentor, #7324). The only client that lists the scope is the
 * synthetic-only `openbank-synthetic-fx-read`, whose credential lives in Vault
 * (ADR-0099 `openbank/keycloak/synthetic-fx-read`, runbook 0009) and whose token therefore
 * carries the journey's read-only identity.
 */
@ApplicationScoped
class FxScopeRoleMapper(
    @ConfigProperty(name = "openbank.fx.security.read-scope", defaultValue = "fx:read")
    private val readScope: String,
) {
    fun roles(scopeClaim: Any?): Set<String> {
        val scopes = when (scopeClaim) {
            is String -> scopeClaim.split(' ').filter(String::isNotBlank).toSet()
            is Collection<*> -> scopeClaim.filterIsInstance<String>().toSet()
            else -> emptySet()
        }
        return buildSet {
            if (readScope in scopes) add(FxRoles.READ)
        }
    }
}

@ApplicationScoped
class FxScopeIdentityAugmentor(
    private val mapper: FxScopeRoleMapper,
    @ConfigProperty(name = "openbank.fx.security.scope-claim", defaultValue = "scope")
    private val scopeClaim: String,
) : SecurityIdentityAugmentor {
    override fun augment(identity: SecurityIdentity, context: AuthenticationRequestContext): Uni<SecurityIdentity> {
        val token = identity.principal as? JsonWebToken ?: return Uni.createFrom().item(identity)
        val roles = mapper.roles(token.getClaim<Any?>(scopeClaim))
        if (roles.isEmpty()) return Uni.createFrom().item(identity)
        return Uni.createFrom().item(QuarkusSecurityIdentity.builder(identity).addRoles(roles).build())
    }
}
