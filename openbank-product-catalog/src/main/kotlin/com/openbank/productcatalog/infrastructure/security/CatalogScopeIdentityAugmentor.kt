// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.security

import io.quarkus.security.identity.AuthenticationRequestContext
import io.quarkus.security.identity.SecurityIdentity
import io.quarkus.security.identity.SecurityIdentityAugmentor
import io.quarkus.security.runtime.QuarkusSecurityIdentity
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.jwt.JsonWebToken

object CatalogRoles {
    const val READ = "CATALOG_SCOPE_READ"
    const val AUTHOR = "CATALOG_SCOPE_AUTHOR"
    const val PUBLISH = "CATALOG_SCOPE_PUBLISH"
    const val PENSION_LEGAL_APPROVER = "PENSION_LEGAL_APPROVER"
    const val PENSION_PRODUCT_OWNER = "PENSION_PRODUCT_OWNER"
    const val PENSION_LEGAL_REALM_ROLE = "ROLE_PENSION_LEGAL_COUNSEL"
    const val PENSION_PRODUCT_REALM_ROLE = "ROLE_PENSION_PRODUCT_OWNER"
}

/** Maps provider-neutral OAuth scopes to stable internal roles without trusting a tenant claim or OPA. */
@ApplicationScoped
class CatalogScopeRoleMapper(
    @ConfigProperty(name = "openbank.catalog.security.read-scope", defaultValue = "catalog:read")
    private val readScope: String,
    @ConfigProperty(name = "openbank.catalog.security.author-scope", defaultValue = "catalog:author")
    private val authorScope: String,
    @ConfigProperty(name = "openbank.catalog.security.publish-scope", defaultValue = "catalog:publish")
    private val publishScope: String,
    @ConfigProperty(name = "openbank.catalog.security.pension-legal-scope", defaultValue = "pension:legal-approve")
    private val pensionLegalScope: String,
    @ConfigProperty(name = "openbank.catalog.security.pension-product-scope", defaultValue = "pension:product-approve")
    private val pensionProductScope: String,
) {
    fun roles(scopeClaim: Any?, realmRoles: Set<String> = emptySet()): Set<String> {
        val scopes = when (scopeClaim) {
            is String -> scopeClaim.split(' ').filter(String::isNotBlank).toSet()
            is Collection<*> -> scopeClaim.filterIsInstance<String>().toSet()
            else -> emptySet()
        }
        return buildSet {
            if (readScope in scopes) add(CatalogRoles.READ)
            if (authorScope in scopes) add(CatalogRoles.AUTHOR)
            if (publishScope in scopes) add(CatalogRoles.PUBLISH)
            if (pensionLegalScope in scopes && CatalogRoles.PENSION_LEGAL_REALM_ROLE in realmRoles) {
                add(CatalogRoles.PENSION_LEGAL_APPROVER)
            }
            if (pensionProductScope in scopes && CatalogRoles.PENSION_PRODUCT_REALM_ROLE in realmRoles) {
                add(CatalogRoles.PENSION_PRODUCT_OWNER)
            }
        }
    }
}

@ApplicationScoped
class CatalogScopeIdentityAugmentor(
    private val mapper: CatalogScopeRoleMapper,
    @ConfigProperty(name = "openbank.catalog.security.scope-claim", defaultValue = "scope")
    private val scopeClaim: String,
) : SecurityIdentityAugmentor {
    override fun augment(identity: SecurityIdentity, context: AuthenticationRequestContext): Uni<SecurityIdentity> {
        val token = identity.principal as? JsonWebToken ?: return Uni.createFrom().item(identity)
        val roles = mapper.roles(token.getClaim<Any?>(scopeClaim), identity.roles).toMutableSet()
        // Keycloak access tokens commonly omit upn; JsonWebToken.name can be null.
        val name = token.name ?: token.getClaim<String>("preferred_username").orEmpty()
        val humanApprover =
            name.isNotBlank() &&
                !name.startsWith("service-account-") &&
                !name.startsWith("agent:") &&
                token.subject?.startsWith("agent:") != true &&
                roles.any {
                    it == CatalogRoles.PENSION_LEGAL_APPROVER || it == CatalogRoles.PENSION_PRODUCT_OWNER
                }
        if (humanApprover) roles.add(CatalogRoles.READ)
        if (roles.isEmpty()) return Uni.createFrom().item(identity)
        return Uni.createFrom().item(QuarkusSecurityIdentity.builder(identity).addRoles(roles).build())
    }
}
