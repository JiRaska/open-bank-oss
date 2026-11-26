// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.webauthn

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.ObjectMapper
import com.webauthn4j.util.Base64UrlUtil
import com.webauthn4j.verifier.exception.VerificationException
import io.quarkus.redis.datasource.RedisDataSource
import io.quarkus.redis.datasource.value.SetArgs
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

/** Supplied by bank authority state and the authenticated app identity, never by the wire DTO. */
internal data class McpNativePasskeyBinding(
    val requestRef: String,
    val partyId: UUID,
    val keycloakUserId: String,
    val clientId: String,
    val parJkt: String,
    val contextSha256: String,
    val expiresAtEpochSecond: Long,
) {
    init {
        require(REFERENCE.matches(requestRef) && REFERENCE.matches(contextSha256))
        require(PAR_THUMBPRINT.matches(parJkt))
        require(keycloakUserId.isNotBlank() && keycloakUserId.length <= MAX_ID_LENGTH)
        require(clientId.isNotBlank() && clientId.length <= MAX_ID_LENGTH)
    }

    private companion object {
        const val MAX_ID_LENGTH = 255
        val REFERENCE = Regex("[0-9a-f]{64}")
        val PAR_THUMBPRINT = Regex("[A-Za-z0-9_-]{43}")
    }
}

/** In-process evidence for the bank authority; the client never receives this object. */
internal data class McpNativePasskeyProof(
    val binding: McpNativePasskeyBinding,
    val credentialReference: String,
    val firstVerifiedAtEpochMillis: Long,
    val assertedAtEpochMillis: Long,
)

@ApplicationScoped
internal class McpNativePasskeyChallengeStore(redis: RedisDataSource, private val mapper: ObjectMapper) {
    private val values = redis.value(String::class.java)

    fun save(challenge: String, binding: McpNativePasskeyBinding) {
        values.set(key(challenge), mapper.writeValueAsString(binding), SetArgs().ex(ChallengeStore.TTL_SECONDS))
    }

    fun claim(challenge: String): McpNativePasskeyBinding? = values.getdel(key(challenge))?.let {
        mapper.readValue(it, McpNativePasskeyBinding::class.java)
    }

    private fun key(challenge: String) = "edge:webauthn:mcp-connect:$challenge"
}

/** Verifies an exact Connect assertion without minting or refreshing any customer session. */
@ApplicationScoped
internal class McpNativePasskeyProofService(
    private val challenges: McpNativePasskeyChallengeStore,
    private val credentials: WebAuthnStore,
    private val mapper: ObjectMapper,
    private val clock: Clock,
    @ConfigProperty(name = "openbank.webauthn.rp-id", defaultValue = "open-bank.tech")
    private val rpId: String,
    @ConfigProperty(name = "openbank.webauthn.origin", defaultValue = "https://open-bank.tech")
    private val origin: String,
) {
    private val random = SecureRandom()

    fun begin(binding: McpNativePasskeyBinding): AuthenticationChallengeDto? {
        if (binding.expiresAtEpochSecond <= clock.instant().epochSecond) return null
        val challenge = Base64UrlUtil.encodeToString(ByteArray(CHALLENGE_BYTES).also(random::nextBytes))
        challenges.save(challenge, binding)
        return AuthenticationChallengeDto(challenge, rpId)
    }

    fun complete(binding: McpNativePasskeyBinding, assertion: AuthCompleteRequestDto): McpNativePasskeyProof? {
        if (binding.expiresAtEpochSecond <= clock.instant().epochSecond) return null
        val challenge = verifiedChallenge(assertion.clientDataJson) ?: return null
        if (challenges.claim(challenge) != binding) return null
        val credential = credentials.find(assertion.credentialId) ?: return null
        if (!belongsTo(credential, binding, assertion)) return null
        val counter = verify(assertion, credential, challenge) ?: return null
        if (!credentials.recordAssertion(credential, counter)) return null
        return proof(binding, credential)
    }

    private fun proof(binding: McpNativePasskeyBinding, credential: RegisteredCredential): McpNativePasskeyProof? {
        val now = clock.instant()
        if (binding.expiresAtEpochSecond <= now.epochSecond) return null
        val firstVerified = credential.firstVerifiedAtEpochMillis ?: return null
        if (firstVerified <= 0 || firstVerified > now.toEpochMilli() - MIN_AGE.toMillis()) return null
        val attestedCredential = Base64.getDecoder().decode(credential.attestedCredentialDataB64)
        val digest = MessageDigest.getInstance("SHA-256").digest(attestedCredential)
        val credentialReference = HexFormat.of().formatHex(digest)
        return McpNativePasskeyProof(binding, credentialReference, firstVerified, now.toEpochMilli())
    }

    private fun verifiedChallenge(clientData: String): String? = try {
        mapper.readTree(Base64UrlUtil.decode(clientData))?.get("challenge")?.textValue()?.takeIf(CHALLENGE::matches)
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: JacksonException) {
        null
    }

    private fun belongsTo(
        credential: RegisteredCredential,
        binding: McpNativePasskeyBinding,
        assertion: AuthCompleteRequestDto,
    ): Boolean = credential.credentialId == assertion.credentialId &&
        credential.partyId == binding.partyId.toString() &&
        credential.keycloakUserId == binding.keycloakUserId

    private fun verify(assertion: AuthCompleteRequestDto, credential: RegisteredCredential, challenge: String): Long? =
        try {
            WebAuthnAssertionVerifier.verify(assertion, credential, challenge, rpId, origin)
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: VerificationException) {
            null
        }

    private companion object {
        const val CHALLENGE_BYTES = 32
        val CHALLENGE = Regex("[A-Za-z0-9_-]{43}")
        val MIN_AGE: Duration = Duration.ofHours(72)
    }
}
