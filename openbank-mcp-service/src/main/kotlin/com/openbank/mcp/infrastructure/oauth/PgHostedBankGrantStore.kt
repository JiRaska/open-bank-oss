// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp.infrastructure.oauth

import com.openbank.mcp.infrastructure.oauth.HostedBankGrantStore.Record
import com.openbank.mcp.infrastructure.oauth.HostedOAuthAdmission.Vendor
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.pgclient.PgPool
import io.vertx.mutiny.sqlclient.Tuple
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

internal class PgHostedBankGrantStore(private val pool: PgPool) : HostedBankGrantStore {
    override suspend fun put(vendor: Vendor, connectionId: UUID, sealed: String, expires: Instant): Boolean =
        pool.preparedQuery(
            """
            INSERT INTO mcp_hosted_bank_grants (vendor, connection_id, sealed_tokens, expires_at)
            VALUES ($1, $2, $3, $4)
            ON CONFLICT DO NOTHING
            """.trimIndent(),
        ).execute(Tuple.of(vendor.name, connectionId, sealed, OffsetDateTime.ofInstant(expires, ZoneOffset.UTC)))
            .awaitSuspending().rowCount() == 1

    override suspend fun get(vendor: Vendor, connectionId: UUID): Record? = pool.preparedQuery(
        """
        SELECT sealed_tokens, expires_at FROM mcp_hosted_bank_grants
        WHERE vendor = $1 AND connection_id = $2 AND rotation_id IS NULL AND expires_at > statement_timestamp()
        """.trimIndent(),
    ).execute(Tuple.of(vendor.name, connectionId)).awaitSuspending().firstOrNull()?.let { row ->
        Record(row.getString("sealed_tokens"), row.getOffsetDateTime("expires_at").toInstant())
    }

    override suspend fun erase(vendor: Vendor, connectionId: UUID) {
        pool.preparedQuery("DELETE FROM mcp_hosted_bank_grants WHERE vendor = $1 AND connection_id = $2")
            .execute(Tuple.of(vendor.name, connectionId)).awaitSuspending()
    }

    override suspend fun claim(vendor: Vendor, connectionId: UUID, operation: UUID): Record? = pool.preparedQuery(
        """
        UPDATE mcp_hosted_bank_grants SET rotation_id = $3
        WHERE vendor = $1 AND connection_id = $2 AND rotation_id IS NULL AND expires_at > statement_timestamp()
        RETURNING sealed_tokens, expires_at
        """.trimIndent(),
    ).execute(
        Tuple.of(vendor.name, connectionId, operation),
    ).awaitSuspending().firstOrNull()?.let { row ->
        Record(row.getString("sealed_tokens"), row.getOffsetDateTime("expires_at").toInstant())
    }

    override suspend fun replace(
        vendor: Vendor,
        connectionId: UUID,
        operation: UUID,
        sealed: String,
        expires: Instant,
    ): Boolean = pool.preparedQuery(
        """
        UPDATE mcp_hosted_bank_grants SET sealed_tokens = $4, expires_at = $5, rotation_id = NULL
        WHERE vendor = $1 AND connection_id = $2 AND rotation_id = $3
        """.trimIndent(),
    ).execute(Tuple.of(vendor.name, connectionId, operation, sealed, OffsetDateTime.ofInstant(expires, ZoneOffset.UTC)))
        .awaitSuspending().rowCount() == 1

    override suspend fun release(vendor: Vendor, connectionId: UUID, operation: UUID) {
        pool.preparedQuery(
            """
            UPDATE mcp_hosted_bank_grants SET rotation_id = NULL
            WHERE vendor = $1 AND connection_id = $2 AND rotation_id = $3
            """.trimIndent(),
        ).execute(Tuple.of(vendor.name, connectionId, operation)).awaitSuspending()
    }

    override suspend fun discard(vendor: Vendor, connectionId: UUID, operation: UUID) {
        pool.preparedQuery(
            "DELETE FROM mcp_hosted_bank_grants WHERE vendor = $1 AND connection_id = $2 AND rotation_id = $3",
        )
            .execute(Tuple.of(vendor.name, connectionId, operation)).awaitSuspending()
    }

    suspend fun deleteExpiredBatch(): Int = pool.preparedQuery(
        """
        WITH expired AS (
            SELECT connection_id FROM mcp_hosted_bank_grants
            WHERE expires_at <= statement_timestamp()
            ORDER BY expires_at
            LIMIT 500 FOR UPDATE SKIP LOCKED
        )
        DELETE FROM mcp_hosted_bank_grants AS grants
        USING expired WHERE grants.connection_id = expired.connection_id
        """.trimIndent(),
    ).execute().awaitSuspending().rowCount()
}
