// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.security

import io.quarkus.security.identity.AuthenticationRequestContext
import io.quarkus.security.identity.SecurityIdentity
import io.quarkus.security.identity.SecurityIdentityAugmentor
import io.quarkus.security.runtime.QuarkusSecurityIdentity
import io.smallrye.config.ConfigMapping
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.jwt.JsonWebToken

object CatalogRoles {
    const val READ = "CATALOG_SCOPE_READ"
    const val AUTHOR = "CATALOG_SCOPE_AUTHOR"
    const val PUBLISH = "CATALOG_SCOPE_PUBLISH"

    /** Derived marker: the principal holds at least one configured revision-approval authority. */
    const val REVISION_APPROVER = "CATALOG_REVISION_APPROVER"
}

/**
 * How the identity provider grants each revision-approval authority a pack policy names. The kernel
 * knows no authority by name: packs declare them, deployment configuration binds them, and an
 * authority with no binding can never be held (ADR-0257, #13429).
 */
@ConfigMapping(prefix = "openbank.catalog.approval")
interface CatalogApprovalSecurityConfig {
    /** Authority name -> the scope AND realm role a token must carry together to hold it. */
    fun authorities(): Map<String, AuthorityBinding>

    /** Machine client id (`azp`) -> its service-account username, allowed to READ approval evidence. */
    fun evidenceReaders(): Map<String, String>

    interface AuthorityBinding {
        fun scope(): String
        fun realmRole(): String
    }
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
    private val approval: CatalogApprovalSecurityConfig,
) {
    /** Every authority this deployment can grant; a human holding any of them may read the catalog. */
    val approvalAuthorities: Set<String> get() = approval.authorities().keys
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
            approval.authorities().forEach { (authority, binding) ->
                if (binding.scope() in scopes && binding.realmRole() in realmRoles) add(authority)
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
        val username = token.getClaim<String>("preferred_username").orEmpty()
        val name = token.name ?: username
        val humanApprover =
            token.getClaim<String>("azp") == "openbank-admin-ui" &&
                name.isNotBlank() &&
                !name.startsWith("service-account-") &&
                !name.startsWith("agent:") &&
                username.isNotBlank() &&
                !username.startsWith("service-account-") &&
                !username.startsWith("agent:") &&
                token.subject?.startsWith("agent:") != true &&
                roles.any { it in mapper.approvalAuthorities }
        if (humanApprover) {
            roles.add(CatalogRoles.READ)
            roles.add(CatalogRoles.REVISION_APPROVER)
        }
        if (roles.isEmpty()) return Uni.createFrom().item(identity)
        return Uni.createFrom().item(QuarkusSecurityIdentity.builder(identity).addRoles(roles).build())
    }
}
