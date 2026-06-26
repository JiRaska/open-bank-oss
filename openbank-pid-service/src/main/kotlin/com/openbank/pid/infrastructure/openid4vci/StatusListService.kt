// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.pid.infrastructure.openid4vci

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.pid.application.port.out.CredentialStatusPort
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jose4j.base64url.Base64Url
import java.time.Clock
import java.time.Instant
import java.util.BitSet
import java.util.zip.Deflater

/**
 * IETF Token Status List for issued PID credentials (eIDAS 2.0, ADR-0094) — how a bank REVOKES a
 * credential it issued.
 *
 * Each issued credential is assigned a 1-bit status at a monotonically-increasing index (0 = valid,
 * 1 = revoked) and carries a `status.status_list` claim `{idx, uri}`. The list is published as a
 * signed Status List Token (a JWS, `typ=statuslist+jwt`) whose `status_list.lst` is the
 * DEFLATE-compressed, base64url-encoded bit array — a relying party fetches it and checks the bit.
 * Revoking is flipping one bit; the next published token reflects it.
 *
 * Index allocation and per-index revocation state are delegated to a [StatusListStore]: in
 * production [PostgresStatusListStore] persists both the allocation counter (a DB sequence) and the
 * revocations (a `revoked` column) so they SURVIVE A POD RESTART and are consistent across replicas —
 * a fail-open "restart un-revokes everything" status list does not meet eIDAS 2.0. The in-memory
 * [InMemoryStatusListStore] remains the fast test/dev fallback. Signing is delegated to the shared
 * [EudiIssuerKey]; with no key configured the list is empty and [enabled] is false (fail-closed,
 * consistent with issuance).
 */
@ApplicationScoped
class StatusListService(
    private val issuerKey: EudiIssuerKey,
    private val objectMapper: ObjectMapper,
    private val store: StatusListStore,
    @ConfigProperty(name = "openbank.pid.eudi.issuer.status-list-id", defaultValue = "1")
    private val listId: String,
    @ConfigProperty(name = "openbank.pid.eudi.issuer.status-list-ttl-seconds", defaultValue = "3600")
    private val ttlSeconds: Long,
    private val clock: Clock,
) : CredentialStatusPort {

    val enabled: Boolean get() = issuerKey.enabled

    /** The `uri` placed in every issued credential's `status.status_list` claim. */
    val statusListUri: String get() = "${issuerKey.issuerId}/api/v1/parties/eudi/status-lists/$listId"

    val id: String get() = listId

    /** How long (seconds) a published status-list token is cacheable — mirrored into Cache-Control. */
    val cacheTtlSeconds: Long get() = ttlSeconds

    /** Reserve the next status index for a credential about to be issued. */
    suspend fun allocate(): Long = store.allocate()

    /** Flip a credential's status to revoked. Returns false for an index never allocated. */
    suspend fun revoke(index: Long): Boolean = store.revoke(index)

    suspend fun isRevoked(index: Long): Boolean = store.isRevoked(index)

    /** [CredentialStatusPort]: only OUR list resolves here; a foreign status-list uri is not-revoked. */
    override suspend fun isRevoked(uri: String, index: Long): Boolean = uri == statusListUri && isRevoked(index)

    /** The signed Status List Token (JWS) a relying party fetches to check revocation. */
    suspend fun statusListToken(now: Instant = Instant.now(clock)): String {
        val packed = packRevokedBits(store.revokedIndices())
        val lst = Base64Url.encode(deflate(packed))
        val payload = objectMapper.createObjectNode().apply {
            put("iss", issuerKey.issuerId)
            put("sub", statusListUri)
            put("iat", now.epochSecond)
            put("exp", now.epochSecond + ttlSeconds)
            put("ttl", ttlSeconds)
            set<com.fasterxml.jackson.databind.JsonNode>(
                "status_list",
                objectMapper.createObjectNode().apply {
                    put("bits", 1)
                    put("lst", lst)
                },
            )
        }
        return issuerKey.sign(objectMapper.writeValueAsString(payload), typ = "statuslist+jwt")
    }

    /** Pack the revoked indices into the IETF Token Status List bit array (1 bit/credential, LSB-0). */
    private fun packRevokedBits(indices: List<Long>): ByteArray {
        val bits = BitSet()
        indices.forEach { if (it in 0..Int.MAX_VALUE.toLong()) bits.set(it.toInt()) }
        return bits.toByteArray()
    }

    private fun deflate(input: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(input)
        deflater.finish()
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(DEFLATE_BUF)
        while (!deflater.finished()) {
            out.write(buf, 0, deflater.deflate(buf))
        }
        deflater.end()
        return out.toByteArray()
    }

    private companion object {
        const val DEFLATE_BUF = 1024
    }
}
