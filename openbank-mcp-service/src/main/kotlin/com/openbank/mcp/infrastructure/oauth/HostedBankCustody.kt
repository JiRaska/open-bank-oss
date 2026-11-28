// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp.infrastructure.oauth

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.domain.identifiers.Ids
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
    )

    /** Only call for a response from the authenticated, pinned Keycloak token endpoint. */
    fun validateResponse(vendor: Vendor, thumbprint: String, tokens: JsonNode): OwnedTokens? =
        validate(vendor, thumbprint, tokens, allowExpiredAccess = false) { issued ->
            // Anchor the bank's relative online refresh lifetime to its signed access issue time.
            Math.addExact(issued, time(tokens, "refresh_expires_in"))
        }

    /** The absolute deadline comes from the custody row, never from refresh claims. */
    fun validateStored(
        vendor: Vendor,
        thumbprint: String,
        tokens: JsonNode,
        refreshExpiry: Instant,
        allowExpiredAccess: Boolean,
    ): OwnedTokens? = validate(vendor, thumbprint, tokens, allowExpiredAccess) { refreshExpiry.epochSecond }

    private fun validate(
        vendor: Vendor,
        thumbprint: String,
        tokens: JsonNode,
        allowExpiredAccess: Boolean,
        refreshDeadline: (Long) -> Long,
    ): OwnedTokens? = runCatching {
        check(tokens.path("token_type").textValue()?.equals("DPoP", ignoreCase = true) == true)
        check(THUMBPRINT.matches(thumbprint))
        val access = requireNotNull(tokens.path("access_token").textValue())
        // Keycloak signs online refresh tokens with its internal symmetric key. Only Keycloak
        // validates them. The facade holds the returned credential opaque and encrypted.
        val refresh = requireNotNull(tokens.path("refresh_token").textValue())
        check(refresh != access && refresh.length in 1..MAX_TOKEN_CHARS && refresh.all { it in '!'..'~' })
        val now = clock.instant().epochSecond
        val accessClaims = verified(access, vendor, thumbprint, now, allowExpiredAccess)
        val issued = time(accessClaims, "iat")
        val accessExpires = time(accessClaims, "exp")
        val refreshExpires = refreshDeadline(issued)
        check(refreshExpires > now && refreshExpires in (issued + 1)..(issued + MAX_ONLINE_REFRESH_SECONDS))
        check(audience(accessClaims.path("aud")))
        check(accessExpires - issued <= MAX_ACCESS_SECONDS)
        val party = uuid(accessClaims.path("party_id").textValue())
        val connection = uuid(accessClaims.path("openbank_mcp_connection_id").textValue())
        OwnedTokens(
            GrantKey(party, vendor.bankClientId, thumbprint, connection),
            access,
            refresh,
            Instant.ofEpochSecond(accessExpires),
            Instant.ofEpochSecond(refreshExpires),
            accessClaims.path("sub").textValue(),
            accessClaims.path("sid").textValue(),
        )
    }.getOrNull()

    private fun verified(
        token: String,
        vendor: Vendor,
        thumbprint: String,
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
        check(claims.path("typ").textValue() == "DPoP" && claims.path("cnf").path("jkt").textValue() == thumbprint)
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
        const val MAX_ONLINE_REFRESH_SECONDS = 36_000 // Customer realm SSO session maximum.
        const val CLOCK_SKEW_SECONDS = 5
        val THUMBPRINT = Regex("[A-Za-z0-9_-]{43}")
    }
}

internal interface HostedBankGrantStore {
    class Record(val sealed: String, val expiresAt: Instant)

    suspend fun put(vendor: Vendor, connectionId: UUID, sealed: String, expires: Instant): Boolean
    suspend fun get(vendor: Vendor, connectionId: UUID): Record?
    suspend fun erase(vendor: Vendor, connectionId: UUID)
    suspend fun claim(vendor: Vendor, connectionId: UUID, operation: UUID): Record?
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
        val verified = verifier.validateResponse(vendor, dpopThumbprint, tokenResponse) ?: return null
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
        val record = store.get(vendor, connectionId) ?: return null
        val verified = open(vendor, connectionId, dpopThumbprint, record)
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
        val operation = Ids.randomId()
        val record = store.claim(vendor, connectionId, operation) ?: return false
        var replaced = false
        var exchangeStarted = false
        try {
            val old = open(vendor, connectionId, dpopThumbprint, record, allowExpiredAccess = true)
                ?.takeIf { it.key.connectionId == connectionId && active(it.key) } ?: return false
            exchangeStarted = true
            val response = bank.refresh(vendor, dpopThumbprint, old.refresh) ?: return false
            val next = verifier.validateResponse(vendor, dpopThumbprint, response) ?: return false
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

    // Keycloak's refresh response hook attests the same active family by stamping its connection.
    // The newly signed access must independently prove the same subject, session and exact grant.
    private fun sameFamily(old: HostedBankJwtVerifier.OwnedTokens, next: HostedBankJwtVerifier.OwnedTokens): Boolean =
        old.key == next.key &&
            old.subject == next.subject &&
            old.session == next.session &&
            old.refresh != next.refresh

    private suspend fun open(
        vendor: Vendor,
        connection: UUID,
        thumbprint: String,
        record: HostedBankGrantStore.Record,
        allowExpiredAccess: Boolean = false,
    ): HostedBankJwtVerifier.OwnedTokens? {
        val clear = withContext(Dispatchers.IO) { protector.decrypt(record.sealed, aad(vendor, connection)) }
        return try {
            verifier.validateStored(vendor, thumbprint, mapper.readTree(clear), record.expiresAt, allowExpiredAccess)
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
