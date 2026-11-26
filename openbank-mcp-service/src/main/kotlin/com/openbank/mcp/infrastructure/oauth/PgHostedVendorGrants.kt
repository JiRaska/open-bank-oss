// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp.infrastructure.oauth

import com.openbank.mcp.infrastructure.oauth.HostedOAuthAdmission.Vendor
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.pgclient.PgPool
import io.vertx.mutiny.sqlclient.Tuple
import java.util.UUID

internal class PgHostedVendorGrants(private val pool: PgPool) {
    class Identity(val vendor: Vendor, val connectionId: UUID)

    suspend fun create(identity: Identity, accessDigest: String, refreshDigest: String) {
        pool.preparedQuery(
            """
            WITH family AS (
                INSERT INTO mcp_hosted_vendor_families (family_id, connection_id, vendor, expires_at)
                VALUES ($1, $2, $3, statement_timestamp() + interval '30 days')
                RETURNING family_id, generation, expires_at
            )
            INSERT INTO mcp_hosted_vendor_tokens (token_digest, kind, family_id, generation, expires_at)
            SELECT $4, 'ACCESS', family_id, generation, statement_timestamp() + interval '5 minutes' FROM family
            UNION ALL
            SELECT $5, 'REFRESH', family_id, generation, expires_at FROM family
            """.trimIndent(),
        ).execute(
            Tuple.of(UUID.randomUUID(), identity.connectionId, identity.vendor.name, accessDigest, refreshDigest),
        ).awaitSuspending()
    }

    suspend fun accessIdentity(digest: String): Identity? = pool.preparedQuery(
        """
        SELECT f.vendor, f.connection_id
        FROM mcp_hosted_vendor_tokens t
        JOIN mcp_hosted_vendor_families f ON f.family_id = t.family_id
        WHERE t.kind = 'ACCESS' AND t.token_digest = $1 AND f.revoked_at IS NULL
            AND f.expires_at > statement_timestamp() AND t.expires_at > statement_timestamp()
        """.trimIndent(),
    ).execute(Tuple.of(digest)).awaitSuspending().firstOrNull()?.let {
        Identity(Vendor.valueOf(it.getString("vendor")), it.getUUID("connection_id"))
    }

    /** A used generation revokes its family before we consult any remote grant authority. */
    suspend fun refreshIdentity(digest: String, vendor: Vendor): Identity? = pool.preparedQuery(
        """
        WITH candidate AS (
            SELECT f.family_id, f.connection_id, f.generation, t.generation AS presented, t.expires_at
            FROM mcp_hosted_vendor_tokens t
            JOIN mcp_hosted_vendor_families f ON f.family_id = t.family_id
            WHERE t.kind = 'REFRESH' AND t.token_digest = $1 AND f.vendor = $2
                AND f.revoked_at IS NULL AND f.expires_at > statement_timestamp()
            FOR UPDATE OF f
        ), revoked AS (
            UPDATE mcp_hosted_vendor_families f
            SET revoked_at = statement_timestamp()
            FROM candidate c
            WHERE f.family_id = c.family_id AND c.presented <> c.generation
        )
        SELECT connection_id FROM candidate
        WHERE presented = generation AND expires_at > statement_timestamp()
        """.trimIndent(),
    ).execute(Tuple.of(digest, vendor.name)).awaitSuspending().firstOrNull()?.let {
        Identity(vendor, it.getUUID("connection_id"))
    }

    /** Lock serializes rotations, including a concurrent reuse of the same prior refresh. */
    suspend fun rotate(
        refreshDigest: String,
        identity: Identity,
        nextAccessDigest: String,
        nextRefreshDigest: String,
    ): Boolean = pool.preparedQuery(
        """
        WITH candidate AS (
            SELECT f.family_id, f.generation, t.generation AS presented
            FROM mcp_hosted_vendor_tokens t
            JOIN mcp_hosted_vendor_families f ON f.family_id = t.family_id
            WHERE t.token_digest = $1 AND t.kind = 'REFRESH'
                AND f.vendor = $2 AND f.connection_id = $3 AND f.revoked_at IS NULL
                AND f.expires_at > statement_timestamp()
                AND (t.generation <> f.generation OR t.expires_at > statement_timestamp())
            FOR UPDATE OF f
        ), rotated AS (
            UPDATE mcp_hosted_vendor_families f
            SET generation = f.generation + 1, expires_at = statement_timestamp() + interval '30 days'
            FROM candidate c
            WHERE f.family_id = c.family_id AND c.presented = c.generation
            RETURNING f.family_id, f.generation, f.expires_at
        ), revoked AS (
            UPDATE mcp_hosted_vendor_families f
            SET revoked_at = statement_timestamp()
            FROM candidate c
            WHERE f.family_id = c.family_id AND c.presented <> c.generation
        )
        INSERT INTO mcp_hosted_vendor_tokens (token_digest, kind, family_id, generation, expires_at)
        SELECT $4, 'ACCESS', family_id, generation, statement_timestamp() + interval '5 minutes' FROM rotated
        UNION ALL
        SELECT $5, 'REFRESH', family_id, generation, expires_at FROM rotated
        """.trimIndent(),
    ).execute(
        Tuple.of(refreshDigest, identity.vendor.name, identity.connectionId, nextAccessDigest, nextRefreshDigest),
    ).awaitSuspending().rowCount() == 2

    suspend fun revoke(identity: Identity): Int = pool.preparedQuery(
        """
        UPDATE mcp_hosted_vendor_families
        SET revoked_at = statement_timestamp()
        WHERE connection_id = $1 AND vendor = $2 AND revoked_at IS NULL
        """.trimIndent(),
    ).execute(Tuple.of(identity.connectionId, identity.vendor.name)).awaitSuspending().rowCount()

    /** Used refresh digests remain as tombstones until the family ends. */
    suspend fun deleteExpiredBatch(): Int {
        val expiredAccess = pool.query(
            """
            WITH expired AS (
                SELECT token_digest FROM mcp_hosted_vendor_tokens
                WHERE kind = 'ACCESS' AND expires_at <= statement_timestamp()
                ORDER BY expires_at LIMIT 500 FOR UPDATE SKIP LOCKED
            )
            DELETE FROM mcp_hosted_vendor_tokens t USING expired
            WHERE t.token_digest = expired.token_digest
            """.trimIndent(),
        ).execute().awaitSuspending().rowCount()
        val expiredFamilies = pool.query(
            """
            WITH expired AS (
                SELECT family_id FROM mcp_hosted_vendor_families
                WHERE expires_at <= statement_timestamp() OR revoked_at IS NOT NULL
                ORDER BY expires_at LIMIT 500 FOR UPDATE SKIP LOCKED
            )
            DELETE FROM mcp_hosted_vendor_families f USING expired
            WHERE f.family_id = expired.family_id
            """.trimIndent(),
        ).execute().awaitSuspending().rowCount()
        return expiredAccess + expiredFamilies
    }
}
