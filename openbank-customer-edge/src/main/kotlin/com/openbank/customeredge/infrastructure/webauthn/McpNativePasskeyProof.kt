// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.webauthn

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.ObjectMapper
import com.webauthn4j.util.Base64UrlUtil
import com.webauthn4j.verifier.exception.VerificationException
import io.micrometer.core.instrument.MeterRegistry
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

/** An authority-authenticated PAR mode. Hosted assertions do not manufacture a DPoP thumbprint. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = McpNativeParBinding.Dpop::class, name = "dpop"),
    JsonSubTypes.Type(value = McpNativeParBinding.ConfidentialClient::class, name = "confidential_client"),
)
internal sealed interface McpNativeParBinding {
    data class Dpop(val thumbprint: String) : McpNativeParBinding {
        init {
            require(PAR_THUMBPRINT.matches(thumbprint))
            val decoded = Base64UrlUtil.decode(thumbprint)
            require(decoded.size == SHA256_BYTES && Base64UrlUtil.encodeToString(decoded) == thumbprint)
        }

        private companion object {
            const val SHA256_BYTES = 32
            val PAR_THUMBPRINT = Regex("[A-Za-z0-9_-]{43}")
        }
    }

    data object ConfidentialClient : McpNativeParBinding
}

/** Supplied by bank authority state and the authenticated app identity, never by the wire DTO. */
internal data class McpNativePasskeyBinding(
    val requestRef: String,
    val partyId: UUID,
    val keycloakUserId: String,
    val clientId: String,
    val parBinding: McpNativeParBinding,
    val contextSha256: String,
    val expiresAtEpochSecond: Long,
) {
    init {
        require(REFERENCE.matches(requestRef) && REFERENCE.matches(contextSha256))
        require(keycloakUserId.isNotBlank() && keycloakUserId.length <= MAX_ID_LENGTH)
        require(clientId.isNotBlank() && clientId.length <= MAX_ID_LENGTH)
    }

    private companion object {
        const val MAX_ID_LENGTH = 255
        val REFERENCE = Regex("[0-9a-f]{64}")
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
    private val metrics: MeterRegistry,
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
        if (binding.expiresAtEpochSecond <= clock.instant().epochSecond) return reject(Refusal.EXPIRED)
        val challenge = verifiedChallenge(assertion.clientDataJson) ?: return reject(Refusal.CHALLENGE_INVALID)
        val challenged = challenges.claim(challenge) ?: return reject(Refusal.CHALLENGE_MISSING)
        if (challenged != binding) return reject(Refusal.CONTEXT_MISMATCH)
        val credential = credentials.find(assertion.credentialId) ?: return reject(Refusal.CREDENTIAL_UNKNOWN)
        if (!belongsTo(credential, binding, assertion)) return reject(Refusal.OWNER_MISMATCH)
        val counter = verify(assertion, credential, challenge) ?: return reject(Refusal.ASSERTION_INVALID)
        if (!credentials.recordAssertion(credential, counter)) return reject(Refusal.ASSERTION_CONFLICT)
        return proof(binding, credential)
    }

    private fun proof(binding: McpNativePasskeyBinding, credential: RegisteredCredential): McpNativePasskeyProof? {
        val now = clock.instant()
        if (binding.expiresAtEpochSecond <= now.epochSecond) return reject(Refusal.EXPIRED)
        val firstVerified = credential.firstVerifiedAtEpochMillis ?: return reject(Refusal.AGE_MISSING)
        if (firstVerified <= 0) return reject(Refusal.AGE_INVALID)
        if (firstVerified > now.toEpochMilli() - MIN_AGE.toMillis()) return reject(Refusal.AGE_TOO_YOUNG)
        val attestedCredential = Base64.getDecoder().decode(credential.attestedCredentialDataB64)
        val digest = MessageDigest.getInstance("SHA-256").digest(attestedCredential)
        val credentialReference = HexFormat.of().formatHex(digest)
        return McpNativePasskeyProof(binding, credentialReference, firstVerified, now.toEpochMilli())
    }

    private fun reject(reason: Refusal): McpNativePasskeyProof? {
        metrics.counter("openbank.edge.mcp.native.proof.rejected", "reason", reason.name).increment()
        return null
    }

    private enum class Refusal {
        EXPIRED,
        CHALLENGE_INVALID,
        CHALLENGE_MISSING,
        CONTEXT_MISMATCH,
        CREDENTIAL_UNKNOWN,
        OWNER_MISMATCH,
        ASSERTION_INVALID,
        ASSERTION_CONFLICT,
        AGE_MISSING,
        AGE_INVALID,
        AGE_TOO_YOUNG,
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
