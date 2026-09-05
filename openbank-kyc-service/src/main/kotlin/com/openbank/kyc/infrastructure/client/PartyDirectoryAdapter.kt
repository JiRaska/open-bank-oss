// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.infrastructure.client

import com.openbank.kyc.application.port.out.PartyDirectoryPage
import com.openbank.kyc.application.port.out.PartyDirectoryPort
import com.openbank.kyc.application.port.out.PartySummary
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger

/**
 * Adapts [PartyServiceClient] to [PartyDirectoryPort] (ADR-0002 — the application layer depends on
 * the port, never on the REST client).
 *
 * Maps only `id`, `status` and `createdAt`; see [PartyListResponse] for why the rest of the payload
 * is deliberately not bound.
 */
@ApplicationScoped
class PartyDirectoryAdapter(@RestClient private val client: PartyServiceClient) : PartyDirectoryPort {

    private val log = Logger.getLogger(PartyDirectoryAdapter::class.java)

    override suspend fun listParties(page: Int, size: Int): PartyDirectoryPage {
        val response = client.listParties(page, size).awaitSuspending()
        // A row missing any of the three fields cannot be reconciled either way, so it is dropped
        // rather than guessed at — but it is logged, because silently shrinking the scanned set is
        // how a detection control starts under-reporting without anything looking wrong.
        val items = response.items.mapNotNull { item ->
            val id = item.id
            val status = item.status
            val createdAt = item.createdAt
            if (id == null || status == null || createdAt == null) {
                log.warnf(
                    "[orphan-detection] Skipping a party row missing id/status/createdAt on page %d " +
                        "(id=%s) — it cannot be reconciled and is NOT counted as scanned",
                    page,
                    id,
                )
                null
            } else {
                PartySummary(id = id, status = status, createdAt = createdAt)
            }
        }
        return PartyDirectoryPage(items = items, total = response.total)
    }
}
