// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.oauth

import com.openbank.mcp.infrastructure.oauth.HostedOAuthAdmission.Vendor
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.core.buffer.Buffer
import io.vertx.mutiny.pgclient.PgPool
import io.vertx.mutiny.sqlclient.Tuple

private const val CALLBACK_EXPIRY_SECONDS = 600
private const val CODE_EXPIRY_SECONDS = 60

/**
 * Durable, single-use bank callback and vendor code state. The caller seals the payload using
 * authenticated encryption with the record kind, digests and vendor as associated data.
 * The bank callback binds to the initiating browser; vendor code redemption binds to S256.
 */
internal class PgHostedOAuthTransactions(private val pool: PgPool) {
    enum class Kind(val lifetimeSeconds: Int) {
        BANK_CALLBACK(CALLBACK_EXPIRY_SECONDS),
        VENDOR_CODE(CODE_EXPIRY_SECONDS),
    }

    class Consumed(val vendor: Vendor, val sealedPayload: ByteArray)

    suspend fun put(
        kind: Kind,
        handleDigest: String,
        vendor: Vendor,
        bindingDigest: String,
        sealedPayload: ByteArray,
    ): Boolean = pool.preparedQuery(
        """
        INSERT INTO mcp_hosted_oauth_transactions
            (kind, handle_digest, vendor, binding_digest, sealed_payload, expires_at)
        VALUES ($1, $2, $3, $4, $5, statement_timestamp() + make_interval(secs => $6))
        ON CONFLICT DO NOTHING
        """.trimIndent(),
    ).execute(
        Tuple.of(
            kind.name,
            handleDigest,
            vendor.name,
            bindingDigest,
            Buffer.buffer(sealedPayload),
            kind.lifetimeSeconds.toDouble(),
        ),
    ).awaitSuspending().rowCount() == 1

    suspend fun consumeBankCallback(handleDigest: String, browserBindingDigest: String): Consumed? =
        consume(Kind.BANK_CALLBACK, handleDigest, browserBindingDigest, null)

    suspend fun consumeVendorCode(handleDigest: String, pkceDigest: String, authenticatedVendor: Vendor): Consumed? =
        consume(Kind.VENDOR_CODE, handleDigest, pkceDigest, authenticatedVendor)

    suspend fun deleteExpiredBatch(): Int = pool.preparedQuery(
        """
        WITH expired AS (
            SELECT kind, handle_digest
            FROM mcp_hosted_oauth_transactions
            WHERE expires_at <= statement_timestamp()
            ORDER BY expires_at
            LIMIT 500
            FOR UPDATE SKIP LOCKED
        )
        DELETE FROM mcp_hosted_oauth_transactions AS transactions
        USING expired
        WHERE transactions.kind = expired.kind AND transactions.handle_digest = expired.handle_digest
        """.trimIndent(),
    ).execute().awaitSuspending().rowCount()

    private suspend fun consume(kind: Kind, handleDigest: String, bindingDigest: String, vendor: Vendor?): Consumed? =
        pool.preparedQuery(
            """
            DELETE FROM mcp_hosted_oauth_transactions
            WHERE kind = $1 AND handle_digest = $2 AND binding_digest = $3
                AND ($4::text IS NULL OR vendor = $4) AND expires_at > statement_timestamp()
            RETURNING vendor, sealed_payload
            """.trimIndent(),
        ).execute(Tuple.of(kind.name, handleDigest, bindingDigest, vendor?.name)).awaitSuspending().firstOrNull()?.let {
            Consumed(Vendor.valueOf(it.getString("vendor")), it.getBuffer("sealed_payload").bytes)
        }
}
