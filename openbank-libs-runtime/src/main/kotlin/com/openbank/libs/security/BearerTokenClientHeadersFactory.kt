// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.security

import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import jakarta.ws.rs.core.MultivaluedHashMap
import jakarta.ws.rs.core.MultivaluedMap
import org.eclipse.microprofile.rest.client.ext.ClientHeadersFactory
import org.jboss.logging.Logger

/**
 * Adds `Authorization: Bearer <service-token>` to every outbound REST client call that
 * declares this factory:
 *
 *     @RegisterClientHeaders(BearerTokenClientHeadersFactory::class)
 *     @RegisterRestClient(configKey = "ledger-api")
 *     interface LedgerClient { ... }
 *
 * Also propagates correlation ID so the downstream service's audit trail joins to ours.
 *
 * Resolution order for the token:
 *   1. `ServiceTokenProvider` CDI bean (if any) — production path with OIDC client.
 *   2. Pass-through of the inbound request's `Authorization` header — useful when the
 *      caller is acting on behalf of an end user (act-as flow).
 *   3. No header — the downstream service will 401, which is what we want.
 */
@ApplicationScoped
class BearerTokenClientHeadersFactory : ClientHeadersFactory {

    private val log = Logger.getLogger(BearerTokenClientHeadersFactory::class.java)

    @Inject
    lateinit var tokenProvider: Instance<ServiceTokenProvider>

    override fun update(
        incoming: MultivaluedMap<String, String>,
        outgoing: MultivaluedMap<String, String>,
    ): MultivaluedMap<String, String> {
        val merged = MultivaluedHashMap<String, String>().apply { putAll(outgoing) }

        val authHeader = when {
            tokenProvider.isResolvable -> "Bearer ${tokenProvider.get().getToken()}"
            incoming.containsKey("Authorization") -> incoming.getFirst("Authorization")
            else -> {
                log.warnf(
                    "BearerTokenClientHeadersFactory: no ServiceTokenProvider bean and no inbound Authorization — downstream will receive 401",
                )
                null
            }
        }
        if (authHeader != null && !merged.containsKey("Authorization")) {
            merged.putSingle("Authorization", authHeader)
        }

        listOf("X-Correlation-ID", "X-Request-ID").forEach { header ->
            incoming.getFirst(header)?.let { if (!merged.containsKey(header)) merged.putSingle(header, it) }
        }

        return merged
    }
}
