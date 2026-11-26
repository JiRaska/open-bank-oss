// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp.infrastructure.oauth

import com.openbank.mcp.infrastructure.oauth.HostedOAuthAdmission.Vendor
import com.openbank.mcp.infrastructure.oauth.HostedOAuthStart.Companion.HANDLE_BYTES
import com.openbank.mcp.infrastructure.oauth.HostedOAuthStart.Companion.digest
import com.openbank.mcp.infrastructure.oauth.PgHostedVendorGrants.Identity
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

internal class HostedVendorTokens(val accessToken: String, val refreshToken: String) {
    val expiresIn: Int get() = ACCESS_EXPIRY_SECONDS

    private companion object {
        const val ACCESS_EXPIRY_SECONDS = 300
    }
    val scope: String get() = HostedOAuthAdmission.OUTER_SCOPE
}

/** Authority must fail closed and use live Delegation evidence instead of a positive cache. */
internal fun interface HostedActiveConnection {
    suspend fun isActive(vendor: Vendor, connectionId: UUID): Boolean
}

internal class HostedVendorGrants(
    private val store: PgHostedVendorGrants,
    private val authentication: HostedVendorAuthentication,
    private val connections: HostedActiveConnection,
    private val usableBankGrant: HostedActiveConnection,
    private val enabled: Set<Vendor> = emptySet(),
    private val registrations: HostedOAuthAdmission.Registrations = HostedOAuthAdmission.Registrations(),
) : HostedVendorGrantIssuer {
    private val random = SecureRandom()

    suspend fun issue(vendor: Vendor, connectionId: UUID): HostedVendorTokens? =
        issue(vendor, connectionId, vendor.clientId)

    override suspend fun issue(vendor: Vendor, connectionId: UUID, clientId: String): HostedVendorTokens? {
        val identity = Identity(vendor, connectionId, clientId)
        if (!active(identity)) return null
        val tokens = newTokens(identity.scopedClientId != null)
        store.create(identity, digest(tokens.accessToken), digest(tokens.refreshToken))
        return tokens
    }

    suspend fun refresh(
        token: String,
        credentials: HostedVendorAuthentication.Credentials,
        resource: String,
        scope: String?,
    ): HostedVendorTokens? {
        if (!REFRESH.matches(token) || resource != HostedOAuthAdmission.RESOURCE) return null
        if (scope != null && scope != HostedOAuthAdmission.OUTER_SCOPE) return null
        val vendor = authentication.authenticate(credentials) ?: return null
        val existing = store.refreshIdentity(digest(token), vendor, credentials.clientId) ?: return null
        if (vendor !in enabled || !usableBankGrant.isActive(vendor, existing.connectionId)) return null
        val tokens = newTokens(existing.scopedClientId != null)
        val rotated = store.rotate(digest(token), existing, digest(tokens.accessToken), digest(tokens.refreshToken))
        return tokens.takeIf { rotated }
    }

    /** Perform this check before tools are discovered or selected. Never pass this bearer upstream. */
    suspend fun admit(token: String): Identity? {
        if (!ACCESS.matches(token)) return null
        val identity = store.accessIdentity(digest(token)) ?: return null
        return identity.takeIf { active(it) }
    }

    suspend fun revoke(vendor: Vendor, connectionId: UUID) {
        store.revoke(Identity(vendor, connectionId))
    }

    private suspend fun active(identity: Identity): Boolean = identity.vendor in enabled &&
        registrations.find(identity.clientId)?.vendor == identity.vendor &&
        connections.isActive(identity.vendor, identity.connectionId)

    private fun newTokens(scoped: Boolean): HostedVendorTokens {
        val version = if (scoped) "2" else "1"
        return HostedVendorTokens("obat${version}_${randomValue()}", "obrt${version}_${randomValue()}")
    }

    private fun randomValue(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(HANDLE_BYTES).also(random::nextBytes))

    companion object {
        private val ACCESS = Regex("obat[12]_[A-Za-z0-9_-]{43}")
        private val REFRESH = Regex("obrt[12]_[A-Za-z0-9_-]{43}")
        val TOOLS = setOf("list_accounts", "get_balance")

        fun allowsTool(name: String): Boolean = name in TOOLS
    }
}
