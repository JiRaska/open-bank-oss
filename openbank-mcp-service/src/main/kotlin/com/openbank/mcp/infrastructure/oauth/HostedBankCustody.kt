// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp.infrastructure.oauth

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.security.FieldProtector
import com.openbank.mcp.infrastructure.customer.CustomerCapabilities
import com.openbank.mcp.infrastructure.customer.CustomerGrantSource
import com.openbank.mcp.infrastructure.customer.GrantKey
import com.openbank.mcp.infrastructure.oauth.HostedBankCodeExchange.VerifiedConnection
import com.openbank.mcp.infrastructure.oauth.HostedOAuthAdmission.Vendor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.jose4j.jwa.AlgorithmConstraints
import org.jose4j.jws.JsonWebSignature
import java.nio.charset.StandardCharsets.UTF_8
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Customer realm signing keys are explicitly pinned by the deployment. */
internal class HostedBankJwtVerifier(
    private val mapper: ObjectMapper,
    keyPins: Map<String, RSAPublicKey>,
    private val clock: Clock,
) {
    private val pins = keyPins.toMap()

    init {
        require(pins.isNotEmpty() && pins.values.all { it.modulus.bitLength() >= MIN_RSA_BITS })
    }

    class OwnedTokens(
        val key: GrantKey,
        val access: String,
        val refresh: String,
        val accessExpiry: Instant,
        val refreshExpiry: Instant,
        val subject: String,
        val session: String,
        val reuseId: String,
    )

    fun validate(
        vendor: Vendor,
        thumbprint: String,
        tokens: JsonNode,
        allowExpiredAccess: Boolean = false,
    ): OwnedTokens? = runCatching {
        check(tokens.path("token_type").textValue()?.equals("DPoP", ignoreCase = true) == true)
        check(THUMBPRINT.matches(thumbprint))
        val access = requireNotNull(tokens.path("access_token").textValue())
        val refresh = requireNotNull(tokens.path("refresh_token").textValue())
        val now = clock.instant().epochSecond
        val accessClaims = verified(access, vendor, thumbprint, "DPoP", now, allowExpiredAccess)
        val refreshClaims = verified(refresh, vendor, thumbprint, "Refresh", now)
        check(audience(accessClaims.path("aud")))
        check(accessClaims.path("exp").longValue() - accessClaims.path("iat").longValue() <= MAX_ACCESS_SECONDS)
        check(accessClaims.path("sub").textValue() == refreshClaims.path("sub").textValue())
        check(accessClaims.path("sid").textValue() == refreshClaims.path("sid").textValue())
        val party = uuid(accessClaims.path("party_id").textValue())
        val connection = uuid(accessClaims.path("openbank_mcp_connection_id").textValue())
        OwnedTokens(
            GrantKey(party, vendor.bankClientId, thumbprint, connection),
            access,
            refresh,
            Instant.ofEpochSecond(accessClaims.path("exp").longValue()),
            Instant.ofEpochSecond(refreshClaims.path("exp").longValue()),
            accessClaims.path("sub").textValue(),
            accessClaims.path("sid").textValue(),
            requireNotNull(refreshClaims.path("reuse_id").textValue()).also { check(it.isNotBlank()) },
        )
    }.getOrNull()

    private fun verified(
        token: String,
        vendor: Vendor,
        thumbprint: String,
        type: String,
        now: Long,
        allowExpired: Boolean = false,
    ): JsonNode {
        check(token.length in 1..MAX_TOKEN_CHARS)
        val jws = JsonWebSignature().apply {
            setAlgorithmConstraints(AlgorithmConstraints(AlgorithmConstraints.ConstraintType.PERMIT, "RS256"))
            compactSerialization = token
            key = checkNotNull(pins[keyIdHeaderValue])
        }
        check(jws.verifySignature())
        val claims = mapper.readTree(jws.payload)
        check(claims.path("iss").textValue() == ISSUER && claims.path("azp").textValue() == vendor.bankClientId)
        check(claims.path("typ").textValue() == type && claims.path("cnf").path("jkt").textValue() == thumbprint)
        check(!claims.path("sub").textValue().isNullOrBlank() && !claims.path("sid").textValue().isNullOrBlank())
        val issued = time(claims, "iat")
        val expires = time(claims, "exp")
        check(issued in 1..now + CLOCK_SKEW_SECONDS && (allowExpired || expires > now) && expires > issued)
        check(!claims.has("nbf") || time(claims, "nbf") <= now)
        return claims
    }

    private fun time(claims: JsonNode, name: String): Long {
        val claim = claims.path(name)
        check(claim.isIntegralNumber && claim.canConvertToLong())
        return claim.longValue()
    }

    private fun audience(value: JsonNode): Boolean = value.textValue() == AUDIENCE ||
        (value.isArray && value.size() == 1 && value[0].textValue() == AUDIENCE)

    private fun uuid(value: String?): UUID = UUID.fromString(value).also { check(it.toString() == value) }

    private companion object {
        const val ISSUER = "https://kc.open-bank.tech/realms/openbank-customers"
        const val AUDIENCE = "openbank-mcp-service"
        const val MIN_RSA_BITS = 2048
        const val MAX_TOKEN_CHARS = 16_384
        const val MAX_ACCESS_SECONDS = 300
        const val CLOCK_SKEW_SECONDS = 5
        val THUMBPRINT = Regex("[A-Za-z0-9_-]{43}")
    }
}

internal interface HostedBankGrantStore {
    suspend fun put(vendor: Vendor, connectionId: UUID, sealed: String, expires: Instant): Boolean
    suspend fun get(vendor: Vendor, connectionId: UUID): String?
    suspend fun erase(vendor: Vendor, connectionId: UUID)
    suspend fun claim(vendor: Vendor, connectionId: UUID, operation: UUID): String?
    suspend fun replace(vendor: Vendor, connectionId: UUID, operation: UUID, sealed: String, expires: Instant): Boolean
    suspend fun release(vendor: Vendor, connectionId: UUID, operation: UUID)
    suspend fun discard(vendor: Vendor, connectionId: UUID, operation: UUID)
}

/** Rechecks the exact live grant tuple at custody and release. No token or customer identity is stored in clear. */
internal class HostedBankCustody(
    private val verifier: HostedBankJwtVerifier,
    private val authority: CustomerGrantSource,
    private val protector: FieldProtector,
    private val store: HostedBankGrantStore,
    private val mapper: ObjectMapper,
    private val clock: Clock,
) : HostedBankGrantVault {
    override suspend fun storeApproved(
        vendor: Vendor,
        dpopThumbprint: String,
        tokenResponse: JsonNode,
    ): VerifiedConnection? {
        val verified = verifier.validate(vendor, dpopThumbprint, tokenResponse) ?: return null
        val connection = requireNotNull(verified.key.connectionId)
        if (!active(verified.key)) return null
        val sealed = seal(vendor, connection, verified)
        return if (store.put(
                vendor,
                connection,
                sealed,
                verified.refreshExpiry,
            )
        ) {
            VerifiedConnection(connection, vendor)
        } else {
            null
        }
    }

    suspend fun loadActive(
        vendor: Vendor,
        connectionId: UUID,
        dpopThumbprint: String,
    ): HostedBankJwtVerifier.OwnedTokens? {
        val sealed = store.get(vendor, connectionId) ?: return null
        val verified = open(vendor, connectionId, dpopThumbprint, sealed)
        return verified?.takeIf { it.key.connectionId == connectionId && active(it.key) }
    }

    /**
     * Fence before calling Keycloak. A lost/failed response or process crash must never make a
     * possibly consumed refresh token reusable. The expiry job can remove abandoned claims.
     */
    suspend fun refreshActive(
        vendor: Vendor,
        connectionId: UUID,
        dpopThumbprint: String,
        bank: HostedBankRefreshExchange,
    ): Boolean {
        val operation = UUID.randomUUID()
        val sealed = store.claim(vendor, connectionId, operation) ?: return false
        var replaced = false
        var exchangeStarted = false
        try {
            val old = open(vendor, connectionId, dpopThumbprint, sealed, allowExpiredAccess = true)
                ?.takeIf { it.key.connectionId == connectionId && active(it.key) } ?: return false
            exchangeStarted = true
            val response = bank.refresh(vendor, dpopThumbprint, old.refresh) ?: return false
            val next = verifier.validate(vendor, dpopThumbprint, response) ?: return false
            if (!sameFamily(old, next) || !active(next.key)) return false
            val updated = seal(vendor, connectionId, next)
            replaced = store.replace(vendor, connectionId, operation, updated, next.refreshExpiry)
            return replaced
        } finally {
            if (!replaced) {
                withContext(NonCancellable) {
                    if (exchangeStarted) {
                        store.discard(vendor, connectionId, operation)
                    } else {
                        store.release(vendor, connectionId, operation)
                    }
                }
            }
        }
    }

    private fun sameFamily(old: HostedBankJwtVerifier.OwnedTokens, next: HostedBankJwtVerifier.OwnedTokens): Boolean =
        old.key == next.key &&
            old.subject == next.subject &&
            old.session == next.session &&
            old.reuseId == next.reuseId &&
            old.refresh != next.refresh

    private suspend fun open(
        vendor: Vendor,
        connection: UUID,
        thumbprint: String,
        sealed: String,
        allowExpiredAccess: Boolean = false,
    ): HostedBankJwtVerifier.OwnedTokens? {
        val clear = withContext(Dispatchers.IO) { protector.decrypt(sealed, aad(vendor, connection)) }
        return try {
            verifier.validate(vendor, thumbprint, mapper.readTree(clear), allowExpiredAccess)
        } finally {
            clear.fill(0)
        }
    }

    private suspend fun seal(vendor: Vendor, connection: UUID, tokens: HostedBankJwtVerifier.OwnedTokens): String {
        val document = mapper.writeValueAsBytes(
            mapOf("access_token" to tokens.access, "refresh_token" to tokens.refresh, "token_type" to "DPoP"),
        )
        return try {
            withContext(Dispatchers.IO) { protector.encrypt(document, aad(vendor, connection)) }
        } finally {
            document.fill(0)
        }
    }

    /** Call after authoritative bank revocation. Until deletion, every release still checks Delegation. */
    suspend fun eraseRevoked(vendor: Vendor, connectionId: UUID) = store.erase(vendor, connectionId)

    private suspend fun active(key: GrantKey): Boolean = withContext(Dispatchers.IO) {
        val now = clock.instant()
        authority.resolve(key).any {
            it.validTo?.isAfter(now) != false && it.allows(CustomerCapabilities.READ_BALANCES)
        }
    }

    private fun aad(vendor: Vendor, connection: UUID): ByteArray =
        "openbank-mcp-hosted-bank-tokens/v1\n${vendor.bankClientId}\n$connection".toByteArray(UTF_8)
}
